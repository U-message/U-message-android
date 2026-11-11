package org.telegram.messenger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Telegram part of the Supreme Court list of banned sources (see Tools/banned_sources).
 * A username match is certain. Most list entries give only a name, so a title match
 * means "same name" and never proves it is the same channel.
 */
public final class UMessageBannedSources {

    public static final int NONE = 0;
    public static final int TITLE = 1;
    public static final int USERNAME = 2;

    private static final String ASSET = "umessage_banned_sources.txt";
    private static final int MIN_SINGLE_WORD_LENGTH = 9;

    private static final Set<String> usernames = new HashSet<>();
    private static final Set<String> titles = new HashSet<>();
    private static String listDate = "";
    private static boolean loaded;

    private UMessageBannedSources() {
    }

    public static synchronized String getListDate() {
        ensureLoaded();
        return listDate.replaceAll("(\\d+)-(\\d+)-(\\d+)", "$3.$2.$1");
    }

    public static synchronized int match(Iterable<String> peerUsernames, String title, boolean isChat) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.BANNED_SOURCES)) return NONE;
        ensureLoaded();
        for (String username : peerUsernames) {
            if (username != null && usernames.contains(username.toLowerCase(Locale.ROOT))) {
                return USERNAME;
            }
        }
        return isChat && title != null && titles.contains(skeleton(title)) ? TITLE : NONE;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(ASSET)) {
            read(in);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    static void read(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() < 3) {
                continue;
            }
            String value = line.substring(2);
            switch (line.charAt(0)) {
                case 'd':
                    listDate = value;
                    break;
                case 'u':
                    usernames.add(value);
                    break;
                case 't':
                    addTitle(value);
                    break;
            }
        }
    }

    private static void addTitle(String title) {
        String key = skeleton(title);
        // One common word ("Islom", "Sabr", "Umar") names thousands of unrelated chats.
        if (key.length() >= MIN_SINGLE_WORD_LENGTH || key.length() >= 4 && title.trim().indexOf(' ') > 0) {
            titles.add(key);
        }
    }

    /** Folds case, diacritics, look-alike letters, emoji and punctuation so spellings compare equal. */
    static String skeleton(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                int fold = "аеорсухікмт".indexOf(c);
                out.append(fold >= 0 ? "aeopcyxikmt".charAt(fold) : c);
            }
        }
        return out.toString();
    }
}
