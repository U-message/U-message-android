package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/** U message: messages kept after the sender deleted them and the earlier versions of edited messages. */
public class UMessageHistory {

    /** Belongs to the Telegram user on the slot (message ids are per user), not to the slot. */
    static SharedPreferences prefs(int account) {
        return UMessageLocalData.userPrefs(UMessageLocalData.FILE_HISTORY, account);
    }

    /** Private chats and basic groups share one id space, so they are keyed by 0; channels by -channelId. */
    private static long idSpace(TLRPC.Message message) {
        return message.peer_id != null && message.peer_id.channel_id != 0 ? -message.peer_id.channel_id : 0;
    }

    /* Deleted messages */

    public static boolean markDeleted(int account, long idSpace, ArrayList<Integer> ids) {
        synchronized (UMessageLocalData.LOCK) {
            if (ids == null || ids.isEmpty()) {
                return false;
            }
            if (!UMessageConfig.isSaveDeleted()) return false;
            SharedPreferences.Editor editor = prefs(account).edit();
            for (Integer id : ids) {
                editor.putBoolean("d_" + idSpace + "_" + id, true);
            }
            editor.apply();
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.umessageMessagesChanged));
            return true;
        }
    }

    public static boolean isDeleted(int account, MessageObject messageObject) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.SAVE_DELETED)) return false;
        if (messageObject != null && messageObject.umPreviewDeleted) {
            return true;
        }
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0 || !UMessageConfig.isSaveDeleted()) {
            return false;
        }
        return prefs(account).getBoolean("d_" + idSpace(messageObject.messageOwner) + "_" + messageObject.getId(), false);
    }

    /* Edited messages */

    private static String editKey(long dialogId, int id) {
        return "e_" + dialogId + "_" + id;
    }

    /**
     * Called before the edited message is written to the database: the storage queue runs in order,
     * so the row read here is still the previous version.
     */
    public static void recordEdit(int account, TLRPC.Message edited) {
        final long operationGeneration = UMessageLocalData.getGeneration();
        final long dialogId = MessageObject.getDialogId(edited);
        final int id = edited.id;
        final String newText = edited.message == null ? "" : edited.message;
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            if (!UMessageConfig.isSaveEdited() || operationGeneration != UMessageLocalData.getGeneration()) return;
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId + " AND mid = " + id + " LIMIT 1");
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        TLRPC.Message old = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        if (old != null && old.message != null && !TextUtils.equals(old.message, newText)) {
                            addVersion(account, dialogId, id, old.message, old.edit_date != 0 ? old.edit_date : old.date, operationGeneration);
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        });
    }

    private static void addVersion(int account, long dialogId, int id, String text, int date, long operationGeneration) throws Exception {
        synchronized (UMessageLocalData.LOCK) {
            if (!UMessageConfig.isSaveEdited() || operationGeneration != UMessageLocalData.getGeneration()) return;
            final SharedPreferences prefs = prefs(account);
            final String key = editKey(dialogId, id);
            JSONArray versions = new JSONArray(prefs.getString(key, "[]"));
            versions.put(new JSONObject().put("t", text).put("d", date));
            prefs.edit().putString(key, versions.toString()).apply();
        }
    }

    public static boolean hasEditHistory(int account, MessageObject messageObject) {
        if (!UMessageConfig.isSaveEdited()) return false;
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0) {
            return false;
        }
        return prefs(account).contains(editKey(messageObject.getDialogId(), messageObject.getId()));
    }

    private static JSONArray getVersions(int account, MessageObject messageObject) {
        if (!UMessageConfig.isSaveEdited()) return new JSONArray();
        try {
            return new JSONArray(prefs(account).getString(editKey(messageObject.getDialogId(), messageObject.getId()), "[]"));
        } catch (Exception e) {
            FileLog.e(e);
            return new JSONArray();
        }
    }

    /**
     * Shows (or hides again) the edit history right in the bubble, GitHub-diff style.
     * Returns false when that is not possible: no saved versions or not a text message.
     */
    public static boolean toggleInlineHistory(int account, MessageObject messageObject) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.SAVE_EDITED)) return false;
        if (messageObject == null || messageObject.type != MessageObject.TYPE_TEXT || messageObject.messageText == null) {
            return false;
        }
        if (messageObject.umTextBeforeHistory != null) {
            messageObject.messageText = messageObject.umTextBeforeHistory;
            messageObject.umTextBeforeHistory = null;
        } else {
            final JSONArray versions = getVersions(account, messageObject);
            if (versions.length() == 0) {
                return false;
            }
            final android.graphics.Paint.FontMetricsInt fontMetrics = org.telegram.ui.ActionBar.Theme.chat_msgTextPaint.getFontMetricsInt();
            final boolean dark = org.telegram.ui.ActionBar.Theme.isCurrentThemeDark();
            final int labelColor = org.telegram.ui.ActionBar.Theme.getColor(messageObject.isOutOwner() ? org.telegram.ui.ActionBar.Theme.key_chat_outTimeText : org.telegram.ui.ActionBar.Theme.key_chat_inTimeText);
            final JSONObject oldest = versions.optJSONObject(0);
            final boolean haveOriginal = oldest != null && oldest.optInt("d") == messageObject.messageOwner.date;
            final String currentText = messageObject.messageOwner.message == null ? "" : messageObject.messageOwner.message;
            final JSONObject newestSaved = versions.optJSONObject(versions.length() - 1);
            // GitHub-style: the message itself shows its last edit (removed words red and struck, added green)
            final SpannableStringBuilder text = new SpannableStringBuilder(Emoji.replaceEmoji(
                    diff(newestSaved == null ? "" : newestSaved.optString("t"), currentText, dark), fontMetrics, false));
            for (int i = versions.length() - 1; i >= 0; i--) {
                final JSONObject version = versions.optJSONObject(i);
                if (version == null) {
                    continue;
                }
                text.append("\n\n");
                final int labelStart = text.length();
                final String label = i == 0
                        ? LocaleController.getString(haveOriginal ? R.string.UMessageEditOriginal : R.string.UMessageEditOldest)
                        : LocaleController.formatString(R.string.UMessageEditVersion, haveOriginal ? i : i + 1);
                text.append("\u270E ").append(label).append(" · ").append(LocaleController.formatDateAudio(version.optInt("d"), true));
                text.setSpan(new android.text.style.RelativeSizeSpan(0.8f), labelStart, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new android.text.style.ForegroundColorSpan(labelColor), labelStart, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.append("\n");
                final JSONObject previous = i > 0 ? versions.optJSONObject(i - 1) : null;
                final CharSequence body = previous == null ? version.optString("t") : diff(previous.optString("t"), version.optString("t"), dark);
                text.append(Emoji.replaceEmoji(body, fontMetrics, false));
            }
            messageObject.umTextBeforeHistory = messageObject.messageText;
            messageObject.messageText = text;
        }
        TLRPC.User fromUser = messageObject.isFromUser() ? MessagesController.getInstance(account).getUser(messageObject.messageOwner.from_id.user_id) : null;
        messageObject.generateLayout(fromUser);
        messageObject.forceUpdate = true;
        return true;
    }

    public static void showEditHistory(BaseFragment fragment, MessageObject messageObject) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.SAVE_EDITED)) return;
        if (fragment == null || fragment.getParentActivity() == null || messageObject == null) {
            return;
        }
        if (getVersions(fragment.getCurrentAccount(), messageObject).length() == 0) {
            org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.UMessageEditHistoryEmpty)).show();
            return;
        }
        // oldest first; the live text is the last entry
        final ArrayList<String> texts = new ArrayList<>();
        final ArrayList<Integer> dates = new ArrayList<>();
        try {
            JSONArray versions = new JSONArray(prefs(fragment.getCurrentAccount()).getString(editKey(messageObject.getDialogId(), messageObject.getId()), "[]"));
            for (int i = 0; i < versions.length(); i++) {
                JSONObject version = versions.getJSONObject(i);
                texts.add(version.optString("t"));
                dates.add(version.optInt("d"));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        final TLRPC.Message current = messageObject.messageOwner;
        texts.add(current.message == null ? "" : current.message);
        dates.add(current.edit_date != 0 ? current.edit_date : current.date);
        // a stored version dated with the send time was never edited before: that is the original text
        final boolean haveOriginal = !dates.isEmpty() && dates.get(0) == current.date;

        final android.content.Context context = fragment.getParentActivity();
        final org.telegram.ui.ActionBar.Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final int textColor = org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack, rp);
        final int grayColor = org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextGray3, rp);
        final boolean dark = org.telegram.ui.ActionBar.Theme.isCurrentThemeDark();
        final int accent = 0xff3d8bf2;
        final int cardColor = dark ? 0x14ffffff : 0x0d000000;

        android.widget.LinearLayout list = new android.widget.LinearLayout(context);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), AndroidUtilities.dp(8));

        final int count = texts.size();
        for (int i = 0; i < count; i++) {
            final boolean isCurrent = i == count - 1;
            final String label;
            if (isCurrent) {
                label = LocaleController.getString(R.string.UMessageEditCurrent);
            } else if (i == 0) {
                label = LocaleController.getString(haveOriginal ? R.string.UMessageEditOriginal : R.string.UMessageEditOldest);
            } else {
                label = LocaleController.formatString(R.string.UMessageEditVersion, haveOriginal ? i : i + 1);
            }
            CharSequence body = i == 0 ? texts.get(0) : diff(texts.get(i - 1), texts.get(i), dark);
            list.addView(versionCard(context, label.toUpperCase(java.util.Locale.getDefault()) + "  ·  " + LocaleController.formatDateAudio(dates.get(i), true),
                    body, isCurrent ? accent : grayColor, textColor, isCurrent ? (accent & 0x00ffffff) | 0x1f000000 : cardColor,
                    i < count - 1),
                    org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT));
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(list);
        AlertDialog.Builder builder = new AlertDialog.Builder(context, rp);
        builder.setTitle(LocaleController.getString(R.string.UMessageEditHistory));
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        fragment.showDialog(builder.create());
    }

    /** One timeline step: a dot + connector on the left, a rounded card with label and text on the right. */
    private static android.view.View versionCard(android.content.Context context, String label, CharSequence body, int labelColor, int textColor, int cardColor, boolean connector) {
        final int dotColor = labelColor;
        android.widget.LinearLayout row = new android.widget.LinearLayout(context) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            {
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                float cx = AndroidUtilities.dp(6), cy = AndroidUtilities.dp(18);
                if (connector) {
                    paint.setColor(org.telegram.ui.ActionBar.Theme.multAlpha(dotColor, .35f));
                    paint.setStrokeWidth(AndroidUtilities.dp(2));
                    canvas.drawLine(cx, cy, cx, getHeight(), paint);
                }
                paint.setColor(dotColor);
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(5), paint);
            }
        };
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(AndroidUtilities.dp(22), 0, 0, AndroidUtilities.dp(12));

        android.widget.LinearLayout card = new android.widget.LinearLayout(context);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        card.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(14), cardColor));

        android.widget.TextView title = new android.widget.TextView(context);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        title.setTypeface(AndroidUtilities.bold());
        title.setLetterSpacing(0.04f);
        title.setTextColor(labelColor);
        title.setText(label);
        card.addView(title, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        android.widget.TextView text = new android.widget.TextView(context);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setTextColor(textColor);
        text.setTextIsSelectable(true);
        text.setText(Emoji.replaceEmoji(body, text.getPaint().getFontMetricsInt(), false));
        card.addView(text);

        row.addView(card, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT));
        return row;
    }

    /** Word-level diff of {@code before} -> {@code after}: inserted words green, removed words red and struck through. */
    private static CharSequence diff(String before, String after, boolean dark) {
        if (before == null) before = "";
        if (after == null) after = "";
        final java.util.List<String> a = tokens(before), b = tokens(after);
        final int n = a.size(), m = b.size();
        if ((long) n * m > 400_000L) {
            return after; // too long to diff cheaply: show the text as is
        }
        final int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        final int addBg = dark ? 0x4d2fb35c : 0x402fb35c, addFg = dark ? 0xff7fe0a0 : 0xff1e7a3c;
        final int delBg = dark ? 0x40ef5350 : 0x33ef5350, delFg = dark ? 0xffff8a87 : 0xffc62828;
        final SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                out.append(b.get(j));
                i++;
                j++;
            } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                appendStyled(out, b.get(j), addBg, addFg, false);
                j++;
            } else {
                appendStyled(out, a.get(i), delBg, delFg, true);
                i++;
            }
        }
        return out;
    }

    private static void appendStyled(SpannableStringBuilder out, String token, int bg, int fg, boolean strike) {
        final int start = out.length();
        out.append(token);
        if (token.trim().isEmpty()) {
            return; // whitespace-only change: no highlight blocks
        }
        out.setSpan(new android.text.style.BackgroundColorSpan(bg), start, out.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.ForegroundColorSpan(fg), start, out.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (strike) {
            out.setSpan(new android.text.style.StrikethroughSpan(), start, out.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Words and the whitespace between them as separate tokens, so the diff keeps the original spacing. */
    private static java.util.List<String> tokens(String s) {
        final ArrayList<String> out = new ArrayList<>();
        final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\s+|[^\\s]+").matcher(s);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }
}
