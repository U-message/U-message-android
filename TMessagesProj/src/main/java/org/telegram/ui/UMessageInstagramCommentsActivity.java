package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Comments of one post: reply, hide or show, delete with confirmation. */
public class UMessageInstagramCommentsActivity extends UMessageInstagramBaseActivity {

    private static final int TEXT_MAX = 2200;
    private static final String DEPTH = "_depth";
    private static final String PARENT = "_parent";

    private final String mediaId;
    private LinearLayout replyPanel;
    private TextView replyingTo;
    private Composer composer;
    private String replyTargetId;
    private boolean busy;

    public UMessageInstagramCommentsActivity(String mediaId) {
        this.mediaId = mediaId;
    }

    @Override
    protected CharSequence title() {
        return getString(R.string.UMessageInstagramComments);
    }

    @Override
    protected CharSequence emptyText() {
        return getString(R.string.UMessageInstagramCommentsEmpty);
    }

    @Override
    protected void request(String cursor, UMessagePremiumController.Callback callback) {
        api().comments(mediaId, cursor, callback);
    }

    /** {@code replies} is shown nested below its comment when the server sends the list itself. */
    @Override
    protected void addRows(JSONObject item, ArrayList<JSONObject> out) {
        out.add(item);
        final JSONArray replies = item.optJSONArray("replies");
        if (replies == null) {
            return;
        }
        for (int i = 0; i < replies.length(); i++) {
            final JSONObject reply = replies.optJSONObject(i);
            if (reply != null) {
                try {
                    reply.put(DEPTH, 1);
                    reply.put(PARENT, item.optString("id"));
                } catch (Exception ignore) {
                }
                out.add(reply);
            }
        }
    }

