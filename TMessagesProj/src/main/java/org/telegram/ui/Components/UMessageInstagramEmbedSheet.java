package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagram;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Shows Instagram's official public embed page of one post or Reel. The WebView only ever stays on that
 * page: it has no file or content access, and anything else the user taps opens outside the app.
 */
public class UMessageInstagramEmbedSheet extends BottomSheet {

    private final UMessageInstagram.Embed embed;
    private final WebView webView;
    private final RadialProgressView progressView;
    private final TextView errorView;
    private boolean destroyed;

    /** Falls back to the Instagram app or the web when the system WebView cannot be created. */
    public static void show(BaseFragment fragment, UMessageInstagram.Embed embed, Theme.ResourcesProvider resourcesProvider) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        try {
            fragment.showDialog(new UMessageInstagramEmbedSheet(context, embed, resourcesProvider));
        } catch (Throwable t) {
            FileLog.e(t);
            UMessageInstagram.openExternally(context, embed.pageUrl());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private UMessageInstagramEmbedSheet(Context context, UMessageInstagram.Embed embed, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.embed = embed;
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final FrameLayout header = new FrameLayout(context);
        final TextView title = new TextView(context);
        title.setText("Instagram");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 20, 0, 56, 0));
        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN));
        close.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setContentDescription(getString(R.string.Close));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final FrameLayout content = new FrameLayout(context);
        webView = new WebView(context) {
            @Override
            protected void onAttachedToWindow() {
                AndroidUtilities.checkAndroidTheme(context, true);
                super.onAttachedToWindow();
            }

            @Override
            protected void onDetachedFromWindow() {
                AndroidUtilities.checkAndroidTheme(context, false);
                super.onDetachedFromWindow();
            }
        };
        lockDown(webView);
        webView.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
        webView.setWebViewClient(new EmbedClient());
        content.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressView = new RadialProgressView(context, resourcesProvider);
        progressView.setSize(dp(36));
        content.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        errorView = new TextView(context);
        errorView.setText(getString(R.string.UMessageInstagramEmbedError));
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorView.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        content.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        final int height = (int) Math.min(AndroidUtilities.displaySize.y * .72f, dp(620));
        layout.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));

        final ButtonWithCounterView open = new ButtonWithCounterView(context, resourcesProvider).setRound();
        open.setText(getString(R.string.UMessageInstagramOpenApp), false);
        open.setOnClickListener(v -> {
            UMessageInstagram.openExternally(context, embed.pageUrl());
            dismiss();
        });
        layout.addView(open, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 12));

        setCustomView(layout);
        webView.loadUrl(embed.embedUrl());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void lockDown(WebView view) {
        final WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true); // the official embed page needs it; no JS interface is ever added
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
    }

    private final class EmbedClient extends WebViewClient {
        private boolean isEmbedPage(Uri uri) {
            return "https".equals(uri.getScheme()) && "www.instagram.com".equals(uri.getHost()) && uri.getPort() == -1 && embed.embedPath().equals(uri.getPath());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            final Uri uri = request.getUrl();
            if (!request.isForMainFrame()) {
                return !"https".equals(uri.getScheme());
            }
            if (isEmbedPage(uri)) {
                return false;
            }
            if (request.hasGesture()) {
                openOutside(uri);
            } else {
                showError(); // a redirect away from the embed (login wall): never shown in-app
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!destroyed) {
                progressView.setVisibility(View.GONE);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showError();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            destroyWebView();
            dismiss();
            return true;
        }
    }

    private void openOutside(Uri uri) {
        final String scheme = uri.getScheme();
        final String host = uri.getHost();
        if (!"https".equals(scheme) && !"http".equals(scheme) || TextUtils.isEmpty(host)) {
            return; // intent:, instagram: and friends are not started from web content
        }
        if (host.equals("instagram.com") || host.endsWith(".instagram.com")) {
            UMessageInstagram.openExternally(getContext(), uri.toString());
        } else {
            Browser.openUrl(getContext(), uri);
        }
    }

    private void showError() {
        if (destroyed) {
            return;
        }
        progressView.setVisibility(View.GONE);
        webView.setVisibility(View.INVISIBLE);
        errorView.setVisibility(View.VISIBLE);
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false; // vertical swipes scroll the embed
    }

    @Override
    public void dismiss() {
        if (!destroyed) {
            webView.onPause(); // stops the sound right away; destroyed when the sheet is gone
        }
        super.dismiss();
    }

    @Override
    public void dismissInternal() {
        super.dismissInternal();
        destroyWebView();
    }

    private void destroyWebView() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            webView.stopLoading();
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
