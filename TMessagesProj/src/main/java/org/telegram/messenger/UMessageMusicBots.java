package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Full songs for Shazam: asks music bots in inline mode (the same as typing "@bot song" in a chat)
 * and takes the best matching audio; when no bot has it, searches the user's own chats and public
 * music channels found through Telegram's search. The song is then sent as our own file, never as
 * the bot's result, so no "via @bot", no bot caption and no trace of the bot in the app.
 */
public final class UMessageMusicBots {

    /** Tried in order until one gives a matching song; replaced by the server's list (register reply "musicBots"). */
    private static final String[] DEFAULT_BOTS = {"vkm4bot", "vkmusic_bot", "DeezerMusicBot"};
    /** Searched in Telegram to find public music channels (no fixed list: channels come and go). */
    private static final String[] CHANNEL_QUERIES = {"mp3", "music", "musiqa", "muzika", "музыка", "треки"};
    private static final int MAX_CHANNELS = 12;
    private static final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private static long channelsTime;

    /** A song found: either a Telegram file to resend, or a link the bot points to. */
    public static final class Song {
        public TLRPC.TL_document document;
        public String url;
        public String mime;
        /** The message the document came from, for refreshing its file reference. */
        public Object parent;
    }

    public interface Callback {
        /** UI thread; null when no bot has the song. */
        void onResult(Song song);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_music", android.content.Context.MODE_PRIVATE);
    }

    public static void setBots(JSONArray bots) {
        if (bots != null) {
            prefs().edit().putString("bots", bots.toString()).apply();
        }
    }

