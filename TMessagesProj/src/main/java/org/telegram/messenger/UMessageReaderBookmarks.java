package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.TreeSet;

/**
 * Per-document page bookmarks of the in-app PDF reader. Pages are 0-based.
 */
public final class UMessageReaderBookmarks {

    private static final String PREFS = "umessage_reader";

    private UMessageReaderBookmarks() {
    }

    public static boolean isBookmarked(long documentId, int page) {
        return read(documentId).contains(page);
    }

    /** @return the new state of the page */
    public static boolean toggle(long documentId, int page) {
        TreeSet<Integer> pages = read(documentId);
        boolean bookmarked = !pages.remove(page);
        if (bookmarked) {
            pages.add(page);
        }
        SharedPreferences.Editor editor = prefs().edit();
        if (pages.isEmpty()) {
            editor.remove(key(documentId));
        } else {
            editor.putString(key(documentId), TextUtils.join(",", pages));
        }
        editor.apply();
        return bookmarked;
    }

    public static ArrayList<Integer> list(long documentId) {
        return new ArrayList<>(read(documentId));
    }

    private static TreeSet<Integer> read(long documentId) {
        TreeSet<Integer> pages = new TreeSet<>();
        String stored = prefs().getString(key(documentId), null);
        if (TextUtils.isEmpty(stored)) {
            return pages;
        }
        for (String part : stored.split(",")) {
            try {
                int page = Integer.parseInt(part.trim());
                if (page >= 0) {
                    pages.add(page);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return pages;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(long documentId) {
        return "bm_" + documentId;
    }
}
