package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.telegram.tgnet.TLRPC;
import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device adult-content filter. A small MobileNetV2 classifier (GantMan nsfw_model, MIT) looks at every
 * photo, video cover, GIF and sticker; nothing leaves the phone. Ahead-of-view preview checks use a bounded
 * background queue, while visible media is prioritized. Unknown media has a neutral opaque placeholder;
 * only a confirmed unsafe verdict uses Telegram's media spoiler. A safe verdict removes the placeholder,
 * an unsafe one keeps it covered with a "hidden content" label, and it can't be opened while the filter is on. Verdicts are remembered per photo/document id, so the
 * check runs once per picture. Outgoing pictures that look explicit are sent as spoilers, with a notice.
 */
public final class UMessageNsfwFilter {

    private static final String MODEL = "models/nsfw_mobilenet_v2.tflite";
    /**
     * Second opinion (Yahoo Open NSFW, ResNet-50): the small model alone hid gym, boxing, massage and car
     * interior photos (skin and muscle read as porn). A picture is hidden only when both agree; the second
     * model only runs on what the first one doubts, so ordinary pictures cost one fast pass.
     */
    private static final String MODEL2 = "models/open_nsfw2.tflite";
    /**
     * Body parts (NudeNet, AGPL-3.0): what an uncovered belly, chest or underwear looks like. The small model's
     * "sexy" class also fired on ordinary photos of dressed girls, so revealing pictures are decided by this one.
     */
    private static final String MODEL3 = "models/nudenet_320n.tflite";
    private static final int BODY_SIZE = 320, BODY_ANCHORS = 2100, BODY_CLASSES = 18;
    private static final int FEMALE_GENITALIA_COVERED = 0, FACE_FEMALE = 1, BUTTOCKS_EXPOSED = 2, FEMALE_BREAST_EXPOSED = 3,
            FEMALE_GENITALIA_EXPOSED = 4, MALE_BREAST_EXPOSED = 5, ANUS_EXPOSED = 6, FACE_MALE = 12, BELLY_EXPOSED = 13,
            MALE_GENITALIA_EXPOSED = 14, FEMALE_BREAST_COVERED = 16, BUTTOCKS_COVERED = 17;
    private static final int SIZE = 224;
    // class order of the model's output
    private static final int DRAWINGS = 0, HENTAI = 1, NEUTRAL = 2, PORN = 3, SEXY = 4;
    private static final int MAX_VERDICTS = 20000;

    /** How explicit a picture is; a level hides everything at or above its threshold. */
    private static final byte SAFE = 0, MILD = 1, REVEALING = 2, EXPLICIT = 3;
    public static final int LEVEL_LOW = 0, LEVEL_MEDIUM = 1, LEVEL_HIGH = 2;

    private static final UMessageNsfwWorkQueue worker = new UMessageNsfwWorkQueue();
    // read by state() from any thread (binds, prefetch, worker): concurrent collections
    private static final java.util.Map<String, Long> visibleUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> retryAfter = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> sampled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> sampling = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.LinkedHashMap<String, Runnable> readyActions = new java.util.LinkedHashMap<>();
    private static final long RETRY_DELAY = 30_000;
    private static final int MAX_CLASSIFICATIONS = 12;
    private static final long MAX_FRAME_PIXELS = 1024 * 1024;
    private static final java.util.LinkedHashMap<String, ArrayList<java.lang.ref.WeakReference<MessageObject>>> watching = new java.util.LinkedHashMap<>();

    private static Interpreter interpreter, interpreter2, interpreter3;
    private static boolean modelMissing, model2Missing, model3Missing;
    private static ByteBuffer input3;
    private static final float[][][] output3 = new float[1][BODY_CLASSES][BODY_ANCHORS];
    private static ByteBuffer input2;
    private static final float[][] output2 = new float[1][2];
    private static ByteBuffer input;
    private static final float[][] output = new float[1][5];

