package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import java.util.ArrayList;
import java.util.TreeSet;

/** Compares audiences independently of the ordering of rules and user IDs. */
public final class UMessageStoryPrivacy {
    public static boolean matches(TL_stories.StoryItem story, ArrayList<TLRPC.PrivacyRule> desired) {
        if (story.min && story.privacy.isEmpty()) return false;
        ArrayList<TLRPC.PrivacyRule> actual = story.privacy;
        if (actual.isEmpty()) {
            actual = new ArrayList<>();
            if (story.isPublic) actual.add(new TLRPC.TL_privacyValueAllowAll());
            else if (story.contacts) actual.add(new TLRPC.TL_privacyValueAllowContacts());
            else if (story.close_friends) actual.add(new TLRPC.TL_privacyValueAllowCloseFriends());
            else return false;
        }
        return key(actual).equals(key(desired));
    }

    /** True only when the server's own rules leave nobody but the owner able to view the story. */
    public static boolean isSelfOnly(TL_stories.StoryItem story, long selfId) {
        if (story.isPublic || story.contacts || story.close_friends || story.privacy.isEmpty()) return false;
        for (TLRPC.PrivacyRule rule : story.privacy) {
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                for (long id : ((TLRPC.TL_privacyValueAllowUsers) rule).users) if (id != selfId) return false;
            } else if (!(rule instanceof TLRPC.TL_privacyValueDisallowAll) && !(rule instanceof TLRPC.TL_privacyValueDisallowUsers) && !(rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants)) {
                return false;
            }
        }
        return true;
    }

    private static String key(ArrayList<TLRPC.PrivacyRule> rules) {
        TreeSet<String> values = new TreeSet<>();
        boolean hasAudience = false;
        for (TLRPC.PrivacyRule rule : rules) {
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                for (long id : ((TLRPC.TL_privacyValueAllowUsers) rule).users) values.add("allow:" + id);
                hasAudience = true;
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                for (long id : ((TLRPC.TL_privacyValueDisallowUsers) rule).users) values.add("exclude:" + id);
            } else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                for (long id : ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats) values.add("allowChat:" + id);
                hasAudience = true;
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                for (long id : ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats) values.add("excludeChat:" + id);
            } else if (!(rule instanceof TLRPC.TL_privacyValueDisallowAll)) {
                values.add(rule.getClass().getName());
                hasAudience = true;
            }
        }
        if (!hasAudience) values.add("nobody");
        return values.toString();
    }
}
