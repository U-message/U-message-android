package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagramBusiness;
import org.telegram.messenger.support.customtabs.CustomTabsIntent;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

/** Instagram for professional accounts, laid out like an iOS settings page: connect through Instagram's own login and open the tools. */
public class UMessageInstagramBusinessActivity extends BaseFragment {

    private static final int[] INSTAGRAM_COLORS = {0xFFD62976, 0xFF962FBF, 0xFF4F5BD5};
    private static final int CARD_RADIUS = 12;
    private static final int CONNECT_RETRIES = 3;
    private static final long CONNECT_RETRY_MS = 3000;

    private LinearLayout page;
    private boolean loading = true;
    private boolean connecting;
    private boolean connectPending;
    private int connectRetries;
    private String error;
    private JSONObject status;

    private final Runnable listener = this::render;

    private UMessageInstagramBusiness api() {
        return UMessageInstagramBusiness.getInstance(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        api().addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        api().removeListener(listener);
        AndroidUtilities.cancelRunOnUIThread(retry);
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundGray)) > 0.5f;
    }

    @Override
    public View createView(Context context) {
        final int background = getThemedColor(Theme.key_windowBackgroundGray);
        final int foreground = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setBackgroundColor(background);
        actionBar.setItemsColor(foreground, false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
        actionBar.setTitleColor(foreground);
        actionBar.setCastShadows(false);
        actionBar.setTitle(getString(R.string.UMessageInstagramBusiness));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(background);
        scroll.setFillViewport(true);
        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;
        render();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // also the way back from the login tab
        connectRetries = connectPending ? CONNECT_RETRIES : 0;
        refresh();
    }

    private final Runnable retry = this::refresh;

    private void refresh() {
        api().status((r, e) -> {
            if (fragmentView == null) {
                return;
            }
            loading = false;
            error = e;
            if (e == null) {
                status = r;
                if (isConnected()) {
                    connectPending = false;
                    connectRetries = 0;
                } else if (connectRetries > 0) {
                    // the app may still be confirming the login
                    connectRetries--;
                    AndroidUtilities.cancelRunOnUIThread(retry);
                    AndroidUtilities.runOnUIThread(retry, CONNECT_RETRY_MS);
                } else {
                    connectPending = false;
                }
            }
            render();
        });
    }

    private boolean isConnected() {
        return status != null && status.optBoolean("connected");
    }

    // ---------- Page ----------

    private void render() {
        if (page == null) {
            return;
        }
        final Context context = page.getContext();
        page.removeAllViews();
        if (isConnected()) {
            renderConnected(context);
        } else {
            renderConnect(context);
        }
    }

    private void renderConnect(Context context) {
        final LinearLayout hero = card(context);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));

        final TextView subtitle = label(context, 16, Theme.key_windowBackgroundWhiteBlackText);
        subtitle.setText(getString(R.string.UMessageInstagramHeroText));
        hero.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        if (UMessageInstagramBusiness.ERROR_NOT_CONFIGURED.equals(error)) {
            hero.addView(notice(context, getString(R.string.UMessageInstagramNotConfigured), Theme.key_windowBackgroundWhiteGrayText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            hero.addView(connectButton(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            if (error != null) {
                hero.addView(notice(context, UMessageInstagramBaseActivity.errorText(error), Theme.key_text_RedRegular), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            }
        }
        page.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 22));

        page.addView(footnote(context, getString(R.string.UMessageInstagramTools)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 6));

        final LinearLayout features = card(context);
        addRow(features, 0xFF007AFF, R.drawable.msg_discussion, R.string.UMessageInstagramDirect, R.string.UMessageInstagramDirectHint, null, null);
        addRow(features, 0xFF34C759, R.drawable.msg_discuss, R.string.UMessageInstagramComments, R.string.UMessageInstagramCommentsHint, null, null);
        addRow(features, 0xFFFF2D55, R.drawable.msg_addphoto, R.string.UMessageInstagramPublish, R.string.UMessageInstagramPublishHint, null, null);
        addRow(features, 0xFFFF9500, R.drawable.msg_stats, R.string.UMessageInstagramInsights, R.string.UMessageInstagramInsightsHint, null, null);
        addRow(features, 0xFFAF52DE, R.drawable.msg_channel, R.string.UMessageInstagramAutoPost, R.string.UMessageInstagramAutoPostHint, null, null);
        page.addView(features, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        page.addView(footnote(context, getString(R.string.UMessageInstagramConnectInfo)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        page.addView(footnote(context, getString(R.string.UMessageInstagramProfessionalInfo)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
    }

    private void renderConnected(Context context) {
        final JSONObject account = status.optJSONObject("account");
        if (account != null) {
            page.addView(profileCard(context, account), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 22));
        }

        final TextView tools = footnote(context, getString(R.string.UMessageInstagramTools));
        page.addView(tools, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 6));

        final LinearLayout card = card(context);
        final int unread = api().getUnread();
        addRow(card, 0xFF007AFF, R.drawable.msg_discussion, R.string.UMessageInstagramDirect, R.string.UMessageInstagramDirectHint, unread > 0 ? String.valueOf(unread) : null, () -> presentFragment(new UMessageInstagramInboxActivity()));
        addRow(card, 0xFF34C759, R.drawable.msg_discuss, R.string.UMessageInstagramComments, R.string.UMessageInstagramCommentsHint, null, () -> presentFragment(new UMessageInstagramMediaActivity(UMessageInstagramMediaActivity.MODE_COMMENTS)));
        addRow(card, 0xFFFF2D55, R.drawable.msg_addphoto, R.string.UMessageInstagramPublish, R.string.UMessageInstagramPublishHint, null, () -> presentFragment(new UMessageInstagramPublishActivity()));
        addRow(card, 0xFFFF9500, R.drawable.msg_stats, R.string.UMessageInstagramInsights, R.string.UMessageInstagramInsightsHint, null, () -> presentFragment(new UMessageInstagramInsightsActivity()));
        addRow(card, 0xFFAF52DE, R.drawable.msg_channel, R.string.UMessageInstagramAutoPost, api().isAutoPostEnabled() ? R.string.PasswordOn : R.string.PasswordOff, null, () -> presentFragment(new UMessageInstagramAutoPostActivity()));
        page.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout danger = card(context);
        final TextView disconnect = label(context, 16, Theme.key_text_RedRegular);
        disconnect.setGravity(Gravity.CENTER);
        disconnect.setText(getString(R.string.UMessageInstagramDisconnect));
        disconnect.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), CARD_RADIUS, CARD_RADIUS));
        disconnect.setOnClickListener(v -> confirmDisconnect());
        danger.addView(disconnect, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        page.addView(danger, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 0, 0));

        if (error != null) {
            page.addView(footnote(context, UMessageInstagramBaseActivity.errorText(error)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        }
    }

    // ---------- Parts ----------

    private View connectButton(Context context) {
        final FrameLayout button = new FrameLayout(context);
        final GradientDrawable background = instagramGradient(GradientDrawable.Orientation.LEFT_RIGHT);
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        button.setForeground(Theme.createRadSelectorDrawable(0x33FFFFFF, 14, 14));
        final boolean busy = loading || connecting;
        if (busy) {
            final RadialProgressView progress = new RadialProgressView(context);
            progress.setSize(dp(22));
            progress.setProgressColor(Color.WHITE);
            button.addView(progress, LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        } else {
            final TextView text = new TextView(context);
            text.setTextSize(17);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextColor(Color.WHITE);
            text.setText(getString(R.string.UMessageInstagramConnect));
            button.addView(text, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
            button.setOnClickListener(v -> connect());
        }
        return button;
    }

    private View profileCard(Context context, JSONObject account) {
        final LinearLayout card = card(context);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(20), dp(16), dp(16));

        final FrameLayout ring = new FrameLayout(context);
        final GradientDrawable ringBackground = instagramGradient(GradientDrawable.Orientation.BL_TR);
        ringBackground.setShape(GradientDrawable.OVAL);
        ring.setBackground(ringBackground);
        final View gap = new View(context);
        final GradientDrawable gapBackground = new GradientDrawable();
        gapBackground.setShape(GradientDrawable.OVAL);
        gapBackground.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        gap.setBackground(gapBackground);
        ring.addView(gap, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.NO_GRAVITY, 3, 3, 3, 3));
        final BackupImageView picture = new BackupImageView(context);
        picture.setRoundRadius(dp(38));
        final String url = account.isNull("picture") ? null : account.optString("picture");
        if (!TextUtils.isEmpty(url)) {
            picture.setImage(url, null, (android.graphics.drawable.Drawable) null);
        }
        ring.addView(picture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.NO_GRAVITY, 6, 6, 6, 6));
        card.addView(ring, LayoutHelper.createLinear(88, 88));

        final TextView username = label(context, 20, Theme.key_windowBackgroundWhiteBlackText);
        username.setTypeface(AndroidUtilities.bold());
        username.setGravity(Gravity.CENTER);
        username.setSingleLine(true);
        username.setEllipsize(TextUtils.TruncateAt.END);
        username.setText("@" + account.optString("username"));
        card.addView(username, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        final String name = account.isNull("name") ? null : account.optString("name");
        if (!TextUtils.isEmpty(name)) {
            final TextView nameView = label(context, 14, Theme.key_windowBackgroundWhiteGrayText);
            nameView.setGravity(Gravity.CENTER);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setText(name);
            card.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        final LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        addStat(stats, account.optInt("mediaCount"), R.string.UMessageInstagramPosts);
        addStat(stats, account.optInt("followers"), R.string.UMessageInstagramFollowers);
        card.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        return card;
    }

    private void addStat(LinearLayout parent, int value, int labelRes) {
        final Context context = parent.getContext();
        final LinearLayout stat = new LinearLayout(context);
        stat.setOrientation(LinearLayout.VERTICAL);
        stat.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView number = label(context, 18, Theme.key_windowBackgroundWhiteBlackText);
        number.setTypeface(AndroidUtilities.bold());
        number.setText(LocaleController.formatNumber(value, ' '));
        stat.addView(number);
        final TextView caption = label(context, 13, Theme.key_windowBackgroundWhiteGrayText);
        caption.setText(getString(labelRes));
        stat.addView(caption);
        parent.addView(stat, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
    }

    /** An iOS settings row: colored icon tile, title and hint, a badge and a chevron when it opens something. */
    private void addRow(LinearLayout card, int tileColor, int iconRes, int titleRes, int hintRes, String badge, Runnable onClick) {
        final Context context = card.getContext();
        if (card.getChildCount() > 0) {
            final View separator = new View(context);
            separator.setBackgroundColor(getThemedColor(Theme.key_divider));
            card.addView(separator, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, LocaleController.isRTL ? 0 : 58, 0, LocaleController.isRTL ? 58 : 0, 0));
        }
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(12), dp(10));

        final FrameLayout tile = new FrameLayout(context);
        final GradientDrawable tileBackground = new GradientDrawable();
        tileBackground.setColor(tileColor);
        tileBackground.setCornerRadius(dp(8));
        tile.setBackground(tileBackground);
        final ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        tile.addView(icon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        row.addView(tile, LayoutHelper.createLinear(32, 32, 0, 0, 12, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = label(context, 16, Theme.key_windowBackgroundWhiteBlackText);
        title.setText(getString(titleRes));
        texts.addView(title);
        final TextView hint = label(context, 13, Theme.key_windowBackgroundWhiteGrayText);
        hint.setText(getString(hintRes));
        texts.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        if (badge != null) {
            final TextView count = new TextView(context);
            count.setTextSize(13);
            count.setTypeface(AndroidUtilities.bold());
            count.setTextColor(Color.WHITE);
            count.setGravity(Gravity.CENTER);
            count.setMinWidth(dp(22));
            count.setPadding(dp(6), 0, dp(6), 0);
            final GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xFFFF3B30);
            pill.setCornerRadius(dp(11));
            count.setBackground(pill);
            count.setText(badge);
            row.addView(count, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 22, 8, 0, 0, 0));
        }
        if (onClick != null) {
            final ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.msg_arrowright);
            chevron.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            chevron.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            row.addView(chevron, LayoutHelper.createLinear(18, 18, 6, 0, 0, 0));
            row.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), CARD_RADIUS, CARD_RADIUS));
            row.setOnClickListener(v -> onClick.run());
        }
        card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private LinearLayout card(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable background = new GradientDrawable();
        background.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        background.setCornerRadius(dp(CARD_RADIUS));
        card.setBackground(background);
        card.setClipToOutline(true);
        return card;
    }

    private View notice(Context context, CharSequence message, int colorKey) {
        final TextView text = label(context, 14, colorKey);
        text.setText(message);
        text.setPadding(dp(12), dp(10), dp(12), dp(10));
        final GradientDrawable background = new GradientDrawable();
        background.setColor(ColorUtils.setAlphaComponent(getThemedColor(colorKey), 0x1A));
        background.setCornerRadius(dp(10));
        text.setBackground(background);
        return text;
    }

    private TextView footnote(Context context, CharSequence message) {
        final TextView text = label(context, 13, Theme.key_windowBackgroundWhiteGrayText);
        text.setText(message);
        return text;
    }

    private TextView label(Context context, int sizeDp, int colorKey) {
        final TextView text = new TextView(context);
        text.setTextSize(sizeDp);
        text.setTextColor(getThemedColor(colorKey));
        return text;
    }

    private static GradientDrawable instagramGradient(GradientDrawable.Orientation orientation) {
        return new GradientDrawable(orientation, INSTAGRAM_COLORS);
    }

    // ---------- Actions ----------

    private void connect() {
        if (connecting) {
            return;
        }
        connecting = true;
        render();
        api().connectUrl((r, e) -> {
            connecting = false;
            if (fragmentView == null) {
                return;
            }
            final String url = r == null || r.isNull("url") ? null : r.optString("url");
            if (e != null || TextUtils.isEmpty(url)) {
                error = e == null ? "error" : e;
                render();
                return;
            }
            error = null;
            connectPending = true;
            openCustomTab(url);
            render();
        });
    }

    /** Always a Custom Tab, never the in-app WebView: the login stays in the user's browser, cookies included. */
    private void openCustomTab(String url) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final Uri uri = Uri.parse(url);
        try {
            final CustomTabsIntent intent = new CustomTabsIntent.Builder()
                .setToolbarColor(Theme.getColor(Theme.key_actionBarBrowser))
                .setShowTitle(true)
                .build();
            intent.launchUrl(context, uri);
        } catch (Exception e) {
            FileLog.e(e);
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e2) {
                connectPending = false;
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.ErrorOccurred)).show();
            }
        }
    }

    private void confirmDisconnect() {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.UMessageInstagramDisconnect))
            .setMessage(getString(R.string.UMessageInstagramDisconnectText))
            .setPositiveButton(getString(R.string.UMessageInstagramDisconnectConfirm), (d, w) -> disconnect())
            .setNegativeButton(getString(R.string.Cancel), null)
            .makeRed(AlertDialog.BUTTON_POSITIVE)
            .create());
    }

    private void disconnect() {
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        api().disconnect((r, e) -> {
            progress.dismiss();
            if (fragmentView == null) {
                return;
            }
            if (e != null) {
                BulletinFactory.of(this).createErrorBulletin(UMessageInstagramBaseActivity.errorText(e)).show();
                return;
            }
            status = null;
            error = null;
            render();
        });
    }
}
