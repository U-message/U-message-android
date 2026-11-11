package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageShazam;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.VideoPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** U Music — listens through the microphone, recognizes the song and sends it to a chat. */
public class UMessageShazamActivity extends BaseFragment {

    private static final int REQUEST_MIC = 41;
    /** The cover's size (dp) in the corner while the clip plays. */
    private static final int COVER_SMALL = 84;
    private static final int LISTEN_SAMPLES = UMessageShazam.SAMPLE_RATE * 15;
    private static final int FIRST_ATTEMPT = UMessageShazam.SAMPLE_RATE * 4;
    private static final int ATTEMPT_STEP = UMessageShazam.SAMPLE_RATE * 3;

    private FloodLayout root;
    /** The song's clip highlight playing behind the result, cut to the screen around its centre. */
    private TextureView videoView;
    private View videoShade;
    private VideoPlayer videoPlayer;
    private boolean videoReady, videoShown;
    private LogoParticlesView logoView;
    private TextView statusTitle, statusSub;
    private LinearLayout resultView;
    private TextView titleView, artistView, sendButton, againButton;

    private final ExecutorService recognizer = Executors.newSingleThreadExecutor();
    /** Bumped to cancel the running listen; threads drop their results once it no longer matches. */
    private volatile int session;
    private boolean listening;
    private UMessageShazam.Track track;
    private Prepared prepared;
    private boolean autoStart;