    /** key -> SAFE..EXPLICIT, oldest evicted first; saved so a picture is judged once, not once per launch */
    private static final LinkedHashMap<String, Byte> verdicts = new LinkedHashMap<String, Byte>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Byte> eldest) {
            return size() > MAX_VERDICTS;
        }
    };
    private static final java.util.HashSet<String> running = new java.util.HashSet<>();
    /** UI-thread callbacks waiting on a judgement already running, by key; guarded by verdicts. */
    private static final java.util.HashMap<String, ArrayList<Runnable>> waiting = new java.util.HashMap<>();
    /** loadStarted: UI thread; loaded: the saved verdicts are in (until then every picture counts as unjudged). */
    private static boolean loadStarted;
    private static volatile boolean loaded;
    private static final Runnable save = UMessageNsfwFilter::saveNow;

    private UMessageNsfwFilter() {
    }

    // ---------------------------------------------------------------- settings

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_nsfw", Context.MODE_PRIVATE);
    }

    private static Boolean enabled;

    public static boolean isEnabled() {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.NSFW_FILTER)) return false;
        if (enabled == null) {
            enabled = prefs().getBoolean("enabled", true);
            // without the bundled model there is no filter at all, rather than every picture left blurred
            try (InputStream ignored = ApplicationLoader.applicationContext.getAssets().open(MODEL)) {
                modelMissing = false;
            } catch (java.io.FileNotFoundException e) {
                modelMissing = true;
            } catch (Throwable e) {
                modelMissing = false; // not a missing model: the filter stays on and loads it later
            }
        }
        return (enabled || parentalLocked()) && !modelMissing;
    }

    private static long parentalCheckedAt;
    private static boolean parental;

    /** A phone under parental control always filters, and the switch can't be turned off. */
    public static boolean parentalLocked() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - parentalCheckedAt > 5000) {
            parentalCheckedAt = now;
            try {
                parental = UMessageParental.isControlled();
            } catch (Throwable e) {
                parental = false;
            }
        }
        return parental;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        prefs().edit().putBoolean("enabled", value).apply();
    }

    private static int level = -1;

    /** Low hides only porn and hentai, medium also revealing photos, high anything suggestive. */
    public static int getLevel() {
        if (level < 0) level = prefs().getInt("level", LEVEL_MEDIUM);
        // parental control never goes below medium
        return parentalLocked() ? Math.max(level, LEVEL_MEDIUM) : level;
    }

    public static void setLevel(int value) {
        level = value;
        prefs().edit().putInt("level", value).apply();
        // the new level applies at once: open chats redraw their media with it
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
            }
        }
    }

    private static boolean hides(byte v) {
        int threshold = getLevel() == LEVEL_LOW ? EXPLICIT : getLevel() == LEVEL_HIGH ? MILD : REVEALING;
        return v >= threshold;
    }

    // ---------------------------------------------------------------- incoming media

    /** Photos, videos, GIFs and round videos, ours included (Saved Messages, forwards) once they are sent. */
    private static boolean applies(MessageObject m) {
        if (m == null || m.messageOwner == null || m.sendPreview || m.isRepostPreview || m.getId() <= 0) return false;
        return m.type == MessageObject.TYPE_PHOTO || m.type == MessageObject.TYPE_VIDEO || m.type == MessageObject.TYPE_GIF
                || m.type == MessageObject.TYPE_ROUND_VIDEO;
    }

    /** Stickers (static, animated, video), ours included once sent; dice and animated emoji are left alone. */
    public static boolean isSticker(MessageObject m) {
        return m != null && m.messageOwner != null && !m.sendPreview && !m.isRepostPreview && m.getId() > 0
                && (m.type == MessageObject.TYPE_STICKER || m.type == MessageObject.TYPE_ANIMATED_STICKER)
                && !m.isDice() && !m.isAnimatedEmoji();
    }

    /** Only a confirmed unsafe result uses a blur; unknown media uses a neutral placeholder. */
    public static boolean blursSticker(MessageObject message) {
        return isSticker(message) && state(message) == UMessageNsfwDecision.State.HIDDEN;
    }

    private static String key(MessageObject m) {
        TLRPC.MessageMedia media = MessageObject.getMedia(m.messageOwner);
        if (media == null) return null;
        if (media.photo != null && media.photo.id != 0) return "p" + media.photo.id;
        if (media.document != null && media.document.id != 0) return "d" + media.document.id;
        return null;
    }

    public static boolean blurs(MessageObject message) {
        return applies(message) && state(message) == UMessageNsfwDecision.State.HIDDEN;
    }

    public static UMessageNsfwDecision.State state(MessageObject message) {
        if (!isEnabled() || !(applies(message) || isSticker(message))) return UMessageNsfwDecision.State.CLEAR;
        String key = key(message);
        if (key == null) return UMessageNsfwDecision.State.CLEAR;
        ensureLoaded();
        Byte verdict;
        synchronized (verdicts) { verdict = verdicts.get(key); }
        Long retry = retryAfter.get(key);
        return UMessageNsfwDecision.resolve(true, true, verdict, getLevel(), isRevealed(key),
                sampling.contains(key), retry != null && retry > android.os.SystemClock.elapsedRealtime());
    }

    /** Pictures the user opened anyway ("Ko'rsatish"), until the chat is closed; never while parental control is on. */
    private static final java.util.HashSet<String> revealed = new java.util.HashSet<>();

    private static boolean isRevealed(String key) {
        if (parentalLocked()) return false;
        synchronized (revealed) {
            return revealed.contains(key);
        }
    }

    /** The chat was closed: what was opened anyway is covered again. */
    public static void clearRevealed() {
        synchronized (revealed) {
            revealed.clear();
        }
    }

    public static boolean canReveal() {
        return !parentalLocked();
    }

    /** Shows one hidden picture anyway, once: leaving the chat covers it again. */
    public static void reveal(MessageObject m) {
        if (!canReveal() || m == null) return;
        String key = key(m);
        if (key == null) return;
        synchronized (revealed) {
            revealed.add(key);
        }
        ArrayList<MessageObject> list = new ArrayList<>();
        list.add(m);
        NotificationCenter.getInstance(m.currentAccount).postNotificationName(NotificationCenter.replaceMessagesObjects, m.getDialogId(), list);
    }

    /** UI-thread entry point for a bound/visible cell or media viewer. */
    public static void onVisible(MessageObject message) {
        if (message == null || !isEnabled() || !(applies(message) || isSticker(message))) return;
        String key = key(message);
        if (key == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        visibleUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        visibleUntil.put(key, now + 45_000);
        ArrayList<java.lang.ref.WeakReference<MessageObject>> instances = watching.get(key);
        if (instances == null) watching.put(key, instances = new ArrayList<>());
        instances.removeIf(reference -> reference.get() == null);
        boolean registered = false;
        for (java.lang.ref.WeakReference<MessageObject> reference : instances) if (reference.get() == message) registered = true;
        if (!registered) {
            if (instances.size() >= 16) instances.remove(0);
            instances.add(new java.lang.ref.WeakReference<>(message));
        }
        while (watching.size() > 256) watching.remove(watching.keySet().iterator().next());
        worker.promote(key);
        if (message.getDocument() != null) {
            motionWatching.put(key, message);
            while (motionWatching.size() > 128) motionWatching.remove(motionWatching.keySet().iterator().next());
        }
        if (needsCheck(message)) requestProbe(message, key);
        else sampleMotion(message);
    }

    public static boolean needsCheck(MessageObject message) {
        UMessageNsfwDecision.State state = state(message);
        return state == UMessageNsfwDecision.State.CHECKING || state == UMessageNsfwDecision.State.UNAVAILABLE;
    }

    public static boolean isCheckUnavailable(MessageObject message) {
        return state(message) == UMessageNsfwDecision.State.UNAVAILABLE;
    }

    /** A single tap during checking opens automatically after a safe result. */
    public static void whenChecked(MessageObject message, Runnable open) {
        String key = key(message);
        if (key == null) return;
        if (!isBlocked(message)) { open.run(); return; }
        readyActions.put(key, open);
        while (readyActions.size() > 128) readyActions.remove(readyActions.keySet().iterator().next());
        onVisible(message);
    }

    private static void markUnavailable(MessageObject message) {
        String key = key(message);
        if (key == null || retryAfter.containsKey(key)) return;
        retryAfter.put(key, android.os.SystemClock.elapsedRealtime() + RETRY_DELAY);
        redraw(message);
        AndroidUtilities.runOnUIThread(() -> {
            retryAfter.remove(key);
            Long visible = visibleUntil.get(key);
            if (visible != null && visible > android.os.SystemClock.elapsedRealtime()) {
                requestProbe(message, key);
                redraw(message);
            }
        }, RETRY_DELAY);
    }

    private static File localMotionFile(MessageObject message) {
        if (!(message.isVideo() || message.isRoundVideo() || message.isGif() || message.isVideoSticker())) return null;
        if (message.messageOwner.attachPath != null && !message.messageOwner.attachPath.isEmpty()) {
            File attached = new File(message.messageOwner.attachPath);
            if (attached.isFile() && !attached.getName().endsWith(".enc")) return attached;
        }
        File file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        return file.isFile() && !file.getName().endsWith(".enc") ? file : null;
    }

    /** Bounded temporal pass: three small frames, without downloading the whole video for filtering. */
    private static void sampleMotion(MessageObject message) {
        if (!isEnabled()) return;
        String key = key(message);
        if (key == null || sampled.contains(key) || sampling.contains(key) || sampling.size() >= 4 || isUnsafe(message)) return;
        File file = localMotionFile(message);
        if (file == null) return; // the preview is used until a complete local file is available
        sampling.add(key);
        worker.execute(key, visibleUntil.containsKey(key), () -> {
            Byte strongest;
            synchronized (verdicts) { strongest = verdicts.get(key); }
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            org.telegram.ui.Components.AnimatedFileDrawable gif = null;
            try {
                long duration;
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    String value = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    duration = value == null ? 0 : Long.parseLong(value);
                } catch (Exception exception) {
                    // Telegram's native decoder also supports animated GIF/WebM files.
                    gif = new org.telegram.ui.Components.AnimatedFileDrawable(file, true, 0,
                            FileLoader.PRIORITY_LOW, null, null, message, 0, message.currentAccount, true, 320, 320, null);
                    duration = gif.getDurationMs();
                }
                if (duration > 0) {
                    for (int percent : new int[]{10, 50, 90}) {
                        Bitmap frame;
                        long millis = duration * percent / 100;
                        if (gif != null) frame = gif.getFrameForAnalysis(millis, MAX_FRAME_PIXELS);
                        else if (android.os.Build.VERSION.SDK_INT >= 27) frame = retriever.getScaledFrameAtTime(
                                millis * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST, SIZE, SIZE);
                        else {
                            String width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                            String height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                            if (width == null || height == null || (long) Integer.parseInt(width) * Integer.parseInt(height) > MAX_FRAME_PIXELS) break;
                            frame = retriever.getFrameAtTime(millis * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                        }
                        if (frame == null) continue;
                        Bitmap scaled = null, body = null;
                        try {
                            scaled = Bitmap.createScaledBitmap(frame, SIZE, SIZE, true);
                            body = padToSquare(frame);
                            Byte result = classify(scaled, body);
                            if (result != null) {
                                strongest = strongest == null ? result : (byte) Math.max(strongest, result);
                            }
                        } finally {
                            if (scaled != null && scaled != frame) scaled.recycle();
                            if (body != null) body.recycle();
                            frame.recycle();
                        }
                        if (strongest != null && hides(strongest)) break;
                    }
                }
            } catch (Throwable exception) {
                FileLog.e(exception);
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
                if (gif != null) gif.recycle();
            }
            if (strongest != null) synchronized (verdicts) { verdicts.put(key, strongest); }
            AndroidUtilities.runOnUIThread(() -> {
                sampling.remove(key);
                sampled.add(key); // unsupported local codecs retain the preview verdict, without decode loops
                AndroidUtilities.cancelRunOnUIThread(save);
                AndroidUtilities.runOnUIThread(save, 5000);
                redraw(message);
            });
        });
    }

    private static void requestProbe(MessageObject message, String key) {
        if (!loaded) { rememberEarly(message, key, true); return; }
        Long retry = retryAfter.get(key);
        if (retry != null && retry > android.os.SystemClock.elapsedRealtime()) return;
        if (probes.containsKey(key)) return;
        // Visible requests bypass the background download slots; inference still runs on one thread.
        if (visibleUntil.containsKey(key)) probe(message, null);
        else {
            ArrayList<MessageObject> list = new ArrayList<>();
            list.add(message);
            prefetch(list);
        }
    }

    private static void redraw(MessageObject message) {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<MessageObject> targets = new ArrayList<>();
            targets.add(message);
            // other live instances only from the same account and chat: never push objects of another
            // screen/dialog that merely share the media into this chat
            ArrayList<java.lang.ref.WeakReference<MessageObject>> instances = watching.get(key(message));
            final long dialogId = message.getDialogId();
            if (instances != null) for (java.lang.ref.WeakReference<MessageObject> reference : instances) {
                MessageObject target = reference.get();
                if (target != null && target != message && target.currentAccount == message.currentAccount
                        && target.getDialogId() == dialogId && !targets.contains(target)) {
                    targets.add(target);
                }
            }
            java.util.HashMap<String, ArrayList<MessageObject>> groups = new java.util.HashMap<>();
            for (MessageObject target : targets) {
                String group = target.currentAccount + "_" + target.getDialogId();
                ArrayList<MessageObject> list = groups.get(group);
                if (list == null) groups.put(group, list = new ArrayList<>());
                list.add(target);
            }
            for (ArrayList<MessageObject> list : groups.values()) {
                MessageObject first = list.get(0);
                NotificationCenter.getInstance(first.currentAccount).postNotificationName(
                        NotificationCenter.replaceMessagesObjects, first.getDialogId(), list);
            }
            String key = key(message);
            if (!isBlocked(message)) {
                Runnable open = readyActions.remove(key);
                if (open != null) open.run();
            } else if (isUnsafe(message) || isCheckUnavailable(message)) readyActions.remove(key);
        });
    }

    // ---------------------------------------------------------------- judging ahead

    /*
     * Pictures are judged before anyone looks at them: as messages arrive, as a chat's history loads and, when
     * the app opens, the last message of every chat. A chat then opens with its verdicts ready instead of
     * showing "checking" covers. A few small previews load at a time.
     */
    private static final int PREFETCH_PARALLEL = 3;
    private static final int MAX_PREFETCH = 64;
    private static final java.util.ArrayDeque<MessageObject> prefetchQueue = new java.util.ArrayDeque<>();
    private static final java.util.HashSet<String> prefetchQueued = new java.util.HashSet<>();
    private static int prefetchActive;
    private static boolean started;
    private static final java.util.LinkedHashMap<String, MessageObject> motionWatching = new java.util.LinkedHashMap<>();

    private static final NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> {
        if (id == NotificationCenter.fileLoaded) {
            for (MessageObject message : new ArrayList<>(motionWatching.values())) {
                if (message.currentAccount == account && args.length > 0
                        && FileLoader.getAttachFileName(message.getDocument()).equals(args[0])) sampleMotion(message);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (args.length > 2 && Boolean.TRUE.equals(args[2])) return; // scheduled
            if (args.length > 1 && args[1] instanceof ArrayList) prefetch((ArrayList<MessageObject>) args[1]);
        } else if (id == NotificationCenter.messagesDidLoad) {
            if (args.length > 2 && args[2] instanceof ArrayList) prefetch((ArrayList<MessageObject>) args[2]);
        } else if (id == NotificationCenter.dialogsNeedReload) {
            prefetchDialogs(account);
        }
    };

    /** Called when the app opens: listens on every account and judges the chats' last messages. */
    public static void start() {
        if (!started) {
            started = true;
            // both models load once, off the UI thread and before the first picture: no wait on the first verdict
            if (isEnabled()) {
                ensureLoaded(); // the saved verdicts first, then the models
                worker.execute(() -> {
                    synchronized (UMessageNsfwFilter.class) {
                        loadModel();
                        loadModel2();
                        loadModel3();
                    }
                });
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter center = NotificationCenter.getInstance(a);
                center.addObserver(observer, NotificationCenter.fileLoaded);
                center.addObserver(observer, NotificationCenter.didReceiveNewMessages);
                center.addObserver(observer, NotificationCenter.messagesDidLoad);
                center.addObserver(observer, NotificationCenter.dialogsNeedReload);
            }
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) prefetchDialogs(a);
        }
    }

    private static void prefetchDialogs(int account) {
        if (!isEnabled()) return;
        androidx.collection.LongSparseArray<ArrayList<MessageObject>> last = MessagesController.getInstance(account).dialogMessage;
        ArrayList<MessageObject> list = new ArrayList<>();
        for (int i = 0; i < last.size(); i++) {
            ArrayList<MessageObject> group = last.valueAt(i);
            if (group != null) list.addAll(group);
        }
        prefetch(list);
    }

    /** Queues the unjudged pictures of these messages; UI thread. */
    public static void prefetch(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty() || !isEnabled()) return;
        ensureLoaded();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject m = messages.get(i);
            if (!(applies(m) || isSticker(m))) continue;
            String key = key(m);
            if (key != null && !loaded) {
                // most are judged already in the saved verdicts: only the rest are probed once they are read
                rememberEarly(m, key, false);
                continue;
            }
            if (key == null || prefetchQueue.size() >= MAX_PREFETCH) continue;
            synchronized (verdicts) {
                if (verdicts.containsKey(key)) continue;
            }
            Long retry = retryAfter.get(key);
            if (retry != null && retry > android.os.SystemClock.elapsedRealtime()) continue;
            if (!prefetchQueued.add(key)) continue;
            prefetchQueue.add(m);
        }
        pumpPrefetch();
    }

    private static void pumpPrefetch() {
        while (prefetchActive < PREFETCH_PARALLEL && !prefetchQueue.isEmpty()) {
            MessageObject m = prefetchQueue.poll();
            String key = key(m);
            synchronized (verdicts) {
                if (key == null || verdicts.containsKey(key)) {
                    prefetchQueued.remove(key);
                    continue;
                }
            }
            prefetchActive++;
            final boolean[] finished = {false};
            final Runnable finish = () -> {
                if (finished[0]) return;
                finished[0] = true;
                prefetchActive--;
                prefetchQueued.remove(key);
                pumpPrefetch();
            };
            if (!probe(m, finish)) finish.run();
        }
    }

    /**
     * Called by the chat cell when the picture's bitmap is decoded. Judges it in the background and, if the
     * verdict changes what is shown, has the chat redraw the message.
     */
    public static void onImage(MessageObject m, Bitmap bitmap) {
        onImage(m, bitmap, null);
    }

    /** Same, with {@code onVerdict} run on the UI thread once the picture is judged (the media viewer waits on it). */
    public static void onImage(MessageObject m, Bitmap bitmap, Runnable onVerdict) {
        // onVerdict always runs, judged or not: the media viewer must not wait on "checking" for good
        if (!isEnabled() || !(applies(m) || isSticker(m)) || bitmap == null || bitmap.isRecycled()
                || Math.min(bitmap.getWidth(), bitmap.getHeight()) < 90) { // the tiny stripped preview says too little
            answer(onVerdict);
            return;
        }
        String key = key(m);
        if (key == null) {
            answer(onVerdict);
            return;
        }
        synchronized (verdicts) {
            if (verdicts.containsKey(key)) {
                answer(onVerdict);
                return;
            }
            if (!running.contains(key) && running.size() >= MAX_CLASSIFICATIONS) {
                answer(onVerdict);
                return; // admission before allocating ~600KB of owned classifier bitmaps
            }
            final boolean judging = !running.add(key);
            if (onVerdict != null) {
                // judged already elsewhere: this one hears the same verdict
                ArrayList<Runnable> list = waiting.get(key);
                if (list == null) waiting.put(key, list = new ArrayList<>());
                list.add(onVerdict);
            }
            if (judging) return;
        }
        Bitmap scaled, body;
        try {
            scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true);
            if (scaled == bitmap) scaled = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            body = padToSquare(bitmap);
        } catch (Throwable e) {
            finishJudging(key);
            return;
        }
        final Bitmap classifierBitmap = scaled;
        worker.execute(key, visibleUntil.containsKey(key), () -> {
            try {
                Byte verdict;
                synchronized (verdicts) { verdict = verdicts.get(key); }
                if (verdict == null) {
                    verdict = classify(classifierBitmap, body);
                    if (verdict != null) {
                        synchronized (verdicts) { verdicts.put(key, verdict); }
                        AndroidUtilities.cancelRunOnUIThread(save);
                        AndroidUtilities.runOnUIThread(save, 5000);
                    }
                }
            } catch (Throwable exception) {
                FileLog.e(exception);
            } finally {
                if (classifierBitmap != null) classifierBitmap.recycle();
                body.recycle();
                finishJudging(key);
            }
            // Both safe and unsafe results change the placeholder; always notify every view.
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (verdicts) {
                    if (!verdicts.containsKey(key)) { markUnavailable(m); return; }
                }
                retryAfter.remove(key);
                sampleMotion(m);
                redraw(m);
            });
        });
    }

    /** Runs on the UI thread later, never inline (callers may be mid-layout). */
    private static void answer(Runnable r) {
        if (r != null) AndroidUtilities.runOnUIThread(r);
    }

    /** A judgement ended (with or without a verdict): everyone waiting on it is told. */
    private static void finishJudging(String key) {
        ArrayList<Runnable> list;
        synchronized (verdicts) {
            running.remove(key);
            list = waiting.remove(key);
        }
        if (list != null) {
            for (int i = 0; i < list.size(); i++) answer(list.get(i));
        }
    }

    private static final java.util.HashMap<String, ImageReceiver> probes = new java.util.HashMap<>();
    /** Everyone waiting on a probe of that key; UI thread. */
    private static final java.util.HashMap<String, ArrayList<Runnable>> probeDone = new java.util.HashMap<>();
    private static final long PROBE_TIMEOUT = 8_000;

    /**
     * The media viewer can reach pictures the chat never drew (swiping past an album into older media), so
     * it asks for a check itself: the 320 px preview is loaded off-screen, judged, then {@code done} runs.
     * {@code done} always runs, also when nothing could be judged; UI thread.
     */
    public static boolean probe(MessageObject m, Runnable done) {
        if (!isEnabled() || !(applies(m) || isSticker(m))) {
            answer(done);
            return false;
        }
        String key = key(m);
        if (key == null) {
            answer(done);
            return false;
        }
        synchronized (verdicts) {
            if (verdicts.containsKey(key)) { answer(done); return false; }
        }
        Long retry = retryAfter.get(key);
        if (retry != null && retry > android.os.SystemClock.elapsedRealtime()) { answer(done); return false; }
        if (probes.containsKey(key)) {
            // being probed already: wait for that one
            if (done != null) {
                ArrayList<Runnable> list = probeDone.get(key);
                if (list == null) probeDone.put(key, list = new ArrayList<>());
                list.add(done);
            }
            return true;
        }
        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(m.photoThumbs, 320);
        if (size == null) {
            markUnavailable(m);
            answer(done);
            return false;
        }
        ImageReceiver receiver = new ImageReceiver();
        probes.put(key, receiver);
        ArrayList<Runnable> list = new ArrayList<>();
        if (done != null) list.add(done);
        probeDone.put(key, list);
        receiver.setDelegate(new ImageReceiver.ImageReceiverDelegate() {
            @Override
            public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                if (!set || thumb) return;
                onImage(m, imageReceiver.getBitmap(), () -> {
                    if (needsCheck(m)) markUnavailable(m);
                    finishProbe(key, receiver);
                });
            }
        });
        receiver.onAttachedToWindow();
        receiver.setImage(ImageLocation.getForObject(size, m.photoThumbsObject), "320_320", null, null, m,
                m.shouldEncryptPhotoOrVideo() ? ImageLoader.CACHE_TYPE_ENCRYPTED : 0);
        // a preview that never loads (offline) must not keep the receiver or its waiters
        AndroidUtilities.runOnUIThread(() -> {
            if (probes.get(key) != receiver) return;
            if (needsCheck(m)) markUnavailable(m);
            finishProbe(key, receiver);
        }, PROBE_TIMEOUT);
        return true;
    }

    private static void finishProbe(String key, ImageReceiver receiver) {
        if (probes.get(key) != receiver) return; // finished already
        probes.remove(key);
        receiver.onDetachedFromWindow();
        ArrayList<Runnable> list = probeDone.remove(key);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) list.get(i).run();
        }
    }

    private static void cancelProbe(String key) {
        ImageReceiver receiver = probes.get(key);
        if (receiver != null) {
            finishProbe(key, receiver);
        }
    }

    /** Judged explicit: the cell labels it and refuses to open it. */
    public static boolean isUnsafe(MessageObject message) {
        return state(message) == UMessageNsfwDecision.State.HIDDEN;
    }

    public static boolean isBlocked(MessageObject message) {
        return state(message) != UMessageNsfwDecision.State.CLEAR;
    }

    /** What the label on a hidden picture says is under it. */
    public static int describe(MessageObject m) {
        if (isSticker(m)) return R.string.UMessageNsfwHiddenSticker;
        if (m.type == MessageObject.TYPE_GIF) return R.string.UMessageNsfwHiddenGif;
        if (m.type == MessageObject.TYPE_VIDEO || m.type == MessageObject.TYPE_ROUND_VIDEO) return R.string.UMessageNsfwHiddenVideo;
        return R.string.UMessageNsfwHiddenPhoto;
    }

    // ---------------------------------------------------------------- outgoing media

    /** Pictures covered by the last send from the attach sheet; the chat shows one notice and resets it. */
    public static int hiddenOnSend;

    /** Its own thread: a send waits at most on the picture being judged now, not on the whole prefetch queue. */
    private static final ExecutorService sendWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "nsfw-send"));

    /**
     * Checks pictures about to be sent off the UI thread (the models can take a while, and wait on loading);
     * {@code done} gets one flag per path, in order, on the UI thread.
     */
    public static void looksExplicit(ArrayList<String> paths, Utilities.Callback<boolean[]> done) {
        final ArrayList<String> list = new ArrayList<>(paths);
        sendWorker.execute(() -> {
            final boolean[] result = new boolean[list.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = looksExplicit(list.get(i));
            }
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    /** Check of one picture (decoded small); background thread. False when it can't tell. */
    private static boolean looksExplicit(String path) {
        if (!isEnabled() || path == null || !new File(path).exists()) return false;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= SIZE) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return false;
            Bitmap scaled = Bitmap.createScaledBitmap(b, SIZE, SIZE, true);
            Bitmap body = padToSquare(b);
            if (scaled != b) b.recycle();
            Byte verdict = classify(scaled, body);
            scaled.recycle();
            body.recycle();
            return verdict != null && hides(verdict);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---------------------------------------------------------------- model

    /** SAFE..EXPLICIT, null = the model is unavailable. */
    private static synchronized Byte classify(Bitmap bitmap, Bitmap body) {
        if (!loadModel()) return null;
        int[] pixels = new int[SIZE * SIZE];
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        input.rewind();
        for (int p : pixels) {
            input.putFloat(((p >> 16) & 0xff) / 255f);
            input.putFloat(((p >> 8) & 0xff) / 255f);
            input.putFloat((p & 0xff) / 255f);
        }
        input.rewind();
        try {
            interpreter.run(input, output);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        float[] s = output[0];
        // nothing a person could show: most pictures (cars, food, screenshots) stop here after one fast pass
        if (s[PORN] + s[HENTAI] + s[SEXY] < 0.1f) return SAFE;
        float explicit = s[PORN] + s[HENTAI];
        // porn / hentai, which the second model must confirm: on its own the small model read skin and muscle
        // (gym, boxing, massage, car seats) as porn
        byte porny = s[PORN] >= 0.6f || s[HENTAI] >= 0.6f || explicit >= 0.7f && explicit > s[SEXY] ? EXPLICIT
                : s[PORN] >= 0.45f || s[HENTAI] >= 0.5f ? REVEALING : explicit >= 0.3f ? MILD : SAFE;
        if (porny != SAFE) {
            final float nsfw = classifyOpenNsfw(bitmap);
            if (nsfw >= 0) {
                porny = (byte) Math.min(porny, nsfw >= 0.85f ? EXPLICIT : nsfw >= 0.6f ? REVEALING : nsfw >= 0.35f ? MILD : SAFE);
            }
        }
        final float[] p = classifyBody(body);
        if (p == null) {
            // no body model: the old rule, the small model's "sexy" class alone
            return (byte) Math.max(porny, s[SEXY] >= 0.9f ? REVEALING : s[SEXY] >= 0.45f ? MILD : SAFE);
        }
        final byte bodyVerdict;
        if (p[FEMALE_BREAST_EXPOSED] >= 0.5f || p[FEMALE_GENITALIA_EXPOSED] >= 0.45f || p[MALE_GENITALIA_EXPOSED] >= 0.45f
                || p[ANUS_EXPOSED] >= 0.45f || p[BUTTOCKS_EXPOSED] >= 0.5f) {
            bodyVerdict = EXPLICIT;
        } else {
            // an open belly, a bikini or underwear; a man's bare chest (sport) is left alone
            final boolean maleOnly = (p[MALE_BREAST_EXPOSED] >= 0.4f || p[FACE_MALE] >= 0.5f) && p[FACE_FEMALE] < 0.4f && p[FEMALE_BREAST_COVERED] < 0.4f;
            if (!maleOnly && (p[BELLY_EXPOSED] >= 0.5f || p[FEMALE_GENITALIA_COVERED] >= 0.55f && (p[BELLY_EXPOSED] >= 0.4f || p[BUTTOCKS_COVERED] >= 0.5f))) {
                bodyVerdict = REVEALING;
            } else if (p[BELLY_EXPOSED] >= 0.35f || p[FEMALE_BREAST_COVERED] >= 0.7f || p[BUTTOCKS_COVERED] >= 0.6f) {
                bodyVerdict = MILD;
            } else {
                bodyVerdict = SAFE;
            }
        }
        return (byte) Math.max(porny, bodyVerdict);
    }

    /** The picture on a black square (right/bottom padding), as the body model was trained; 320x320. */
    private static Bitmap padToSquare(Bitmap source) {
        final int w = source.getWidth(), h = source.getHeight(), side = Math.max(w, h);
        final float scale = BODY_SIZE / (float) side;
        Bitmap out = Bitmap.createBitmap(BODY_SIZE, BODY_SIZE, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(0xff000000);
        android.graphics.Rect dst = new android.graphics.Rect(0, 0, Math.round(w * scale), Math.round(h * scale));
        canvas.drawBitmap(source, null, dst, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Best score per body class (over the anchors where that class wins), null when the model is unavailable. */
    private static float[] classifyBody(Bitmap body) {
        if (body == null || !loadModel3()) return null;
        try {
            int[] pixels = new int[BODY_SIZE * BODY_SIZE];
            body.getPixels(pixels, 0, BODY_SIZE, 0, 0, BODY_SIZE, BODY_SIZE);
            input3.rewind();
            for (int px : pixels) {
                input3.putFloat(((px >> 16) & 0xff) / 255f);
                input3.putFloat(((px >> 8) & 0xff) / 255f);
                input3.putFloat((px & 0xff) / 255f);
            }
            input3.rewind();
            interpreter3.run(input3, output3);
            final float[][] scores = output3[0];
            final float[] best = new float[BODY_CLASSES];
            for (int a = 0; a < BODY_ANCHORS; a++) {
                int top = 0;
                for (int c = 1; c < BODY_CLASSES; c++) {
                    if (scores[c][a] > scores[top][a]) top = c;
                }
                if (scores[top][a] > best[top]) best[top] = scores[top][a];
            }
            return best;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean loadModel3() {
        if (interpreter3 != null) return true;
        if (model3Missing || retryLater(2)) return false;
        try {
            input3 = ByteBuffer.allocateDirect(BODY_SIZE * BODY_SIZE * 3 * 4).order(ByteOrder.nativeOrder());
            interpreter3 = new Interpreter(readModel(MODEL3), modelOptions());
            loadFailures[2] = 0;
            return true;
        } catch (java.io.FileNotFoundException e) {
            FileLog.e(e);
            model3Missing = true;
            return false;
        } catch (Throwable e) {
            FileLog.e(e);
            loadFailed(2);
            return false;
        }
    }

    /*
     * Only a model missing from the app turns it off for good. Any other failure (out of memory, a busy phone)
     * leaves pictures covered as unjudged and is tried again later, 10 s, 20 s ... up to 5 min apart: the filter
     * must not switch itself off for the session, least of all on a phone under parental control.
     */
    private static final long[] retryAt = new long[3];
    private static final int[] loadFailures = new int[3];

    private static boolean retryLater(int model) {
        return android.os.SystemClock.elapsedRealtime() < retryAt[model];
    }

    private static void loadFailed(int model) {
        final int failures = Math.min(++loadFailures[model], 6);
        retryAt[model] = android.os.SystemClock.elapsedRealtime() + Math.min(10_000L << (failures - 1), 300_000L);
    }

    private static ByteBuffer readModel(String path) throws java.io.IOException {
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8 << 20);
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            byte[] bytes = out.toByteArray();
            ByteBuffer model = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            model.put(bytes);
            model.rewind();
            return model;
        }
    }

    private static Interpreter.Options modelOptions() {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(2);
        options.setUseXNNPACK(true);
        return options;
    }

    /** Open NSFW probability 0..1, -1 when the model is unavailable. Fed the 224 picture as a 256 resize, center crop. */
    private static float classifyOpenNsfw(Bitmap bitmap) {
        if (!loadModel2()) return -1;
        Bitmap big = null;
        try {
            big = Bitmap.createScaledBitmap(bitmap, 256, 256, true);
            int[] pixels = new int[SIZE * SIZE];
            big.getPixels(pixels, 0, SIZE, 16, 16, SIZE, SIZE);
            input2.rewind();
            for (int p : pixels) {
                input2.putFloat((p >> 16) & 0xff);
                input2.putFloat((p >> 8) & 0xff);
                input2.putFloat(p & 0xff);
            }
            input2.rewind();
            interpreter2.run(input2, output2);
            return output2[0][1];
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        } finally {
            if (big != null && big != bitmap) big.recycle();
        }
    }

    private static boolean loadModel2() {
        if (interpreter2 != null) return true;
        if (model2Missing || retryLater(1)) return false;
        try {
            input2 = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder());
            interpreter2 = new Interpreter(readModel(MODEL2), modelOptions());
            loadFailures[1] = 0;
            return true;
        } catch (java.io.FileNotFoundException e) {
            FileLog.e(e);
            model2Missing = true;
            return false;
        } catch (Throwable e) {
            FileLog.e(e);
            loadFailed(1);
            return false;
        }
    }

    private static boolean loadModel() {
        if (interpreter != null) return true;
        if (modelMissing || retryLater(0)) return false;
        try {
            input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder());
            interpreter = new Interpreter(readModel(MODEL), modelOptions());
            loadFailures[0] = 0;
            return true;
        } catch (java.io.FileNotFoundException e) {
            FileLog.e(e);
            modelMissing = true; // no model, no filter: never leave every picture blurred
            return false;
        } catch (Throwable e) {
            // out of memory and the like: pictures stay covered as unjudged, the model is tried again later
            FileLog.e(e);
            loadFailed(0);
            return false;
        }
    }

    // ---------------------------------------------------------------- verdict store

    /*
     * The verdicts live in a file of their own, read and written on the filter's thread: up to 20000 entries
     * were parsed on the first draw and rebuilt into preferences every few seconds on the UI thread. Until the
     * file is read every picture counts as unjudged (covered); what was drawn meanwhile is redrawn once it is.
     */
    private static final String STORE = "umessage_nsfw_verdicts_v2";
    private static boolean storeMigrated;
    /** Pictures asked about before the saved verdicts were in; UI thread. */
    private static final LinkedHashMap<String, MessageObject> askedEarly = new LinkedHashMap<String, MessageObject>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MessageObject> eldest) {
            return size() > 1000;
        }
    };

    private static File storeFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), STORE);
    }

    private static void ensureLoaded() {
        synchronized (verdicts) {
            if (loadStarted) return;
            loadStarted = true;
        }
        worker.execute(UMessageNsfwFilter::loadNow);
    }

    /** Keys of those that were drawn (not only prefetched): only these need a redraw. */
    private static final java.util.HashSet<String> drawnEarly = new java.util.HashSet<>();

    private static void rememberEarly(MessageObject m, String key, boolean drawn) {
        synchronized (askedEarly) {
            askedEarly.put(key, m);
            if (drawn) drawnEarly.add(key);
        }
    }

    /** Filter thread. */
    private static void loadNow() {
        final LinkedHashMap<String, Byte> stored = new LinkedHashMap<>();
        try {
            prefs().edit().remove("revealed").apply(); // was kept for good before; now only while the chat is open
            File file = storeFile();
            String saved;
            if (file.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.max(16, file.length()));
                    byte[] chunk = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
                    saved = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } else {
                saved = ""; // older builds kept them in preferences
            }
            if (!saved.isEmpty()) {
                for (String item : saved.split(",")) {
                    if (item.length() < 3) continue;
                    int v = item.charAt(0) - '0';
                    if (v < SAFE || v > EXPLICIT) continue;
                    stored.put(item.substring(1), (byte) v);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        synchronized (verdicts) {
            // what was judged this session meanwhile is newer than the file
            final LinkedHashMap<String, Byte> session = new LinkedHashMap<>(verdicts);
            verdicts.clear();
            verdicts.putAll(stored);
            verdicts.putAll(session);
        }
        loaded = true;
        AndroidUtilities.runOnUIThread(UMessageNsfwFilter::onLoaded);
    }

    /** UI thread: pictures drawn as unjudged before the file was read are redrawn, or judged if still unknown. */
    private static void onLoaded() {
        final ArrayList<MessageObject> early;
        final java.util.HashSet<String> drawn;
        synchronized (askedEarly) {
            early = new ArrayList<>(askedEarly.values());
            drawn = new java.util.HashSet<>(drawnEarly);
            askedEarly.clear();
            drawnEarly.clear();
        }
        if (early.isEmpty()) return;
        final java.util.HashMap<String, ArrayList<MessageObject>> redraw = new java.util.HashMap<>();
        for (int i = 0; i < early.size(); i++) {
            MessageObject m = early.get(i);
            String key = key(m);
            if (key == null) continue;
            boolean known;
            synchronized (verdicts) {
                known = verdicts.containsKey(key);
            }
            if (!known) {
                if (applies(m) || isSticker(m)) requestProbe(m, key);
                continue;
            }
            if (!drawn.contains(key)) continue;
            String group = m.currentAccount + "_" + m.getDialogId();
            ArrayList<MessageObject> list = redraw.get(group);
            if (list == null) redraw.put(group, list = new ArrayList<>());
            list.add(m);
        }
        for (ArrayList<MessageObject> list : redraw.values()) {
            MessageObject first = list.get(0);
            NotificationCenter.getInstance(first.currentAccount).postNotificationName(NotificationCenter.replaceMessagesObjects, first.getDialogId(), list);
        }
    }

    /** UI thread, debounced: the file is written on the filter's thread, after the saved verdicts were read. */
    private static void saveNow() {
        ensureLoaded();
        worker.execute(UMessageNsfwFilter::writeNow);
    }

    /** Filter thread. */
    private static void writeNow() {
        final String[] keys;
        final byte[] values;
        synchronized (verdicts) {
            keys = new String[verdicts.size()];
            values = new byte[keys.length];
            int i = 0;
            for (Map.Entry<String, Byte> e : verdicts.entrySet()) {
                keys[i] = e.getKey();
                values[i] = e.getValue();
                i++;
            }
        }
        StringBuilder sb = new StringBuilder(keys.length * 22);
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(',');
            sb.append((char) ('0' + values[i])).append(keys[i]);
        }
        try {
            File file = storeFile();
            File tmp = new File(file.getPath() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
            if (!storeMigrated) {
                storeMigrated = true;
                // older stores were judged by the first model alone (gym and car photos hidden), so they are judged again
                prefs().edit().remove("verdicts5").remove("verdicts4").remove("verdicts3").remove("verdicts2").remove("verdicts").apply();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