    private static ArrayList<String> bots() {
        final ArrayList<String> result = new ArrayList<>();
        try {
            final String saved = prefs().getString("bots", null);
            if (saved != null) {
                final JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length(); i++) {
                    final String name = array.optString(i).replace("@", "").trim();
                    if (!name.isEmpty()) {
                        result.add(name);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        for (String name : DEFAULT_BOTS) {
            result.add(name);
        }
        return result;
    }

    public static void find(int account, String title, String artist, Callback callback) {
        final ArrayList<String> list = bots();
        final String query = TextUtils.isEmpty(artist) ? title : artist + " " + title;
        tryBot(account, list, 0, query, title, artist, callback);
    }

    private static void tryBot(int account, ArrayList<String> list, int index, String query, String title, String artist, Callback callback) {
        if (index >= list.size()) {
            searchChannels(account, query, title, artist, callback);
            return;
        }
        final Runnable next = () -> tryBot(account, list, index + 1, query, title, artist, callback);
        final String username = list.get(index);
        final TLObject object = MessagesController.getInstance(account).getUserOrChat(username);
        if (object instanceof TLRPC.User) {
            ask(account, (TLRPC.User) object, query, title, artist, callback, next);
            return;
        }
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                next.run();
                return;
            }
            final TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController.getInstance(account).putUsers(res.users, false);
            MessagesController.getInstance(account).putChats(res.chats, false);
            TLRPC.User bot = null;
            for (TLRPC.User user : res.users) {
                if (user.bot && username.equalsIgnoreCase(UserObject.getPublicUsername(user))) {
                    bot = user;
                }
            }
            if (bot == null) {
                next.run();
            } else {
                ask(account, bot, query, title, artist, callback, next);
            }
        }));
    }

    private static void ask(int account, TLRPC.User bot, String query, String title, String artist, Callback callback, Runnable next) {
        final TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
        req.bot = MessagesController.getInstance(account).getInputUser(bot);
        req.peer = new TLRPC.TL_inputPeerEmpty();
        req.query = query;
        req.offset = "";
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            final Song song = response instanceof TLRPC.messages_BotResults ? pick(((TLRPC.messages_BotResults) response).results, title, artist) : null;
            if (song != null) {
                callback.onResult(song);
            } else {
                next.run();
            }
        }));
    }

    /** The best audio: the title must match, the artist and a full length (not a 30 s preview) count extra. */
    private static Song pick(ArrayList<TLRPC.BotInlineResult> results, String title, String artist) {
        final String wantTitle = normalize(title);
        final String wantArtist = normalize(artist);
        Song best = null;
        int bestScore = 0;
        for (TLRPC.BotInlineResult result : results) {
            String songTitle = result.title, performer = result.description;
            double duration = 0;
            final ArrayList<TLRPC.DocumentAttribute> attributes = result.document != null ? result.document.attributes : result.content != null ? result.content.attributes : null;
            boolean audio = false;
            if (attributes != null) {
                for (TLRPC.DocumentAttribute attribute : attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeAudio && !attribute.voice) {
                        audio = true;
                        duration = attribute.duration;
                        if (!TextUtils.isEmpty(attribute.title)) {
                            songTitle = attribute.title;
                        }
                        if (!TextUtils.isEmpty(attribute.performer)) {
                            performer = attribute.performer;
                        }
                    }
                }
            }
            final Song song = new Song();
            if (result.document instanceof TLRPC.TL_document && (audio || MessageObject.isMusicDocument(result.document))) {
                song.document = (TLRPC.TL_document) result.document;
            } else if (result.content != null && !TextUtils.isEmpty(result.content.url) && result.content.url.startsWith("https://")
                    && (audio || result.content.mime_type != null && result.content.mime_type.startsWith("audio/"))) {
                song.url = result.content.url;
                song.mime = result.content.mime_type;
            } else {
                continue;
            }
            final int score = score(wantTitle, wantArtist, songTitle, performer, duration);
            if (score > bestScore) {
                bestScore = score;
                best = song;
            }
        }
        return best;
    }

    /** 0 when the title does not match; the artist and a full length (not a 30 s preview) count extra. */
    private static int score(String wantTitle, String wantArtist, String songTitle, String performer, double duration) {
        final String gotTitle = normalize(songTitle);
        final String gotArtist = normalize(performer);
        if (gotTitle.isEmpty() || wantTitle.isEmpty() || !(gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle))) {
            return 0;
        }
        int score = 1;
        if (!wantArtist.isEmpty() && (gotArtist.contains(wantArtist) || !gotArtist.isEmpty() && wantArtist.contains(gotArtist))) {
            score += 4;
        }
        if (duration == 0 || duration > 60) {
            score += 2;
        }
        if (gotTitle.equals(wantTitle)) {
            score += 1;
        }
        return score;
    }

    /** No bot had it: the user's own chats and public music channels, all at once; the best match wins. */
    private static void searchChannels(int account, String query, String title, String artist, Callback callback) {
        discoverChannels(account, () -> {
            final ArrayList<TLObject> requests = new ArrayList<>();
            final TLRPC.TL_messages_searchGlobal global = new TLRPC.TL_messages_searchGlobal();
            global.q = query;
            global.filter = new TLRPC.TL_inputMessagesFilterMusic();
            global.limit = 30;
            global.offset_peer = new TLRPC.TL_inputPeerEmpty();
            requests.add(global);
            for (TLRPC.Chat chat : channels) {
                final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
                req.peer = MessagesController.getInputPeer(chat);
                req.q = query;
                req.filter = new TLRPC.TL_inputMessagesFilterMusic();
                req.limit = 20;
                requests.add(req);
            }
            final ArrayList<TLRPC.Message> found = new ArrayList<>();
            final int[] left = {requests.size()};
            for (TLObject req : requests) {
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TLRPC.messages_Messages) {
                        final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                        MessagesController.getInstance(account).putUsers(res.users, false);
                        MessagesController.getInstance(account).putChats(res.chats, false);
                        found.addAll(res.messages);
                    }
                    if (--left[0] == 0) {
                        callback.onResult(pickMessage(account, found, title, artist));
                    }
                }));
            }
        });
    }

    private static Song pickMessage(int account, ArrayList<TLRPC.Message> messages, String title, String artist) {
        final String wantTitle = normalize(title);
        final String wantArtist = normalize(artist);
        TLRPC.Message best = null;
        int bestScore = 0;
        for (TLRPC.Message message : messages) {
            final TLRPC.Document document = message.media != null ? message.media.document : null;
            if (!(document instanceof TLRPC.TL_document)) {
                continue;
            }
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeAudio && !attribute.voice) {
                    String songTitle = attribute.title;
                    if (TextUtils.isEmpty(songTitle)) {
                        songTitle = FileLoader.getDocumentFileName(document);
                    }
                    final int score = score(wantTitle, wantArtist, songTitle, attribute.performer, attribute.duration);
                    if (score > bestScore) {
                        bestScore = score;
                        best = message;
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }
        final Song song = new Song();
        song.document = (TLRPC.TL_document) best.media.document;
        song.parent = new MessageObject(account, best, false, false);
        return song;
    }

    /** Public music channels from Telegram's own search, kept for the app session (refreshed daily). */
    private static void discoverChannels(int account, Runnable done) {
        if (!channels.isEmpty() && System.currentTimeMillis() - channelsTime < 24 * 60 * 60 * 1000L) {
            done.run();
            return;
        }
        final ArrayList<TLRPC.Chat> result = new ArrayList<>();
        final java.util.HashSet<Long> seen = new java.util.HashSet<>();
        final int[] left = {CHANNEL_QUERIES.length};
        for (String q : CHANNEL_QUERIES) {
            final TLRPC.TL_contacts_search req = new TLRPC.TL_contacts_search();
            req.q = q;
            req.limit = 10;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_contacts_found) {
                    final TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                    MessagesController.getInstance(account).putUsers(res.users, false);
                    MessagesController.getInstance(account).putChats(res.chats, false);
                    for (TLRPC.Chat chat : res.chats) {
                        if (ChatObject.isChannelAndNotMegaGroup(chat) && !TextUtils.isEmpty(ChatObject.getPublicUsername(chat)) && seen.add(chat.id)) {
                            result.add(chat);
                        }
                    }
                }
                if (--left[0] == 0) {
                    // the biggest channels have the most songs
                    java.util.Collections.sort(result, (a, b) -> Integer.compare(b.participants_count, a.participants_count));
                    channels.clear();
                    channels.addAll(result.subList(0, Math.min(MAX_CHANNELS, result.size())));
                    channelsTime = System.currentTimeMillis();
                    done.run();
                }
            }));
        }
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        s = s.toLowerCase(Locale.ROOT);
        final int cut = s.indexOf(" (");
        if (cut > 0) {
            s = s.substring(0, cut);
        }
        return s.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
