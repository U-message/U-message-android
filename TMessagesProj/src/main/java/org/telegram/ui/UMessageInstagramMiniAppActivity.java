package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagramFollowers;
import org.telegram.messenger.UMessageInstagramFollowers.Person;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.UMessageModernList;

import java.util.ArrayList;

/**
 * Instagram inside U message, for any account (personal too): Direct and notifications are instagram.com itself in a
 * WebView (the user logs in on Instagram's own page, the password never reaches us); the Followers tab is native and
 * shows who unfollowed, found by comparing follower lists stored on this phone.
 */
public class UMessageInstagramMiniAppActivity extends BaseFragment {

    private static final int TAB_DIRECT = 0;
    private static final int TAB_ACTIVITY = 1;
    private static final int TAB_FOLLOWERS = 2;
    private static final int MENU_RELOAD = 1;
    private static final int MENU_BUSINESS = 2;
    private static final int MENU_LOGOUT = 3;
    private static final int FILE_REQUEST = 7301;
    private static final int[] INSTAGRAM_COLORS = {0xFFD62976, 0xFF962FBF, 0xFF4F5BD5};

    private WebView webView;
    private RadialProgressView webProgress;
    private ScrollView followersScroll;
    private LinearLayout followersPage;
    private final TextView[] tabViews = new TextView[3];
    private int tab = -1;
    private String loadedUrl;
    private ValueCallback<Uri[]> fileCallback;

    private boolean checking;
    private String progressText;

