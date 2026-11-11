package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagramBusiness;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;

/** One Instagram Direct conversation. Replies are possible only inside Meta's 24-hour window. */
public class UMessageInstagramThreadActivity extends UMessageInstagramBaseActivity {

    private static final int TEXT_MAX = 1000;
    private static final long REFRESH_MS = 30 * 1000;

    private final String conversationId;
    private final String recipientId;
    private final String name;

    private Composer composer;
    private TextView windowNotice;
    private boolean sending;
    /** Reply deadline in unix seconds; 0 when the server did not say. */
    private long canReplyUntil;
    private boolean windowClosed;
    private int localId;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!sending && !loading) {
                reload();
            }
            AndroidUtilities.runOnUIThread(this, REFRESH_MS);
        }
    };

    public UMessageInstagramThreadActivity(String conversationId, String recipientId, String name) {
        this.conversationId = conversationId;
        this.recipientId = recipientId;
        this.name = name;
    }

    @Override
    protected CharSequence title() {
        return name;
    }

    @Override
    protected CharSequence emptyText() {
        return getString(R.string.UMessageInstagramThreadEmpty);
    }

    @Override
    protected boolean reversed() {
        return true;
    }

    @Override
    protected boolean mergeOnReload() {
        return true;
    }

    @Override
    protected void request(String cursor, UMessagePremiumController.Callback callback) {
        api().messages(conversationId, cursor, callback);
    }

    @Override
    protected void sortRows(ArrayList<JSONObject> rows) {
        Collections.sort(rows, (a, b) -> Long.compare(UMessageInstagramBusiness.timeSeconds(b, "time"), UMessageInstagramBusiness.timeSeconds(a, "time")));
    }

    @Override
    protected void onPageLoaded(JSONObject result) {
        // this request's value wins: a new incoming message reopens the window
        // (older pages carry null here)
        if (!result.isNull("canReplyUntil")) {
            canReplyUntil = UMessageInstagramBusiness.timeSeconds(result, "canReplyUntil");
            windowClosed = canReplyUntil != 0 && canReplyUntil <= System.currentTimeMillis() / 1000;
        }
        updateWindow();
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.cancelRunOnUIThread(refresh);
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(refresh);
        super.onFragmentDestroy();
    }

    @Override
    protected void addBottomViews(Context context, LinearLayout root) {
        windowNotice = text(context, 13, Theme.key_windowBackgroundWhiteGrayText);
        windowNotice.setGravity(Gravity.CENTER);
        windowNotice.setPadding(dp(16), dp(10), dp(16), dp(10));
        windowNotice.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        windowNotice.setVisibility(View.GONE);
        root.addView(windowNotice, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        composer = new Composer(context, R.string.UMessageInstagramMessageHint, TEXT_MAX, this::send);
        root.addView(composer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateWindow();
    }

    private void updateWindow() {
        if (windowNotice == null) {
            return;
        }
        composer.setVisibility(windowClosed ? View.GONE : View.VISIBLE);
        if (windowClosed) {
            windowNotice.setText(getString(R.string.UMessageInstagramWindowClosed));
            windowNotice.setVisibility(View.VISIBLE);
            AndroidUtilities.hideKeyboard(composer.editText);
        } else if (canReplyUntil != 0) {
            final long left = Math.max(60, canReplyUntil - System.currentTimeMillis() / 1000);
            windowNotice.setText(LocaleController.formatString(R.string.UMessageInstagramWindowOpen, (int) (left / 3600), (int) (left % 3600 / 60)));
            windowNotice.setVisibility(View.VISIBLE);
        } else {
            windowNotice.setVisibility(View.GONE);
        }
    }

    private void send() {
        final String message = composer.textTrimmed();
        if (sending || message.isEmpty() || TextUtils.isEmpty(recipientId)) {
            return;
        }
        sending = true;
        composer.sendButton.setAlpha(0.4f);
        api().send(recipientId, message, (r, error) -> {
            sending = false;
            if (fragmentView == null) {
                return;
            }
            composer.sendButton.setAlpha(1f);
            if (UMessageInstagramBusiness.ERROR_WINDOW_CLOSED.equals(error)) {
                windowClosed = true;
                updateWindow();
                return;
            }
            if (error != null) {
                // the text stays in the field so it can be sent again
                BulletinFactory.of(this).createErrorBulletin(errorText(error)).show();
                return;
            }
            composer.editText.setText("");
            try {
                final JSONObject sent = new JSONObject();
                sent.put("id", r != null && !r.isNull("id") ? r.optString("id") : "local" + (++localId));
                sent.put("fromMe", true);
                sent.put("text", message);
                sent.put("time", System.currentTimeMillis() / 1000);
                rows.add(0, sent);
                listView.getAdapter().notifyDataSetChanged();
                listView.scrollToPosition(0);
            } catch (Exception ignore) {
            }
        });
    }

    private static final class Bubble extends FrameLayout {
        LinearLayout bubble;
        BackupImageView image;
        TextView message, time;

        Bubble(Context context) {
            super(context);
        }
    }

    @Override
    protected View createRow(Context context) {
        final Bubble row = new Bubble(context);
        row.setPadding(dp(10), dp(3), dp(10), dp(3));

        row.bubble = new LinearLayout(context);
        row.bubble.setOrientation(LinearLayout.VERTICAL);
        row.bubble.setPadding(dp(11), dp(7), dp(11), dp(6));
        row.addView(row.bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        row.image = new BackupImageView(context);
        row.image.setRoundRadius(dp(10));
        row.bubble.addView(row.image, LayoutHelper.createLinear(200, 200, 0, 0, 0, 4));

        row.message = text(context, 15, Theme.key_chat_messageTextIn);
        row.message.setMaxWidth((int) (AndroidUtilities.displaySize.x * 0.72f));
        row.bubble.addView(row.message);

        row.time = text(context, 11, Theme.key_windowBackgroundWhiteGrayText);
        row.bubble.addView(row.time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 0, 2, 0, 0));
        return row;
    }

    @Override
    protected void bindRow(View view, JSONObject item, int position) {
        final Bubble row = (Bubble) view;
        final boolean mine = item.optBoolean("fromMe");
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.bubble.getLayoutParams();
        lp.gravity = mine ? Gravity.END : Gravity.START;
        row.bubble.setLayoutParams(lp);

        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(getThemedColor(mine ? Theme.key_chat_outBubble : Theme.key_chat_inBubble));
        row.bubble.setBackground(background);
        row.message.setTextColor(getThemedColor(mine ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn));
        row.time.setTextColor(getThemedColor(mine ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText));

        final String text = item.isNull("text") ? "" : item.optString("text");
        final String mediaUrl = item.isNull("mediaUrl") ? "" : item.optString("mediaUrl");
        final String mediaType = item.isNull("mediaType") ? "" : item.optString("mediaType");
        final boolean isImage = !mediaUrl.isEmpty() && (mediaType.isEmpty() || mediaType.toLowerCase(java.util.Locale.ROOT).contains("image"));
        row.image.setVisibility(isImage ? View.VISIBLE : View.GONE);
        if (isImage) {
            setRemoteImage(row.image, mediaUrl, 10);
        }
        String shown = text;
        if (shown.isEmpty() && !mediaUrl.isEmpty() && !isImage) {
            shown = getString(R.string.UMessageInstagramOpenAttachment);
        } else if (shown.isEmpty() && mediaUrl.isEmpty()) {
            shown = getString(R.string.UMessageInstagramUnsupportedMessage);
        }
        row.message.setText(shown);
        row.message.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        row.time.setText(formatTime(item));
    }

    @Override
    protected void onRowClick(JSONObject item, View view) {
        final String mediaUrl = item.isNull("mediaUrl") ? "" : item.optString("mediaUrl");
        if (!mediaUrl.isEmpty()) {
            Browser.openUrl(getParentActivity(), mediaUrl);
        }
    }
}
