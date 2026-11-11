package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/** The account's posts: a tap opens their comments, or their statistics. */
public class UMessageInstagramMediaActivity extends UMessageInstagramBaseActivity {

    public static final int MODE_COMMENTS = 0;
    public static final int MODE_STATS = 1;

    private final int mode;

    public UMessageInstagramMediaActivity(int mode) {
        this.mode = mode;
    }

    @Override
    protected CharSequence title() {
        return getString(mode == MODE_COMMENTS ? R.string.UMessageInstagramComments : R.string.UMessageInstagramPostStats);
    }

    @Override
    protected CharSequence emptyText() {
        return getString(R.string.UMessageInstagramMediaEmpty);
    }

    @Override
    protected void request(String cursor, UMessagePremiumController.Callback callback) {
        api().media(cursor, callback);
    }

    private static final class Row extends LinearLayout {
        BackupImageView thumb;
        TextView caption, counts;

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

        row.thumb = new BackupImageView(context);
        row.thumb.setRoundRadius(dp(8));
        row.addView(row.thumb, LayoutHelper.createLinear(64, 64, 0, 0, 12, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.caption = text(context, 15, Theme.key_windowBackgroundWhiteBlackText);
        row.caption.setMaxLines(2);
        row.caption.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(row.caption);

        row.counts = text(context, 13, Theme.key_windowBackgroundWhiteGrayText);
        row.counts.setSingleLine(true);
        row.counts.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(row.counts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        return row;
    }

    @Override
    protected void bindRow(View view, JSONObject item, int position) {
        final Row row = (Row) view;
        setRemoteImage(row.thumb, item.isNull("thumb") ? null : item.optString("thumb"), 8);
        final String caption = item.isNull("caption") ? "" : item.optString("caption");
        row.caption.setText(caption.isEmpty() ? getString(R.string.UMessageInstagramNoCaption) : caption);
        row.counts.setText(LocaleController.formatString(R.string.UMessageInstagramMediaCounts, item.optInt("likes"), item.optInt("comments"), formatTime(item)));
    }

    @Override
    protected void onRowClick(JSONObject item, View view) {
        if (mode == MODE_COMMENTS) {
            presentFragment(new UMessageInstagramCommentsActivity(item.optString("id")));
        } else {
            showStats(item);
        }
    }

    private void showStats(JSONObject item) {
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        api().mediaInsights(item.optString("id"), (r, error) -> {
            progress.dismiss();
            if (fragmentView == null) {
                return;
            }
            if (error != null || r == null) {
                BulletinFactory.of(this).createErrorBulletin(errorText(error)).show();
                return;
            }
            final String message = LocaleController.formatString(R.string.UMessageInstagramPostStatsText,
                r.optInt("reach"), r.optInt("views"), r.optInt("likes"), r.optInt("comments"), r.optInt("saves"), r.optInt("shares"));
            final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.UMessageInstagramPostStats))
                .setMessage(message)
                .setPositiveButton(getString(R.string.OK), null);
            final String permalink = item.isNull("permalink") ? "" : item.optString("permalink");
            if (!permalink.isEmpty()) {
                builder.setNegativeButton(getString(R.string.UMessageInstagramOpenPost), (d, w) -> Browser.openUrl(getParentActivity(), permalink));
            }
            showDialog(builder.create());
        });
    }
}