    /** From the chats header quick button: starts listening right away. */
    public static UMessageShazamActivity quick() {
        final UMessageShazamActivity fragment = new UMessageShazamActivity();
        fragment.autoStart = true;
        return fragment;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && autoStart) {
            autoStart = false;
            startListening();
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageShazam));
        // the screen draws its own background (and the cover's color) under the header
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setItemsBackgroundColor(0x22ffffff, false);
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        root = new FloodLayout(context);
        final FrameLayout content = new FrameLayout(context);
        final FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT);
        contentParams.topMargin = ActionBar.getCurrentActionBarHeight() + (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0);
        videoView = new TextureView(context);
        videoView.setAlpha(0f);
        root.addView(videoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // dark at the bottom so the name and buttons read on any clip
        videoShade = new View(context);
        videoShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8c000000, 0x40000000, 0xb3000000, 0xf2000000}));
        videoShade.setAlpha(0f);
        root.addView(videoShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(content, contentParams);
        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        logoView = new LogoParticlesView(context);
        logoView.onCoverShown = this::revealVideo;
        logoView.setContentDescription(LocaleController.getString(R.string.UMessageShazamFind));
        logoView.setOnClickListener(v -> {
            if (logoView.isFound()) {
                return;
            }
            if (listening) {
                stopListening();
                setStatus(R.string.UMessageShazamFind, R.string.UMessageShazamTapLogo);
            } else {
                startListening();
            }
        });
        content.addView(logoView, LayoutHelper.createFrame(240, 240, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 114, 0, 0));

        final LinearLayout statusLayout = new LinearLayout(context);
        statusLayout.setOrientation(LinearLayout.VERTICAL);
        statusTitle = new TextView(context);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        statusTitle.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_UNBOUNDED_BOLD));
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setGravity(Gravity.CENTER);
        statusLayout.addView(statusTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        statusSub = new TextView(context);
        statusSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusSub.setTextColor(0xffa1a1aa);
        statusSub.setGravity(Gravity.CENTER);
        statusLayout.addView(statusSub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        content.addView(statusLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 414, 24, 0));
        setStatus(R.string.UMessageShazamFind, R.string.UMessageShazamTapLogo);

        resultView = new LinearLayout(context);
        resultView.setOrientation(LinearLayout.VERTICAL);
        resultView.setVisibility(View.GONE);
        content.addView(resultView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 28, 384, 28, 28));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        titleView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_UNBOUNDED_BOLD));
        titleView.setTextColor(Color.WHITE);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        resultView.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        artistView.setTextColor(0xd1ffffff);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        resultView.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        resultView.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        sendButton = createButton(context, LocaleController.getString(R.string.UMessageShazamSend), true);
        sendButton.setOnClickListener(v -> pickChatAndSend());
        resultView.addView(sendButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        againButton = createButton(context, LocaleController.getString(R.string.UMessageShazamAgain), false);
        againButton.setOnClickListener(v -> startListening());
        resultView.addView(againButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 10, 0, 0));

        return fragmentView = root;
    }

    private TextView createButton(Context context, String text, boolean primary) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, primary ? 16 : 15);
        view.setTypeface(AndroidUtilities.bold());
        if (primary) {
            view.setTextColor(FloodLayout.DEFAULT_COLORS[3]);
            view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), 0xffffffff, 0xffe9e9ee));
        } else {
            view.setTextColor(Color.WHITE);
            view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), 0x26ffffff, 0x40ffffff));
        }
        ScaleStateListAnimator.apply(view, 0.04f, 1.2f);
        return view;
    }

    /**
     * Sends the song to the chats picked, right away: it has been made ready since it was recognized.
     * With one chat that chat opens; a song not ready yet shows as a plain audio message there and
     * turns into the real one in place.
     */
    private void pickChatAndSend() {
        final Prepared song = prepared;
        if (song == null) {
            return;
        }
        final android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            final java.util.ArrayList<Long> dialogs = new java.util.ArrayList<>();
            for (org.telegram.messenger.MessagesStorage.TopicKey key : dids) {
                dialogs.add(key.dialogId);
            }
            final long dialogId = dialogs.size() == 1 ? dialogs.get(0) : 0;
            if (dialogId != 0 && !org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) {
                final android.os.Bundle chatArgs = new android.os.Bundle();
                if (org.telegram.messenger.DialogObject.isUserDialog(dialogId)) {
                    chatArgs.putLong("user_id", dialogId);
                } else {
                    chatArgs.putLong("chat_id", -dialogId);
                }
                removeSelfFromStack();
                fragment.presentFragment(new ChatActivity(chatArgs), true);
                // after the chat is open, so it receives the message
                send(currentAccount, song, dialogs, notify);
            } else {
                send(currentAccount, song, dialogs, notify);
                fragment.finishFragment();
                org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.UMessageShazamSent)).show();
            }
            return true;
        });
        presentFragment(picker);
    }

    private static final String CAPTION = "@u_message";

    /** The song made ready for sending from the moment it is recognized. */
    private static final class Prepared {
        final UMessageShazam.Track track;
        /** The full song already in Telegram (from a music bot or channel), resent as our own file. */
        org.telegram.tgnet.TLRPC.TL_document document;
        Object parent;
        /** Otherwise a file to upload: the full song from a bot's link, or the 30 s hook. */
        java.io.File file;
        boolean full;
        boolean done;
        private final java.util.ArrayList<Runnable> waiting = new java.util.ArrayList<>();

        Prepared(UMessageShazam.Track track) {
            this.track = track;
        }

        void whenDone(Runnable action) {
            if (done) {
                action.run();
            } else {
                waiting.add(action);
            }
        }

        void finish() {
            done = true;
            for (Runnable action : waiting) {
                action.run();
            }
            waiting.clear();
        }
    }

    private static Prepared prepare(int account, UMessageShazam.Track result) {
        final Prepared prepared = new Prepared(result);
        org.telegram.messenger.UMessageMusicBots.find(account, result.title, result.artist, song -> {
            if (song != null && song.document != null) {
                prepared.document = song.document;
                prepared.parent = song.parent;
                prepared.full = true;
                prepared.finish();
            } else {
                downloadFile(prepared, song != null ? song.url : null);
            }
        });
        return prepared;
    }

    /** Downloads the song from the bot's link, or the hook when there is none. */
    private static void downloadFile(Prepared prepared, String fullUrl) {
        final UMessageShazam.Track result = prepared.track;
        org.telegram.messenger.UMessageNet.run(() -> {
            java.io.File file = null;
            boolean full = false;
            try {
                final org.telegram.messenger.UMessageMusic.Track song = new org.telegram.messenger.UMessageMusic.Track();
                song.title = result.title;
                song.artist = result.artist != null ? result.artist : "";
                song.id = (song.artist + "|" + song.title).hashCode() & 0x7fffffffL;
                java.io.File source = null;
                if (fullUrl != null) {
                    try {
                        song.preview = fullUrl;
                        source = org.telegram.messenger.UMessageMusic.download(song);
                        full = true;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                if (source == null) {
                    song.preview = result.previewUrl;
                    source = org.telegram.messenger.UMessageMusic.download(song);
                }
                // Telegram shows a sent audio file by its name when the file has no tags of its own
                final String name = (TextUtils.isEmpty(song.artist) ? song.title : song.artist + " - " + song.title)
                        .replaceAll("[\\\\/:*?\"<>|]", "_");
                final java.io.File dir = new java.io.File(org.telegram.messenger.ApplicationLoader.applicationContext.getCacheDir(), "umessage_shazam");
                dir.mkdirs();
                file = new java.io.File(dir, name + (source.getName().endsWith(".m4a") ? ".m4a" : ".mp3"));
                org.telegram.messenger.AndroidUtilities.copyFile(source, file);
            } catch (Exception e) {
                FileLog.e(e);
                file = null;
            }
            final java.io.File audio = file;
            final boolean isFull = full;
            AndroidUtilities.runOnUIThread(() -> {
                prepared.file = audio;
                prepared.full = isFull;
                prepared.finish();
            });
        });
    }

    private static void send(int account, Prepared prepared, java.util.ArrayList<Long> dialogs, boolean notify) {
        if (prepared.done) {
            sendPrepared(account, prepared, dialogs, null, notify);
            return;
        }
        final org.telegram.messenger.MessageObject[] placeholders = new org.telegram.messenger.MessageObject[dialogs.size()];
        for (int i = 0; i < dialogs.size(); i++) {
            placeholders[i] = showPlaceholder(account, dialogs.get(i), prepared.track);
        }
        prepared.whenDone(() -> sendPrepared(account, prepared, dialogs, placeholders, notify));
    }

    private static void sendPrepared(int account, Prepared prepared, java.util.ArrayList<Long> dialogs, org.telegram.messenger.MessageObject[] placeholders, boolean notify) {
        if (prepared.document == null && prepared.file == null) {
            if (placeholders != null) {
                for (int i = 0; i < dialogs.size(); i++) {
                    removePlaceholder(account, dialogs.get(i), placeholders[i].getId());
                }
            }
            org.telegram.ui.Components.BulletinFactory.global().createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageShazamError)).show();
            return;
        }
        final String caption = prepared.full ? CAPTION : "";
        final String mime = prepared.file != null && prepared.file.getName().endsWith(".m4a") ? "audio/mp4" : "audio/mpeg";
        for (int i = 0; i < dialogs.size(); i++) {
            final long dialogId = dialogs.get(i);
            if (placeholders == null) {
                if (prepared.document != null) {
                    org.telegram.messenger.SendMessagesHelper.getInstance(account).sendMessage(org.telegram.messenger.SendMessagesHelper.SendMessageParams.of(prepared.document, null, null, dialogId,
                            null, null, caption, null, null, null, notify, 0, 0, 0, prepared.parent, null, false));
                } else {
                    org.telegram.messenger.SendMessagesHelper.prepareSendingDocument(org.telegram.messenger.AccountInstance.getInstance(account), prepared.file.getAbsolutePath(), prepared.file.getAbsolutePath(), null,
                            caption, mime, dialogId, null, null, null, null, null, notify, 0, null, org.telegram.messenger.SendMessageChatArguments.EMPTY, false);
                }
                continue;
            }
            // the placeholder itself is sent (as a resend), so it stays where it is and just becomes the song
            final org.telegram.messenger.MessageObject placeholder = placeholders[i];
            final org.telegram.tgnet.TLRPC.Message message = placeholder.messageOwner;
            final org.telegram.tgnet.TLRPC.TL_document document = prepared.document != null ? prepared.document : fileDocument(prepared, mime);
            message.media.document = document;
            message.message = caption;
            message.attachPath = prepared.file != null ? prepared.file.getAbsolutePath() : null;
            final org.telegram.messenger.SendMessagesHelper.SendMessageParams params = org.telegram.messenger.SendMessagesHelper.SendMessageParams.of(document, null, message.attachPath, dialogId,
                    null, null, caption, null, null, null, notify, 0, 0, 0, prepared.parent, null, false);
            params.retryMessageObject = placeholder;
            org.telegram.messenger.SendMessagesHelper.getInstance(account).sendMessage(params);
            final java.util.ArrayList<org.telegram.messenger.MessageObject> replaced = new java.util.ArrayList<>();
            replaced.add(new org.telegram.messenger.MessageObject(account, message, false, true));
            org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(org.telegram.messenger.NotificationCenter.replaceMessagesObjects, dialogId, replaced);
        }
    }

    /** A document to upload from the downloaded file, as Telegram makes one for a picked audio file. */
    private static org.telegram.tgnet.TLRPC.TL_document fileDocument(Prepared prepared, String mime) {
        final org.telegram.tgnet.TLRPC.TL_document document = new org.telegram.tgnet.TLRPC.TL_document();
        document.file_reference = new byte[0];
        document.mime_type = mime;
        document.size = prepared.file.length();
        document.dc_id = 0;
        final org.telegram.tgnet.TLRPC.TL_documentAttributeFilename filename = new org.telegram.tgnet.TLRPC.TL_documentAttributeFilename();
        filename.file_name = prepared.file.getName();
        document.attributes.add(filename);
        final org.telegram.tgnet.TLRPC.TL_documentAttributeAudio audio = new org.telegram.tgnet.TLRPC.TL_documentAttributeAudio();
        audio.title = prepared.track.title;
        audio.performer = prepared.track.artist;
        audio.duration = org.telegram.messenger.UMessageMusic.readDuration(prepared.file) / 1000.0;
        document.attributes.add(audio);
        return document;
    }

    /**
     * A local audio message with the song's name, shown in the chat until the song is ready; it looks
     * like any song (no loading ring) and is then sent itself, so it never jumps.
     */
    private static org.telegram.messenger.MessageObject showPlaceholder(int account, long dialogId, UMessageShazam.Track result) {
        final org.telegram.messenger.UserConfig userConfig = org.telegram.messenger.UserConfig.getInstance(account);
        final org.telegram.tgnet.TLRPC.TL_message message = new org.telegram.tgnet.TLRPC.TL_message();
        message.local_id = message.id = userConfig.getNewMessageId();
        userConfig.saveConfig(false);
        message.random_id = org.telegram.messenger.Utilities.random.nextLong();
        message.out = true;
        message.dialog_id = dialogId;
        message.from_id = new org.telegram.tgnet.TLRPC.TL_peerUser();
        message.from_id.user_id = userConfig.getClientUserId();
        message.peer_id = org.telegram.messenger.MessagesController.getInstance(account).getPeer(dialogId);
        message.date = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime();
        message.message = "";
        final org.telegram.tgnet.TLRPC.TL_document document = new org.telegram.tgnet.TLRPC.TL_document();
        document.mime_type = "audio/mpeg";
        document.file_reference = new byte[0];
        final org.telegram.tgnet.TLRPC.TL_documentAttributeAudio audio = new org.telegram.tgnet.TLRPC.TL_documentAttributeAudio();
        audio.title = result.title;
        audio.performer = result.artist;
        document.attributes.add(audio);
        final org.telegram.tgnet.TLRPC.TL_messageMediaDocument media = new org.telegram.tgnet.TLRPC.TL_messageMediaDocument();
        media.flags |= 1;
        media.document = document;
        message.media = media;
        message.flags |= org.telegram.tgnet.TLRPC.MESSAGE_FLAG_HAS_MEDIA | org.telegram.tgnet.TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.send_state = org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SENDING;
        final org.telegram.messenger.MessageObject object = new org.telegram.messenger.MessageObject(account, message, false, true);
        object.wasJustSent = true;
        final java.util.ArrayList<org.telegram.messenger.MessageObject> list = new java.util.ArrayList<>();
        list.add(object);
        org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(org.telegram.messenger.NotificationCenter.didReceiveNewMessages, dialogId, list, false, 0);
        return object;
    }

    private static void removePlaceholder(int account, long dialogId, int id) {
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        ids.add(id);
        long channelId = 0;
        if (org.telegram.messenger.DialogObject.isChatDialog(dialogId)) {
            final org.telegram.tgnet.TLRPC.Chat chat = org.telegram.messenger.MessagesController.getInstance(account).getChat(-dialogId);
            if (org.telegram.messenger.ChatObject.isChannel(chat)) {
                channelId = chat.id;
            }
        }
        org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(org.telegram.messenger.NotificationCenter.messagesDeleted, ids, channelId, false);
    }

    private void openLink(String url) {
        if (!TextUtils.isEmpty(url) && getParentActivity() != null) {
            Browser.openUrl(getParentActivity(), url);
        }
    }

    private void setStatus(int title, int sub) {
        statusTitle.setText(LocaleController.getString(title));
        statusSub.setText(LocaleController.getString(sub));
    }

    private void startListening() {
        if (getParentActivity() == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(getParentActivity(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            getParentActivity().requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        final int id = ++session;
        listening = true;
        track = null;
        prepared = null;
        stopVideo();
        logoView.setMode(LogoParticlesView.MODE_LISTENING);
        root.setFlood(false);
        setStatus(R.string.UMessageShazamListeningTitle, R.string.UMessageShazamListeningHint);
        statusTitle.setVisibility(View.VISIBLE);
        statusSub.setVisibility(View.VISIBLE);
        resultView.setVisibility(View.GONE);
        new Thread(() -> record(id), "umessage-shazam").start();
    }

    private void stopListening() {
        session++;
        listening = false;
        if (!logoView.isFound()) {
            logoView.setMode(LogoParticlesView.MODE_IDLE);
        }
    }

    @SuppressLint("MissingPermission")
    private void record(int id) {
        final short[] samples = new short[LISTEN_SAMPLES];
        int count = 0;
        int nextAttempt = FIRST_ATTEMPT;
        AudioRecord recorder = null;
        try {
            final int minBuffer = AudioRecord.getMinBufferSize(UMessageShazam.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, UMessageShazam.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, UMessageShazam.SAMPLE_RATE));
            recorder.startRecording();
            while (session == id && count < samples.length) {
                final int read = recorder.read(samples, count, Math.min(1600, samples.length - count));
                if (read <= 0) {
                    break;
                }
                long sum = 0;
                for (int i = count; i < count + read; i++) {
                    sum += samples[i] * samples[i];
                }
                // room noise (~250 RMS) reads as silence, loud music near the phone as full
                final float level = Math.max(0f, Math.min(1f, ((float) Math.sqrt(sum / (double) read) - 250f) / 4500f));
                AndroidUtilities.runOnUIThread(() -> {
                    if (session == id) {
                        logoView.setLevel(level);
                    }
                });
                count += read;
                if (count >= nextAttempt && count < samples.length) {
                    final int snapshot = count;
                    recognizer.execute(() -> attempt(id, samples, snapshot, false));
                    nextAttempt += ATTEMPT_STEP;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignore) {
                }
                recorder.release();
            }
        }
        if (session == id) {
            final int snapshot = count;
            if (snapshot < FIRST_ATTEMPT) {
                AndroidUtilities.runOnUIThread(() -> onFinished(id, R.string.UMessageShazamMic));
            } else if (!recognizer.isShutdown()) {
                recognizer.execute(() -> attempt(id, samples, snapshot, true));
            }
        }
    }

    private void attempt(int id, short[] samples, int count, boolean last) {
        if (session != id) {
            return;
        }
        UMessageShazam.Track result = null;
        boolean failed = false;
        try {
            result = UMessageShazam.recognize(samples, count);
        } catch (Exception e) {
            FileLog.e(e);
            failed = true;
        }
        final UMessageShazam.Track found = result;
        final boolean error = failed;
        AndroidUtilities.runOnUIThread(() -> {
            if (session != id) {
                return;
            }
            if (found != null) {
                onFinished(id, 0);
                showResult(found);
            } else if (last) {
                onFinished(id, error ? R.string.UMessageShazamError : R.string.UMessageShazamNotFound);
            }
        });
    }

    private void onFinished(int id, int status) {
        if (session != id) {
            return;
        }
        stopListening();
        if (status != 0) {
            setStatus(R.string.UMessageShazamFind, status);
        }
    }

    /**
     * The logo's dots fly into the cover and become it, the cover's color floods the screen and the
     * song's name rises in; meanwhile the song itself is made ready, so sending is instant.
     */
    private void showResult(UMessageShazam.Track result) {
        track = result;
        prepared = prepare(currentAccount, result);
        statusTitle.setVisibility(View.GONE);
        statusSub.setVisibility(View.GONE);
        logoView.setCover(null);
        logoView.setMode(LogoParticlesView.MODE_FOUND);
        root.setColors(FloodLayout.DEFAULT_COLORS);
        root.setFlood(true);
        sendButton.setTextColor(FloodLayout.DEFAULT_COLORS[3]);
        if (BuildVars.LOGS_ENABLED) FileLog.d("UMusic: cover url " + result.coverUrl);
        org.telegram.messenger.UMessageMusic.loadCover(result.coverUrl, bitmap -> {
            if (BuildVars.LOGS_ENABLED) FileLog.d("UMusic: cover loaded " + (bitmap != null) + " current " + (track == result));
            if (track == result && bitmap == null) {
                logoView.setCover(null);
                logoView.setCoverFailed();
            }
            if (track == result && bitmap != null) {
                logoView.setCover(bitmap);
                final int[] colors = FloodLayout.colorsOf(bitmap);
                root.setColors(colors);
                sendButton.setTextColor(colors[3]);
            }
        });
        titleView.setText(result.title);
        artistView.setText(result.artist);
        artistView.setVisibility(TextUtils.isEmpty(result.artist) ? View.INVISIBLE : View.VISIBLE);
        resultView.setVisibility(View.VISIBLE);
        rise(titleView, 1000);
        rise(artistView, 1100);
        rise(sendButton, 1200);
        rise(againButton, 1200);
        loadVideo(result);
    }

    private void loadVideo(UMessageShazam.Track result) {
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final String url = org.telegram.messenger.UMessageMusic.findVideo(result.title, result.artist);
            java.io.File clip = null;
            if (url != null && track == result) {
                try {
                    clip = org.telegram.messenger.UMessageMusic.downloadVideo(url);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            final java.io.File file = clip;
            AndroidUtilities.runOnUIThread(() -> {
                if (file == null || track != result || videoView == null || isFinishing()) {
                    return;
                }
                stopVideo();
                // muted: the song itself is what the user just heard
                videoPlayer = new VideoPlayer(false, true);
                videoPlayer.setTextureView(videoView);
                videoPlayer.setLooping(true);
                videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                    @Override
                    public void onStateChanged(boolean playWhenReady, int playbackState) {
                    }

                    @Override
                    public void onError(VideoPlayer player, Exception e) {
                        FileLog.e(e);
                    }

                    @Override
                    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                        cropVideo(width * pixelWidthHeightRatio, height);
                    }

                    @Override
                    public void onRenderedFirstFrame() {
                        if (track == result) {
                            videoReady = true;
                            revealVideo();
                        }
                    }
                });
                videoPlayer.preparePlayer(android.net.Uri.fromFile(file), "other");
                videoPlayer.play();
            });
        });
    }

    /**
     * Once the cover has fully appeared and the clip is ready, the cover moves up into the top left
     * corner and the clip fades in behind everything, from its start.
     */
    private void revealVideo() {
        if (!videoReady || !logoView.isCoverShown() || videoShown || videoPlayer == null) {
            return;
        }
        videoShown = true;
        videoPlayer.seekTo(0);
        videoView.animate().alpha(1f).setStartDelay(150).setDuration(600).start();
        videoShade.animate().alpha(1f).setStartDelay(150).setDuration(600).start();
        logoView.setPivotX(0);
        logoView.setPivotY(0);
        logoView.animate().scaleX(COVER_SMALL / 240f).scaleY(COVER_SMALL / 240f)
                .translationX(dp(20) - logoView.getLeft()).translationY(dp(12) - logoView.getTop())
                .setDuration(650).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    /** Fills the screen with the clip, cut evenly on both sides: the singer is in the middle. */
    private void cropVideo(float width, float height) {
        final int viewWidth = videoView.getWidth(), viewHeight = videoView.getHeight();
        if (width <= 0 || height <= 0 || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        final float videoAspect = width / height, viewAspect = viewWidth / (float) viewHeight;
        final android.graphics.Matrix matrix = new android.graphics.Matrix();
        if (videoAspect > viewAspect) {
            matrix.setScale(videoAspect / viewAspect, 1f, viewWidth / 2f, viewHeight / 2f);
        } else {
            matrix.setScale(1f, viewAspect / videoAspect, viewWidth / 2f, viewHeight / 2f);
        }
        videoView.setTransform(matrix);
    }

    private void stopVideo() {
        videoReady = false;
        videoShown = false;
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (videoView != null) {
            videoView.animate().cancel();
            videoView.setAlpha(0f);
            videoShade.animate().cancel();
            videoShade.setAlpha(0f);
            logoView.animate().cancel();
            logoView.setScaleX(1f);
            logoView.setScaleY(1f);
            logoView.setTranslationX(0);
            logoView.setTranslationY(0);
        }
    }

    private static void rise(View view, long delay) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(16));
        view.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(450).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_MIC) {
            return;
        }
        if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            setStatus(R.string.UMessageShazamFind, R.string.UMessageShazamMic);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (videoPlayer != null) {
            videoPlayer.pause();
        }
        if (listening) {
            stopListening();
            setStatus(R.string.UMessageShazamFind, R.string.UMessageShazamTapLogo);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (videoPlayer != null) {
            videoPlayer.play();
        }
    }

    @Override
    public void onFragmentDestroy() {
        stopVideo();
        session++;
        recognizer.shutdownNow();
        super.onFragmentDestroy();
    }

    /** The screen's background: near black, flooded with the cover's colors once a song is found. */
    private static class FloodLayout extends FrameLayout {

        static final int[] DEFAULT_COLORS = {0xff5a5a5f, 0xff1c1c1e, 0xff09090b, 0xff111111};

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private int[] colors = DEFAULT_COLORS;
        private RadialGradient gradient;
        private float flood;
        private ValueAnimator animator;

        FloodLayout(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        /** Bright, mid and edge colors of the flood plus a dark one for text, from the cover. */
        static int[] colorsOf(android.graphics.Bitmap bitmap) {
            final float[] hsv = new float[3];
            Color.colorToHSV(AndroidUtilities.getDominantColor(bitmap), hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.78f);
            final int bright = Color.HSVToColor(hsv);
            final float value = hsv[2];
            hsv[2] = value * 0.42f;
            final int mid = Color.HSVToColor(hsv);
            hsv[2] = value * 0.1f;
            final int edge = Color.HSVToColor(hsv);
            hsv[2] = value * 0.55f;
            hsv[1] = Math.min(1f, hsv[1] + 0.15f);
            final int text = Color.HSVToColor(hsv);
            return new int[]{bright, mid, edge, text};
        }

        void setColors(int[] value) {
            colors = value;
            gradient = null;
            invalidate();
        }

        void setFlood(boolean on) {
            if (animator != null) {
                animator.cancel();
            }
            if (!on) {
                flood = 0;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(flood, 1f);
            animator.addUpdateListener(a -> {
                flood = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setStartDelay(500);
            animator.setDuration(900);
            animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            animator.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            gradient = null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xff09090b);
            if (flood <= 0) {
                return;
            }
            if (gradient == null) {
                gradient = new RadialGradient(0, 0, 1, new int[]{colors[0], colors[1], colors[2]}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                // an ellipse 120 % wide and 80 % high, centred a quarter down the screen
                matrix.setScale(getWidth() * 1.2f, getHeight() * 0.8f);
                matrix.postTranslate(getWidth() / 2f, getHeight() * 0.25f);
                gradient.setLocalMatrix(matrix);
                paint.setShader(gradient);
            }
            paint.setAlpha((int) (255 * flood));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    /**
     * The U message mark made of 36 dots. Idle they breathe, listening they shake with the sound;
     * when the song is found every dot flies to its own square of the cover and grows into it, and
     * the cover itself fades in sharp on top.
     */
    private static class LogoParticlesView extends View {

        static final int MODE_IDLE = 0, MODE_LISTENING = 1, MODE_FOUND = 2;

        private static final int COUNT = 36;
        private static final long MORPH = 700, COVER_FADE = 260;
        /** Dot positions on the mark and square cells of the cover, in a 240 × 240 box. */
        private static final float[] POINTS = new float[COUNT * 2];
        private static final int[] DEFAULT_CELLS = new int[COUNT];

        static {
            final float k = 240f / 820f;
            int n = 0;
            for (int flip = 0; flip < 2; flip++) {
                for (int i = 0; i < 18; i++) {
                    float x, y;
                    if (i < 7) {
                        x = 155 + i * 55;
                        y = 49;
                    } else {
                        final double a = -Math.PI / 2 - Math.PI * (i - 7 + 0.5) / 11;
                        x = (float) (525 + 175.5 * Math.cos(a));
                        y = (float) (224.5 - 175.5 * Math.sin(a));
                    }
                    if (flip == 1) {
                        // the mark's twin, turned 180° around the ring's centre
                        x = 1050 - x;
                        y = 449 - y;
                    }
                    POINTS[n++] = (x - 115) * k;
                    POINTS[n++] = (y + 185.5f) * k;
                }
            }
            for (int i = 0; i < COUNT; i++) {
                final int r = i / 6, c = i % 6;
                final float t = (r + c) / 10f;
                DEFAULT_CELLS[i] = ColorUtils.blendARGB(0xffffffff, 0xff8e8e93, t);
            }
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private final Path clipPath = new Path();
        private final int[] cells = DEFAULT_CELLS.clone();
        private int mode = MODE_IDLE;
        private long foundTime, coverTime;
        private float level, shownLevel;
        private android.graphics.Bitmap cover;
        private boolean coverFailed, coverShown;
        /** Runs once the found song's cover is fully on screen (or failed to load). */
        Runnable onCoverShown;

        LogoParticlesView(Context context) {
            super(context);
        }

        boolean isFound() {
            return mode == MODE_FOUND;
        }

        void setMode(int value) {
            mode = value;
            if (value == MODE_FOUND) {
                foundTime = SystemClock.elapsedRealtime();
                coverTime = 0;
            }
            coverFailed = false;
            coverShown = false;
            invalidate();
        }

        boolean isCoverShown() {
            return coverShown;
        }

        void setCoverFailed() {
            coverFailed = true;
            invalidate();
        }

        private void markShown() {
            if (!coverShown) {
                coverShown = true;
                if (onCoverShown != null) {
                    post(onCoverShown);
                }
            }
        }

        void setLevel(float value) {
            level = value;
        }

        void setCover(android.graphics.Bitmap bitmap) {
            cover = bitmap;
            coverPaint.setShader(null);
            System.arraycopy(DEFAULT_CELLS, 0, cells, 0, COUNT);
            if (bitmap != null) {
                // each dot takes the color of its own square, so the cover is already there while they fly
                final android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bitmap, 6, 6, true);
                for (int i = 0; i < COUNT; i++) {
                    cells[i] = small.getPixel(i % 6, i / 6) | 0xff000000;
                }
                coverPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            invalidate();
        }

        private static final CubicBezierInterpolator MORPH_EASE = new CubicBezierInterpolator(0.7, 0, 0.2, 1);

        @Override
        protected void onDraw(Canvas canvas) {
            final float u = getWidth() / 240f;
            final long now = SystemClock.elapsedRealtime();
            final float time = now / 1000f;
            // rises fast with a beat, falls back slower
            shownLevel += (level - shownLevel) * (level > shownLevel ? 0.45f : 0.12f);
            boolean morphDone = mode == MODE_FOUND;
            final boolean clip = mode == MODE_FOUND;
            if (clip) {
                // the cover's rounded corners close in while the dots fly into it
                final float corners = 20 * u * Math.min(1, (now - foundTime) / (float) (MORPH + 350));
                clipPath.rewind();
                rect.set(0, 0, getWidth(), getHeight());
                clipPath.addRoundRect(rect, corners, corners, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clipPath);
            }
            for (int i = 0; i < COUNT; i++) {
                float p = 0;
                if (mode == MODE_FOUND) {
                    final long delay = (long) (((i / 6) + (i % 6)) * 35);
                    p = MORPH_EASE.getInterpolation(Math.max(0, Math.min(1, (now - foundTime - delay) / (float) MORPH)));
                    morphDone &= p >= 1;
                }
                final float px = POINTS[i * 2], py = POINTS[i * 2 + 1];
                float dx = 0, dy = 0, scale = 1;
                final float phase = (i * 0.13f) % 0.9f;
                if (mode == MODE_LISTENING || p < 1 && mode == MODE_FOUND) {
                    // the CSS "jig": (0,0) → (5,-7) → (-4,3) → (3,6) → (0,0) every 0.9 s
                    final float f = ((time + phase) % 0.9f) / 0.9f * 4;
                    final int seg = (int) f;
                    final float t = (float) (0.5 - 0.5 * Math.cos(Math.PI * (f - seg)));
                    final float[] kx = {0, 5, -4, 3, 0}, ky = {0, -7, 3, 6, 0};
                    // follows the sound: almost still when it is quiet, shaking hard when it is loud
                    final float amp = (0.06f + shownLevel * 2.4f) * (1 - p);
                    dx = (kx[seg] + (kx[seg + 1] - kx[seg]) * t) * amp;
                    dy = (ky[seg] + (ky[seg + 1] - ky[seg]) * t) * amp;
                } else if (mode == MODE_IDLE) {
                    scale = 1 + 0.25f * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * (time + phase) / 2.4));
                }
                final float dotSize = 12 * scale;
                final float left = (px - dotSize / 2 + dx) + ((i % 6) * 40 - (px - dotSize / 2 + dx)) * p;
                final float top = (py - dotSize / 2 + dy) + ((i / 6) * 40 - (py - dotSize / 2 + dy)) * p;
                final float size = dotSize + (40 - dotSize) * p;
                rect.set(left * u, top * u, (left + size) * u, (top + size) * u);
                final int dotColor = 0xffffffff;
                paint.setColor(ColorUtils.blendARGB(dotColor, cells[i], p));
                final float radius = size / 2 * (1 - p) * u;
                // overlap the squares a hair so no seams show between them
                if (p >= 1) {
                    rect.inset(-0.5f, -0.5f);
                }
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            if (clip) {
                canvas.restore();
            }
            if (morphDone && cover != null) {
                if (coverTime == 0) {
                    coverTime = now;
                }
                final float alpha = Math.min(1, (now - coverTime) / (float) COVER_FADE);
                final float side = 240 * u;
                matrix.setScale(side / cover.getWidth(), side / cover.getHeight());
                coverPaint.getShader().setLocalMatrix(matrix);
                coverPaint.setAlpha((int) (255 * alpha));
                rect.set(0, 0, side, side);
                canvas.drawRoundRect(rect, 20 * u * alpha, 20 * u * alpha, coverPaint);
                if (alpha >= 1) {
                    markShown();
                    return;
                }
            } else if (morphDone && coverFailed) {
                markShown();
                return;
            }
            invalidate();
        }
    }
}