    @Override
    public boolean isLightStatusBar() {
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundGray)) > 0.5f;
    }

    @SuppressLint("SetJavaScriptEnabled")
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
        actionBar.setTitle(getString(R.string.UMessageInstagramMiniApp));
        final ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_RELOAD, R.drawable.msg_retry, getString(R.string.UMessageInstagramReload));
        other.addSubItem(MENU_BUSINESS, R.drawable.msg_photos, getString(R.string.UMessageInstagramBusiness));
        other.addSubItem(MENU_LOGOUT, R.drawable.msg_leave, getString(R.string.UMessageInstagramLogout));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_RELOAD) {
                    if (tab == TAB_FOLLOWERS) renderFollowers(); else if (webView != null) webView.reload();
                } else if (id == MENU_BUSINESS) {
                    presentFragment(new UMessageInstagramBusinessActivity());
                } else if (id == MENU_LOGOUT) {
                    UMessageInstagramFollowers.logout();
                    loadedUrl = null;
                    selectTab(tab < 0 ? TAB_DIRECT : tab, true);
                }
            }
        });

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        // segmented control: Direct | Notifications | Followers
        final LinearLayout segment = new LinearLayout(context);
        segment.setPadding(dp(2), dp(2), dp(2), dp(2));
        final GradientDrawable segBg = new GradientDrawable();
        segBg.setCornerRadius(dp(10));
        segBg.setColor(ColorUtils.setAlphaComponent(foreground, 0x14));
        segment.setBackground(segBg);
        final int[] titles = {R.string.UMessageInstagramTabDirect, R.string.UMessageInstagramTabActivity, R.string.UMessageInstagramTabFollowers};
        for (int i = 0; i < 3; i++) {
            final TextView t = new TextView(context);
            t.setText(getString(titles[i]));
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTypeface(AndroidUtilities.bold());
            final int index = i;
            t.setOnClickListener(v -> selectTab(index, false));
            tabViews[i] = t;
            segment.addView(t, LayoutHelper.createLinear(0, 32, 1f));
        }
        root.addView(segment, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 8));

        final FrameLayout content = new FrameLayout(context);
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        webView = new WebView(context);
        final WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        // a plain mobile browser: instagram.com refuses or nags embedded WebViews that say "wv"
        final String ua = s.getUserAgentString().replace("; wv", "").replaceAll(" Version/\\d+(\\.\\d+)*", "");
        s.setUserAgentString(ua);
        UMessageInstagramFollowers.setUserAgent(ua);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                final Uri uri = request.getUrl();
                final String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
                final String scheme = uri.getScheme() != null ? uri.getScheme() : "";
                if ((scheme.equals("https") || scheme.equals("http")) && (host.endsWith("instagram.com") || host.endsWith("facebook.com")
                        || host.endsWith("cdninstagram.com") || host.endsWith("fbcdn.net"))) {
                    return false;
                }
                // other links and app intents leave the page
                try {
                    final Intent intent = scheme.equals("intent") ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, uri);
                    intent.addCategory(Intent.CATEGORY_BROWSABLE);
                    intent.setComponent(null);
                    intent.setSelector(null);
                    if (getParentActivity() != null) getParentActivity().startActivity(intent);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (webProgress != null) webProgress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (webProgress != null) webProgress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                    return true;
                } catch (Throwable e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        content.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        webProgress = new RadialProgressView(context);
        webProgress.setProgressColor(INSTAGRAM_COLORS[1]);
        content.addView(webProgress, LayoutHelper.createFrame(44, 44, Gravity.CENTER));

        followersScroll = new ScrollView(context);
        followersScroll.setFillViewport(true);
        followersPage = new LinearLayout(context);
        followersPage.setOrientation(LinearLayout.VERTICAL);
        followersPage.setPadding(0, dp(4), 0, dp(32));
        followersScroll.addView(followersPage, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(followersScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        selectTab(UMessageInstagramFollowers.userId() == null ? TAB_DIRECT : TAB_ACTIVITY, true);
        return fragmentView;
    }

    private void selectTab(int index, boolean force) {
        if (index == tab && !force) {
            if (index != TAB_FOLLOWERS && webView != null) webView.scrollTo(0, 0);
            return;
        }
        tab = index;
        final int fg = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        for (int i = 0; i < 3; i++) {
            final TextView t = tabViews[i];
            if (i == index) {
                final GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(8));
                bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
                t.setBackground(bg);
                t.setTextColor(fg);
            } else {
                t.setBackground(null);
                t.setTextColor(ColorUtils.setAlphaComponent(fg, 0x99));
            }
        }
        final boolean followers = index == TAB_FOLLOWERS;
        followersScroll.setVisibility(followers ? View.VISIBLE : View.GONE);
        webView.setVisibility(followers ? View.GONE : View.VISIBLE);
        if (followers) {
            webProgress.setVisibility(View.GONE);
            renderFollowers();
            return;
        }
        final String url = UMessageInstagramFollowers.userId() == null ? UMessageInstagramFollowers.ORIGIN + "/accounts/login/"
                : UMessageInstagramFollowers.ORIGIN + (index == TAB_DIRECT ? "/direct/inbox/" : "/notifications/");
        if (force || !url.equals(loadedUrl)) {
            loadedUrl = url;
            webView.loadUrl(url);
        }
    }

    private void openInWeb(String url) {
        loadedUrl = url;
        selectTab(TAB_DIRECT, false);
        webView.loadUrl(url);
    }

    // --- Followers tab ---

    private void renderFollowers() {
        if (followersPage == null) return;
        final Context context = followersPage.getContext();
        followersPage.removeAllViews();
        final String uid = UMessageInstagramFollowers.userId();
        if (uid == null) {
            followersPage.addView(UMessageModernList.footer(context, getString(R.string.UMessageInstagramLoginFirst), getResourceProvider()));
            final LinearLayout card = UMessageModernList.card(context, getResourceProvider());
            final View login = UMessageModernList.row(context, getResourceProvider(), UMessageModernList.KIND_NAV, R.drawable.msg_photos,
                    UMessageModernList.PURPLE, getString(R.string.UMessageInstagramLogin), null, null, false, false);
            login.setOnClickListener(v -> openInWeb(UMessageInstagramFollowers.ORIGIN + "/accounts/login/"));
            card.addView(login);
            followersPage.addView(card, UMessageModernList.cardParams());
            return;
        }

        final long[] last = UMessageInstagramFollowers.lastCheck(uid);
        final LinearLayout card = UMessageModernList.card(context, getResourceProvider());
        final String subtitle = checking ? progressText
                : last == null ? null
                : formatString(R.string.UMessageInstagramLastCheck, LocaleController.formatNumber(last[0], ' '), LocaleController.formatDateAudio(last[1], true));
        final View check = UMessageModernList.row(context, getResourceProvider(), UMessageModernList.KIND_NAV, R.drawable.msg_retry,
                UMessageModernList.PINK, getString(checking ? R.string.UMessageInstagramCheckingTitle : R.string.UMessageInstagramCheck), subtitle, null, false, false);
        check.setOnClickListener(v -> startCheck(uid));
        check.setEnabled(!checking);
        card.addView(check);
        followersPage.addView(card, UMessageModernList.cardParams());
        followersPage.addView(UMessageModernList.footer(context, getString(R.string.UMessageInstagramCheckNote), getResourceProvider()));

        final ArrayList<Person> events = UMessageInstagramFollowers.events(uid);
        addPeople(context, getString(R.string.UMessageInstagramUnfollowed), events, true);
        addPeople(context, getString(R.string.UMessageInstagramNewFollowers), events, false);
        if (events.isEmpty() && last != null) {
            followersPage.addView(UMessageModernList.footer(context, getString(R.string.UMessageInstagramNoChanges), getResourceProvider()));
        }
    }

    private void addPeople(Context context, String title, ArrayList<Person> events, boolean lost) {
        LinearLayout card = null;
        for (Person p : events) {
            if (p.lost != lost) continue;
            if (card == null) {
                followersPage.addView(UMessageModernList.header(context, title, getResourceProvider()));
                card = UMessageModernList.card(context, getResourceProvider());
                followersPage.addView(card, UMessageModernList.cardParams());
            } else {
                final View divider = new View(context);
                divider.setBackgroundColor(Theme.getColor(Theme.key_divider, getResourceProvider()));
                card.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 68, 0, 0, 0));
            }
            card.addView(personRow(context, p));
        }
    }

    private View personRow(Context context, Person p) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(getThemedColor(Theme.key_listSelector), false));
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(20));
        final GradientDrawable placeholder = new GradientDrawable(GradientDrawable.Orientation.TL_BR, INSTAGRAM_COLORS);
        placeholder.setShape(GradientDrawable.OVAL);
        if (!TextUtils.isEmpty(p.photo)) avatar.setImage(p.photo, "40_40", placeholder); else avatar.setImageDrawable(placeholder);
        row.addView(avatar, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 8, 0, 8));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(!TextUtils.isEmpty(p.name) ? p.name : p.username);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        texts.addView(name);
        final TextView sub = new TextView(context);
        sub.setText("@" + p.username + " · " + LocaleController.formatDateAudio(p.time, true));
        sub.setTextSize(13);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        texts.addView(sub);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 68, 0, 16, 0));
        if (!TextUtils.isEmpty(p.username)) {
            row.setOnClickListener(v -> openInWeb(UMessageInstagramFollowers.ORIGIN + "/" + Uri.encode(p.username) + "/"));
        }
        return row;
    }

    private void startCheck(String uid) {
        if (checking || getParentActivity() == null) return;
        if (!UMessageInstagramFollowers.canCheck(uid)) {
            showAlert(getString(R.string.UMessageInstagramCheckLimit));
            return;
        }
        checking = true;
        progressText = getString(R.string.UMessageInstagramPreparing);
        renderFollowers();
        new Thread(() -> {
            final int total = UMessageInstagramFollowers.followerCount(uid);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView == null) return;
                if (total > UMessageInstagramFollowers.BIG_ACCOUNT) {
                    new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                            .setTitle(getString(R.string.UMessageInstagramTabFollowers))
                            .setMessage(formatString(R.string.UMessageInstagramBigAccount, LocaleController.formatNumber(total, ' ')))
                            .setPositiveButton(getString(R.string.UMessageInstagramContinue), (d, w) -> runCheck(uid))
                            .setNegativeButton(getString(R.string.Cancel), (d, w) -> { checking = false; renderFollowers(); })
                            .setOnCancelListener(d -> { checking = false; renderFollowers(); })
                            .show();
                } else {
                    runCheck(uid);
                }
            });
        }, "um-ig-count").start();
    }

    private void runCheck(String uid) {
        UMessageInstagramFollowers.check(uid, (loaded, total) -> {
            progressText = total > 0 ? formatString(R.string.UMessageInstagramChecking, loaded, total)
                    : formatString(R.string.UMessageInstagramCheckingNoTotal, loaded);
            if (fragmentView != null && tab == TAB_FOLLOWERS) renderFollowers();
        }, (error, baseline, lost, gained) -> {
            checking = false;
            if (fragmentView == null) return;
            renderFollowers();
            if (error == UMessageInstagramFollowers.ERROR_LOGIN) {
                showAlert(getString(R.string.UMessageInstagramLoginFirst));
            } else if (error == UMessageInstagramFollowers.ERROR_LIMITED) {
                showAlert(getString(R.string.UMessageInstagramCheckBlocked));
            } else if (error != UMessageInstagramFollowers.ERROR_NONE) {
                showAlert(getString(R.string.UMessageInstagramCheckFailed));
            } else if (baseline) {
                showAlert(getString(R.string.UMessageInstagramBaseline));
            } else {
                showAlert(formatString(R.string.UMessageInstagramCheckResult, lost, gained));
            }
        });
    }

    private void showAlert(String text) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.UMessageInstagramTabFollowers))
                .setMessage(text)
                .setPositiveButton(getString(R.string.OK), null)
                .show();
    }

    // --- lifecycle ---

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode == Activity.RESULT_OK ? resultCode : Activity.RESULT_CANCELED, data));
            fileCallback = null;
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (tab != TAB_FOLLOWERS && webView != null && webView.canGoBack()) {
            if (invoked) webView.goBack();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return tab == TAB_FOLLOWERS || webView == null || !webView.canGoBack();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onFragmentDestroy();
    }
}
