package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;

/** U message: shows an exported chat (messages.html) and saves it as a ZIP or, through the print dialog, as a PDF. */
public class UMessageExportViewerActivity extends BaseFragment {

    private static final int PDF = 1, SAVE_ZIP = 2, SHARE = 3;

    private final File html, zip;
    private final String chatTitle;
    private WebView webView;

    public UMessageExportViewerActivity(File html, File zip, String chatTitle) {
        this.html = html;
        this.zip = zip;
        this.chatTitle = chatTitle;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(chatTitle);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == PDF) {
                    printToPdf();
                } else if (id == SAVE_ZIP) {
                    saveZip();
                } else if (id == SHARE) {
                    shareZip();
                }
            }
        });
        final ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(PDF, R.drawable.msg_download, getString(R.string.UMessageExportSavePdf));
        other.addSubItem(SAVE_ZIP, R.drawable.msg_download, getString(R.string.UMessageExportSaveZip));
        other.addSubItem(SHARE, R.drawable.msg_share, getString(R.string.UMessageExportShare));

        final FrameLayout frame = new FrameLayout(context);
        webView = new WebView(context);
        final WebSettings settings = webView.getSettings();
        settings.setAllowFileAccess(true);
        settings.setJavaScriptEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient());
        frame.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        webView.loadUrl(Uri.fromFile(html).toString());
        fragmentView = frame;
        return fragmentView;
    }

    /** The system print dialog offers "Save as PDF"; videos and voice messages print as their file names. */
    private void printToPdf() {
        if (getParentActivity() == null || webView == null) {
            return;
        }
        try {
            final PrintManager printManager = (PrintManager) getParentActivity().getSystemService(Context.PRINT_SERVICE);
            final String job = chatTitle + " - U message";
            printManager.print(job, webView.createPrintDocumentAdapter(job), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveZip() {
        if (getParentActivity() == null) {
            return;
        }
        MediaController.saveFile(zip.getAbsolutePath(), getParentActivity(), 2, zip.getName(), "application/zip", uri ->
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_download, getString(R.string.UMessageExportSaved)).show());
    }

    private void shareZip() {
        if (getParentActivity() == null) {
            return;
        }
        try {
            final Uri uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", zip);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, getString(R.string.UMessageExportShare)));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        // the export holds a full copy of the chat: drop it once the viewer is closed
        // (a share target already got its own read grant/copy of the ZIP)
        final File root = zip != null ? zip.getParentFile() : null;
        if (root != null && "umessage_export".equals(root.getName())) {
            new Thread(() -> deleteRecursive(root), "umessage-export-cleanup").start();
        }
        super.onFragmentDestroy();
    }

    private static void deleteRecursive(File f) {
        if (f == null) {
            return;
        }
        final File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
