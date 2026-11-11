package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/** Instagram Direct: the conversation list of the connected account. */
public class UMessageInstagramInboxActivity extends UMessageInstagramBaseActivity {

    private int lastUnread = -1;
    private final Runnable statusListener = () -> {
        // a new message arrived while this list is open
        final int unread = api().getUnread();
        if (lastUnread != -1 && unread != lastUnread && fragmentView != null) {
            reload();
        }
        lastUnread = unread;
    };

    @Override
    public boolean onFragmentCreate() {
        api().addListener(statusListener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        api().removeListener(statusListener);
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence title() {
        return getString(R.string.UMessageInstagramDirect);
    }

    @Override
    protected CharSequence emptyText() {
        return getString(R.string.UMessageInstagramDirectEmpty);
    }

    @Override
    protected void request(String cursor, UMessagePremiumController.Callback callback) {
        api().conversations(cursor, callback);
    }

    private static final class Row extends LinearLayout {
        BackupImageView avatar;
        TextView name, last, time, badge;

        Row(Context context) {
            super(context);
        }
    }

    @Override
    protected View createRow(Context context) {
        final Row row = new Row(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackground(Theme.getSelectorDrawable(false));

        row.avatar = new BackupImageView(context);
        row.avatar.setRoundRadius(dp(26));
        row.addView(row.avatar, LayoutHelper.createLinear(52, 52, 0, 0, 12, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.name = text(context, 16, Theme.key_windowBackgroundWhiteBlackText);
        row.name.setTypeface(AndroidUtilities.bold());
        row.name.setSingleLine(true);
        row.name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(row.name);

        row.last = text(context, 14, Theme.key_windowBackgroundWhiteGrayText);
        row.last.setSingleLine(true);
        row.last.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(row.last, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        final LinearLayout end = new LinearLayout(context);
        end.setOrientation(LinearLayout.VERTICAL);
        end.setGravity(Gravity.END);
        row.addView(end, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));

        row.time = text(context, 12, Theme.key_windowBackgroundWhiteGrayText);
        end.addView(row.time);

        row.badge = text(context, 12, Theme.key_chats_unreadCounterText);
        row.badge.setGravity(Gravity.CENTER);
        row.badge.setMinWidth(dp(20));
        row.badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(10));
        pill.setColor(getThemedColor(Theme.key_chats_unreadCounter));
        row.badge.setBackground(pill);
        end.addView(row.badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 0, 4, 0, 0));
        return row;
    }

    private static String displayName(JSONObject item) {
        final String name = item.isNull("name") ? "" : item.optString("name");
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        final String username = item.isNull("username") ? "" : item.optString("username");
        return TextUtils.isEmpty(username) ? getString(R.string.UMessageInstagramUnknownUser) : "@" + username;
    }

    @Override
    protected void bindRow(View view, JSONObject item, int position) {
        final Row row = (Row) view;
        setRemoteImage(row.avatar, item.isNull("picture") ? null : item.optString("picture"), 26);
        row.name.setText(displayName(item));
        row.last.setText(item.isNull("lastText") ? "" : item.optString("lastText"));
        final long time = org.telegram.messenger.UMessageInstagramBusiness.timeSeconds(item, "lastTime");
        row.time.setText(time == 0 ? "" : org.telegram.messenger.LocaleController.stringForMessageListDate(time));
        final int unread = item.optInt("unread", 0);
        row.badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        row.badge.setText(String.valueOf(unread));
    }

    @Override
    protected void onRowClick(JSONObject item, View view) {
        presentFragment(new UMessageInstagramThreadActivity(item.optString("id"), item.optString("userId"), displayName(item)));
    }
}
