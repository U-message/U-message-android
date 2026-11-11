package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * U message: exports a whole chat (every message and every media file) as messages.html plus a media folder,
 * like Telegram Desktop does. The result opens in {@link UMessageExportViewerActivity}, which saves it as a ZIP
 * or prints it to PDF.
 */
public class UMessageChatExport {

    private static final int PAGE = 100;
    private static final long MEDIA_TIMEOUT_MS = 5 * 60 * 1000;
    private static final Pattern URL = Pattern.compile("(https?://[^\\s<]+|www\\.[^\\s<]+)");

    private final ChatActivity fragment;
    private final int account;
    private final long dialogId;
    private final String title;
    private final ArrayList<TLRPC.Message> messages = new ArrayList<>();
    private final HashMap<Integer, TLRPC.Message> byId = new HashMap<>();
    private final HashMap<Integer, String> mediaPaths = new HashMap<>();
    private AlertDialog progress;
    private volatile boolean cancelled;

    public static void show(ChatActivity fragment) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        if (isRestricted(fragment)) {
            android.widget.Toast.makeText(context, getString(R.string.UMessageExportRestricted), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        fragment.showDialog(new AlertDialog.Builder(context, fragment.getResourceProvider())
            .setTitle(getString(R.string.UMessageExportChat))
            .setMessage(getString(R.string.UMessageExportConfirm))
            .setPositiveButton(getString(R.string.UMessageExportStart), (d, w) -> new UMessageChatExport(fragment).start())
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
    }

    /** Chats where saving content is restricted (noforwards), and secret chats, are never exported. */
    private static boolean isRestricted(ChatActivity fragment) {
        if (fragment.getCurrentEncryptedChat() != null || fragment.isPeerNoForwards()) {
            return true;
        }
        final MessagesController controller = MessagesController.getInstance(fragment.getCurrentAccount());
        final TLRPC.Chat chat = fragment.getCurrentChat();
        if (chat != null && (chat.noforwards || controller.isChatNoForwards(chat))) {
            return true;
        }
        return controller.isPeerNoForwards(fragment.getDialogId());
    }

    /** Protected messages and self-destructing / view-once media stay out of the export. */
    private static boolean skipMessage(TLRPC.Message m) {
        if (m.noforwards) {
            return true;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(m);
        return media != null && media.ttl_seconds != 0;
    }

    /** Removes exports left over from earlier runs (called on startup). */
    public static void cleanupStale() {
        try {
            deleteRecursive(new File(ApplicationLoader.applicationContext.getCacheDir(), "umessage_export"));
            final File ext = ApplicationLoader.applicationContext.getExternalCacheDir();
            if (ext != null) {
                deleteRecursive(new File(ext, "umessage_export")); // older builds staged it here
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private UMessageChatExport(ChatActivity fragment) {
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.dialogId = fragment.getDialogId();
        final TLRPC.User user = fragment.getCurrentUser();
        final TLRPC.Chat chat = fragment.getCurrentChat();
        this.title = user != null ? UserObject.getUserName(user) : chat != null ? chat.title : "chat";
    }

    private void start() {
        progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_LOADING);
        progress.setMessage(formatString(R.string.UMessageExportLoadingMessages, 0));
        progress.setCanceledOnTouchOutside(false);
        progress.setOnCancelListener(d -> cancelled = true);
        progress.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.Cancel), (d, w) -> {
            cancelled = true;
            d.dismiss();
        });
        progress.show();
        loadPage(0);
    }

    private void setProgressText(String text) {
        setProgressText(text, -1);
    }

    private void setProgressText(String text, int percent) {
        AndroidUtilities.runOnUIThread(() -> {
            if (progress != null && progress.isShowing()) {
                progress.setMessage(text);
                if (percent >= 0) {
                    progress.setProgress(percent);
                }
            }
        });
    }

    private void fail() {
        AndroidUtilities.runOnUIThread(() -> {
            if (progress != null) {
                progress.dismiss();
            }
            if (!cancelled && fragment.getParentActivity() != null) {
                org.telegram.ui.Components.BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.UMessageExportFailed)).show();
            }
        });
    }

    /** History comes newest first; pages continue from the oldest message seen until the server returns none. */
    private void loadPage(int offsetId) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = controller.getInputPeer(dialogId);
        req.offset_id = offsetId;
        req.limit = PAGE;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (cancelled) {
                return;
            }
            if (!(res instanceof TLRPC.messages_Messages)) {
                fail();
                return;
            }
            final TLRPC.messages_Messages r = (TLRPC.messages_Messages) res;
            controller.putUsers(r.users, false);
            controller.putChats(r.chats, false);
            int minId = Integer.MAX_VALUE;
            for (TLRPC.Message m : r.messages) {
                if (m instanceof TLRPC.TL_messageEmpty || byId.containsKey(m.id)) {
                    continue;
                }
                if (skipMessage(m)) {
                    minId = Math.min(minId, m.id);
                    continue;
                }
                messages.add(m);
                byId.put(m.id, m);
                minId = Math.min(minId, m.id);
            }
            setProgressText(formatString(R.string.UMessageExportLoadingMessages, messages.size()));
            if (r.messages.isEmpty() || minId == Integer.MAX_VALUE || minId <= 1) {
                Collections.reverse(messages);
                new Thread(this::buildFiles, "umessage-export").start();
            } else {
                loadPage(minId);
            }
        }));
    }

    private void buildFiles() {
        try {
            final File root = new File(ApplicationLoader.applicationContext.getCacheDir(), "umessage_export");
            deleteRecursive(root);
            final String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
            final String baseName = safeName(title) + "_" + stamp;
            final File dir = new File(root, baseName);
            final File mediaDir = new File(dir, "media");
            if (!mediaDir.mkdirs()) {
                fail();
                return;
            }

            final ArrayList<TLRPC.Message> withMedia = new ArrayList<>();
            for (TLRPC.Message m : messages) {
                final TLRPC.MessageMedia media = MessageObject.getMedia(m);
                if (media != null && (media.photo instanceof TLRPC.TL_photo || media.document instanceof TLRPC.TL_document)) {
                    withMedia.add(m);
                }
            }
            for (int i = 0; i < withMedia.size() && !cancelled; i++) {
                setProgressText(formatString(R.string.UMessageExportLoadingMedia, i + 1, withMedia.size()), i * 100 / withMedia.size());
                final TLRPC.Message m = withMedia.get(i);
                final File src = download(m);
                if (src != null && src.exists()) {
                    final String name = m.id + "_" + safeName(fileName(m, src));
                    copy(src, new File(mediaDir, name));
                    mediaPaths.put(m.id, "media/" + name);
                }
            }
            if (cancelled) {
                return;
            }
            setProgressText(getString(R.string.UMessageExportWriting), 100);
            final File html = new File(dir, "messages.html");
            writeHtml(html);
            final File zip = new File(root, baseName + ".zip");
            zipDir(dir, zip);
            AndroidUtilities.runOnUIThread(() -> {
                if (progress != null) {
                    progress.dismiss();
                }
                if (!cancelled) {
                    fragment.presentFragment(new UMessageExportViewerActivity(html, zip, title));
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
            fail();
        }
    }

    /** Waits for FileLoader to bring the message's file (full-size photo or the document). Null when it fails. */
    private File download(TLRPC.Message m) throws InterruptedException {
        final FileLoader loader = FileLoader.getInstance(account);
        final File path = loader.getPathToMessage(m);
        if (path.exists() && path.length() > 0) {
            return path;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(m);
        final MessageObject parent = new MessageObject(account, m, false, false);
        final String attachName;
        final Runnable load;
        if (media.photo instanceof TLRPC.TL_photo) {
            final TLRPC.Photo photo = media.photo;
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
            if (size == null) {
                return null;
            }
            attachName = FileLoader.getAttachFileName(size);
            load = () -> loader.loadFile(ImageLocation.getForPhoto(size, photo), parent, "jpg", FileLoader.PRIORITY_HIGH, 0);
        } else {
            final TLRPC.Document document = media.document;
            attachName = FileLoader.getAttachFileName(document);
            load = () -> loader.loadFile(document, parent, FileLoader.PRIORITY_HIGH, 0);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final File[] result = new File[1];
        final NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> {
            if (args.length > 0 && TextUtils.equals(attachName, (String) args[0])) {
                if (id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File) {
                    result[0] = (File) args[1];
                }
                latch.countDown();
            }
        };
        AndroidUtilities.runOnUIThread(() -> {
            final NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(observer, NotificationCenter.fileLoaded);
            center.addObserver(observer, NotificationCenter.fileLoadFailed);
            load.run();
        });
        try {
            final long deadline = System.currentTimeMillis() + MEDIA_TIMEOUT_MS;
            while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                if (cancelled || System.currentTimeMillis() > deadline) {
                    break;
                }
                if (path.exists() && path.length() > 0) {
                    return path;
                }
            }
        } finally {
            AndroidUtilities.runOnUIThread(() -> {
                final NotificationCenter center = NotificationCenter.getInstance(account);
                center.removeObserver(observer, NotificationCenter.fileLoaded);
                center.removeObserver(observer, NotificationCenter.fileLoadFailed);
            });
        }
        if (result[0] != null) {
            return result[0];
        }
        return path.exists() ? path : null;
    }

    private void writeHtml(File file) throws Exception {
        final Locale locale = LocaleController.getInstance().getCurrentLocale();
        final DateFormat dayFormat = DateFormat.getDateInstance(DateFormat.LONG, locale);
        final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", locale);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            w.write("<title>" + esc(title) + "</title><style>" + CSS + "</style></head><body><header><h1>" + esc(title) + "</h1><p>");
            w.write(messages.size() + " · U message</p></header><main>");
            String lastDay = null;
            long lastSender = 0;
            for (TLRPC.Message m : messages) {
                final Date date = new Date(m.date * 1000L);
                final String day = dayFormat.format(date);
                if (!day.equals(lastDay)) {
                    w.write("<div class=\"day\">" + esc(day) + "</div>");
                    lastDay = day;
                    lastSender = 0;
                }
                if (m instanceof TLRPC.TL_messageService) {
                    w.write("<div class=\"service\">" + esc(String.valueOf(new MessageObject(account, m, false, false).messageText)) + "</div>");
                    lastSender = 0;
                    continue;
                }
                final long sender = MessageObject.getFromChatId(m);
                w.write("<div class=\"msg" + (m.out ? " out" : "") + "\" id=\"m" + m.id + "\">");
                if (sender != lastSender) {
                    w.write("<div class=\"from\">" + esc(senderName(sender)) + "</div>");
                    lastSender = sender;
                }
                if (m.fwd_from != null) {
                    w.write("<div class=\"fwd\">" + esc(getString(R.string.ForwardedFrom)) + " " + esc(forwardName(m.fwd_from)) + "</div>");
                }
                if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
                    final TLRPC.Message replied = byId.get(m.reply_to.reply_to_msg_id);
                    final String preview = replied == null ? "…" : snippet(replied);
                    w.write("<a class=\"reply\" href=\"#m" + m.reply_to.reply_to_msg_id + "\">" + esc(preview) + "</a>");
                }
                writeMedia(w, m);
                if (!TextUtils.isEmpty(m.message)) {
                    w.write("<div class=\"text\">" + linkify(esc(m.message)).replace("\n", "<br>") + "</div>");
                }
                w.write("<div class=\"time\">" + timeFormat.format(date) + (m.edit_date != 0 ? " ✎" : "") + "</div></div>");
            }
            w.write("</main></body></html>");
        }
    }

    private void writeMedia(BufferedWriter w, TLRPC.Message m) throws Exception {
        final TLRPC.MessageMedia media = MessageObject.getMedia(m);
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            return;
        }
        final String path = mediaPaths.get(m.id);
        if (media.photo instanceof TLRPC.TL_photo || media.document instanceof TLRPC.TL_document) {
            final TLRPC.Document doc = media.document;
            final String name = doc != null ? FileLoader.getDocumentFileName(doc) : "photo.jpg";
            if (path == null) {
                w.write("<div class=\"missing\">[" + esc(TextUtils.isEmpty(name) ? "media" : name) + ": " + esc(getString(R.string.UMessageExportMediaMissing)) + "]</div>");
                return;
            }
            final String src = escAttr(path);
            if (doc == null || doc.mime_type != null && doc.mime_type.startsWith("image/") || MessageObject.isStickerDocument(doc)) {
                w.write("<a href=\"" + src + "\"><img class=\"media\" loading=\"lazy\" src=\"" + src + "\" alt=\"\"></a>");
            } else if (MessageObject.isRoundVideoDocument(doc)) {
                w.write("<video class=\"round\" controls preload=\"metadata\" src=\"" + src + "\"></video><span class=\"print\">" + esc(name) + "</span>");
            } else if (MessageObject.isVideoDocument(doc)) {
                w.write("<video class=\"media\" controls preload=\"metadata\" src=\"" + src + "\"></video><span class=\"print\">" + esc(name) + "</span>");
            } else if (MessageObject.isVoiceDocument(doc) || MessageObject.isMusicDocument(doc)) {
                w.write("<audio controls preload=\"none\" src=\"" + src + "\"></audio><span class=\"print\">" + esc(name) + "</span>");
            } else {
                w.write("<a class=\"file\" href=\"" + src + "\">" + esc(TextUtils.isEmpty(name) ? "file" : name) + " · " + AndroidUtilities.formatFileSize(doc.size) + "</a>");
            }
        } else {
            // location, contact, poll, game, invoice...: the same one-line text the chat list shows
            final CharSequence text = new MessageObject(account, m, false, false).messageText;
            if (!TextUtils.isEmpty(text) && TextUtils.isEmpty(m.message)) {
                w.write("<div class=\"text other\">" + esc(text.toString()) + "</div>");
            }
        }
    }

    private String senderName(long id) {
        final MessagesController c = MessagesController.getInstance(account);
        if (id > 0) {
            final TLRPC.User u = c.getUser(id);
            return u == null ? "" : UserObject.getUserName(u);
        }
        final TLRPC.Chat ch = c.getChat(-id);
        return ch == null ? title : ch.title;
    }

    private String forwardName(TLRPC.MessageFwdHeader fwd) {
        if (!TextUtils.isEmpty(fwd.from_name)) {
            return fwd.from_name;
        }
        if (fwd.from_id != null) {
            return senderName(MessageObject.getPeerId(fwd.from_id));
        }
        return "";
    }

    private String snippet(TLRPC.Message m) {
        String s = !TextUtils.isEmpty(m.message) ? m.message : String.valueOf(new MessageObject(account, m, false, false).messageText);
        s = s.replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String fileName(TLRPC.Message m, File src) {
        final TLRPC.MessageMedia media = MessageObject.getMedia(m);
        if (media != null && media.document != null) {
            final String name = FileLoader.getDocumentFileName(media.document);
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        }
        return src.getName();
    }

    private static String safeName(String s) {
        final String r = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("_+", "_");
        return TextUtils.isEmpty(r) ? "chat" : r.length() > 60 ? r.substring(0, 60) : r;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escAttr(String path) {
        return esc(path).replace(" ", "%20").replace("#", "%23");
    }

    private static String linkify(String escaped) {
        final Matcher m = URL.matcher(escaped);
        final StringBuffer sb = new StringBuffer();
        while (m.find()) {
            final String url = m.group(1);
            final String href = url.startsWith("www.") ? "https://" + url : url;
            m.appendReplacement(sb, Matcher.quoteReplacement("<a href=\"" + href + "\">" + url + "</a>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void zipDir(File dir, File zip) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            addToZip(out, dir, dir.getName());
        }
    }

    private static void addToZip(ZipOutputStream out, File file, String name) throws Exception {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(out, child, name + "/" + child.getName());
                }
            }
            return;
        }
        out.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        out.closeEntry();
    }

    private static void deleteRecursive(File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static final String CSS =
        "body{margin:0;background:#f4f4f2;color:#141414;font:15px/1.45 -apple-system,Roboto,'Segoe UI',sans-serif}" +
        "header{padding:20px 16px;background:#0c0c0e;color:#fff}header h1{margin:0;font-size:20px}header p{margin:4px 0 0;color:#9a9a96;font-size:13px}" +
        "main{max-width:760px;margin:0 auto;padding:12px}" +
        ".day{text-align:center;margin:18px 0 8px;font-size:12.5px;color:#6b6b68}" +
        ".service{text-align:center;margin:8px auto;font-size:13px;color:#555;max-width:80%}" +
        ".msg{background:#fff;border-radius:14px;padding:8px 12px;margin:4px 0;max-width:82%;box-shadow:0 1px 1px rgba(0,0,0,.06);overflow-wrap:anywhere}" +
        ".msg.out{margin-left:auto;background:#e9e1fb}" +
        ".from{font-weight:600;font-size:13.5px;color:#4a3c8c;margin-bottom:2px}" +
        ".fwd{font-size:12.5px;color:#6b6b68;margin-bottom:2px}" +
        ".reply{display:block;border-left:3px solid #7b55d1;padding:2px 8px;margin:2px 0 6px;font-size:13px;color:#555;text-decoration:none;background:rgba(123,85,209,.07);border-radius:4px}" +
        ".media{display:block;max-width:100%;max-height:420px;border-radius:10px;margin:4px 0}" +
        ".round{width:220px;height:220px;border-radius:50%;object-fit:cover;display:block}" +
        "audio{width:100%;margin:4px 0}.file{display:inline-block;padding:8px 10px;border-radius:10px;background:#f1f1ef;color:#141414;text-decoration:none;margin:4px 0}" +
        ".missing{color:#8a8a86;font-size:13px}.time{text-align:right;font-size:11.5px;color:#8a8a86}" +
        ".print{display:none}a{color:#4a3c8c}" +
        "@media print{body{background:#fff}header{background:#fff;color:#000;border-bottom:1px solid #ccc}video,audio{display:none}.print{display:inline-block;font-size:12px;color:#555}" +
        ".msg{box-shadow:none;border:1px solid #e3e3e0;page-break-inside:avoid}.media{max-height:300px}}";
}