    @Override
    protected void addBottomViews(Context context, LinearLayout root) {
        replyPanel = new LinearLayout(context);
        replyPanel.setOrientation(LinearLayout.VERTICAL);
        replyPanel.setVisibility(View.GONE);
        root.addView(replyPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        replyPanel.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        replyingTo = text(context, 13, Theme.key_windowBackgroundWhiteGrayText);
        replyingTo.setSingleLine(true);
        replyingTo.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(replyingTo, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 16, 0, 8, 0));

        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon));
        close.setContentDescription(getString(R.string.Cancel));
        close.setOnClickListener(v -> closeReply());
        header.addView(close, LayoutHelper.createLinear(44, 36));

        composer = new Composer(context, R.string.UMessageInstagramReplyHint, TEXT_MAX, this::sendReply);
        replyPanel.addView(composer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static final class Row extends LinearLayout {
        TextView username, body, meta;

        Row(Context context) {
            super(context);
        }
    }

    @Override
    protected View createRow(Context context) {
        final Row row = new Row(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Theme.getSelectorDrawable(false));

        row.username = text(context, 14, Theme.key_windowBackgroundWhiteBlackText);
        row.username.setTypeface(AndroidUtilities.bold());
        row.username.setSingleLine(true);
        row.username.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(row.username);

        row.body = text(context, 15, Theme.key_windowBackgroundWhiteBlackText);
        row.addView(row.body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        row.meta = text(context, 12, Theme.key_windowBackgroundWhiteGrayText);
        row.addView(row.meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        return row;
    }

    private static String username(JSONObject item) {
        final String name = item.isNull("username") ? "" : item.optString("username");
        return name.isEmpty() ? getString(R.string.UMessageInstagramUnknownUser) : "@" + name;
    }

    @Override
    protected void bindRow(View view, JSONObject item, int position) {
        final Row row = (Row) view;
        final int depth = item.optInt(DEPTH, 0);
        row.setPadding(dp(depth > 0 ? 40 : 16), dp(9), dp(16), dp(9));
        final boolean hidden = item.optBoolean("hidden");
        row.username.setText(username(item));
        row.body.setText(item.isNull("text") ? "" : item.optString("text"));
        row.body.setAlpha(hidden ? 0.5f : 1f);

        final StringBuilder meta = new StringBuilder(formatTime(item));
        if (hidden) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(getString(R.string.UMessageInstagramCommentHidden));
        }
        // the server may send the count instead of the list
        final int replyCount = item.opt("replies") instanceof Number ? item.optInt("replies") : 0;
        if (replyCount > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(LocaleController.formatString(R.string.UMessageInstagramReplyCount, replyCount));
        }
        row.meta.setText(meta);
    }

    @Override
    protected void onRowClick(JSONObject item, View view) {
        if (busy) {
            return;
        }
        final boolean hidden = item.optBoolean("hidden");
        final CharSequence[] actions = {
            getString(R.string.UMessageInstagramCommentReply),
            getString(hidden ? R.string.UMessageInstagramCommentShow : R.string.UMessageInstagramCommentHide),
            getString(R.string.UMessageInstagramCommentDelete)
        };
        final int[] icons = {R.drawable.menu_reply, hidden ? R.drawable.msg_stories_myhide : R.drawable.msg_stories_stealth, R.drawable.msg_delete};
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(username(item))
            .setItems(actions, icons, (d, which) -> {
                if (which == 0) {
                    openReply(item);
                } else if (which == 1) {
                    toggleHidden(item);
                } else {
                    confirmDelete(item);
                }
            })
            .create());
    }

    private void openReply(JSONObject item) {
        // Instagram threads replies under the top-level comment only
        replyTargetId = item.has(PARENT) ? item.optString(PARENT) : item.optString("id");
        replyingTo.setText(LocaleController.formatString(R.string.UMessageInstagramReplyingTo, username(item)));
        replyPanel.setVisibility(View.VISIBLE);
        composer.editText.requestFocus();
        AndroidUtilities.showKeyboard(composer.editText);
    }

    private void closeReply() {
        replyTargetId = null;
        composer.editText.setText("");
        replyPanel.setVisibility(View.GONE);
        AndroidUtilities.hideKeyboard(composer.editText);
    }

    private void sendReply() {
        final String message = composer.textTrimmed();
        if (busy || message.isEmpty() || replyTargetId == null) {
            return;
        }
        busy = true;
        composer.sendButton.setAlpha(0.4f);
        api().commentReply(replyTargetId, message, (r, error) -> {
            busy = false;
            if (fragmentView == null) {
                return;
            }
            composer.sendButton.setAlpha(1f);
            if (error != null) {
                BulletinFactory.of(this).createErrorBulletin(errorText(error)).show();
                return;
            }
            closeReply();
            reload();
        });
    }

    private void toggleHidden(JSONObject item) {
        final boolean hide = !item.optBoolean("hidden");
        busy = true;
        api().commentHide(item.optString("id"), hide, (r, error) -> {
            busy = false;
            if (fragmentView == null) {
                return;
            }
            if (error != null) {
                BulletinFactory.of(this).createErrorBulletin(errorText(error)).show();
                return;
            }
            try {
                item.put("hidden", hide);
            } catch (Exception ignore) {
            }
            listView.getAdapter().notifyDataSetChanged();
        });
    }

    private void confirmDelete(JSONObject item) {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.UMessageInstagramCommentDelete))
            .setMessage(LocaleController.formatString(R.string.UMessageInstagramCommentDeleteText, username(item)))
            .setPositiveButton(getString(R.string.Delete), (d, w) -> delete(item))
            .setNegativeButton(getString(R.string.Cancel), null)
            .makeRed(AlertDialog.BUTTON_POSITIVE)
            .create());
    }

    private void delete(JSONObject item) {
        busy = true;
        api().commentDelete(item.optString("id"), (r, error) -> {
            busy = false;
            if (fragmentView == null) {
                return;
            }
            if (error != null) {
                BulletinFactory.of(this).createErrorBulletin(errorText(error)).show();
                return;
            }
            // reload: deleting a comment also takes its replies with it
            reload();
        });
    }
}
