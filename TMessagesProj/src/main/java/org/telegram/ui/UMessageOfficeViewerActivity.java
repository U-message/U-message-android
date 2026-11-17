package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import org.json.JSONArray;
import org.json.JSONTokener;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UMessageOfficeDocument;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.tgnet.TLRPC;

import java.io.File;

/**
 * U message: Word, Excel, PowerPoint, CSV and text files from a message, read and edited inside the app.
 * The file is turned into a page by {@link UMessageOfficeDocument}; Save writes a copy with the edits that can
 * be sent back to the chat, saved to Downloads or shared. The original file in the chat is never changed.
 */
public class UMessageOfficeViewerActivity extends BaseFragment {

    private static final int CHROME_MARGIN_DP = 14;
    private static final int CHROME_HEIGHT_DP = 44;

    private final MessageObject message;
    private final File file;
    private final String name;
    private final int kind;
    private final boolean isProtected;

    private FrameLayout contentView;
    private WebView webView;
    private RadialProgressView progress;
    private LinearLayout errorView;
    private LinearLayout actionsCapsule;
    private TextView doneButton;
    /** True once the current working copy went somewhere (Downloads, Save as, a chat, another app). */
    private boolean exported;
    private File saveAsSource;
    private ActionBarPopupWindow morePopup;
    private FlagSecureReason flagSecure;
    private boolean editing;
    private boolean saving;
    private boolean loaded;
    private volatile boolean closed;
    /** The last saved copy; menu actions use it once there are edits. */
    private File savedCopy;
    /** Edited since the last save; set from the page's input events. */
    private volatile boolean unsaved;

    private class Host {
        @android.webkit.JavascriptInterface
        public void changed() {
            unsaved = true;
        }
    }

    public static boolean canOpen(MessageObject message) {
        if (message == null || message.getDocument() == null) return false;
        return UMessageOfficeDocument.kindOf(message.getDocumentName(), message.getDocument().mime_type) != UMessageOfficeDocument.NONE;
    }

    public UMessageOfficeViewerActivity(MessageObject message, File file) {
        this.message = message;
        this.file = file;
        this.name = message.getDocumentName();
        this.kind = UMessageOfficeDocument.sniff(file, UMessageOfficeDocument.kindOf(name, message.getDocument().mime_type));
        this.isProtected = message.messageOwner.noforwards
                || MessagesController.getInstance(message.currentAccount).isPeerNoForwards(message.getDialogId());
    }

    private boolean canEdit() {
        // a copy of a protected file could be sent anywhere
        return !isProtected && UMessageOfficeDocument.isEditable(kind);
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean isLightStatusBar() {
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundGray)) > 0.7f;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (webView != null) {
            ((ViewGroup.MarginLayoutParams) webView.getLayoutParams()).bottomMargin = bottom;
            webView.requestLayout();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        contentView = new FrameLayout(context);
        int background = getThemedColor(Theme.key_windowBackgroundGray);
        contentView.setBackgroundColor(background);

        webView = new WebView(context);
        webView.setBackgroundColor(background);
        WebSettings settings = webView.getSettings();
        // The page is generated by us from the file; scripts only handle editing and sheet tabs.
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        webView.addJavascriptInterface(new Host(), "UMHost");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                if (progress != null) progress.setVisibility(View.GONE);
                updateButtons();
            }
        });
        int top = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(CHROME_MARGIN_DP + CHROME_HEIGHT_DP + 8);
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webParams.topMargin = top;
        webParams.bottomMargin = AndroidUtilities.navigationBarHeight;
        contentView.addView(webView, webParams);

        progress = new RadialProgressView(context);
        progress.setProgressColor(getThemedColor(Theme.key_featuredStickers_addButton));
        contentView.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        createErrorView(context);
        contentView.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        createTopChrome(context);

        fragmentView = contentView;
        ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onInsetsInternal);
        load();
        return fragmentView;
    }

    private void load() {
        final int background = getThemedColor(Theme.key_windowBackgroundGray);
        Utilities.globalQueue.postRunnable(() -> {
            String html = null;
            try {
                html = UMessageOfficeDocument.toHtml(file, kind, background);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String result = html;
            AndroidUtilities.runOnUIThread(() -> {
                if (closed || webView == null) return;
                if (result == null) {
                    showError();
                } else {
                    webView.loadDataWithBaseURL("about:blank", result, "text/html", "utf-8", null);
                }
            });
        });
    }

    private static final int SAVE_AS_REQUEST = 7401;
    private static final int ACTION_SIZE_DP = 40;

    private void createTopChrome(Context context) {
        int margin = CHROME_MARGIN_DP;
        int top = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(CHROME_MARGIN_DP);

        ImageView back = createIconButton(context, R.drawable.ic_ab_back, R.string.Back);
        back.setOnClickListener(v -> {
            if (onBackPressed(true)) finishFragment();
        });
        FrameLayout.LayoutParams backParams = LayoutHelper.createFrame(CHROME_HEIGHT_DP, CHROME_HEIGHT_DP, Gravity.TOP | Gravity.LEFT, margin, 0, 0, 0);
        backParams.topMargin = top;
        contentView.addView(back, backParams);

        // iOS-style: one glass capsule of actions on the right, a Done capsule in its place while editing
        int actions = isProtected ? 0 : canEdit() ? 3 : 2;
        int capsuleWidth = actions == 0 ? 0 : actions * ACTION_SIZE_DP + 8;

        TextView title = new TextView(context);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        applyGlass(title);
        FrameLayout.LayoutParams titleParams = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, CHROME_HEIGHT_DP, Gravity.TOP | Gravity.LEFT,
                margin + CHROME_HEIGHT_DP + 8, 0, margin + Math.max(capsuleWidth, 76) + 8, 0);
        titleParams.topMargin = top;
        contentView.addView(title, titleParams);

        if (actions == 0) return;
        actionsCapsule = new LinearLayout(context);
        actionsCapsule.setOrientation(LinearLayout.HORIZONTAL);
        actionsCapsule.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        applyGlass(actionsCapsule);
        actionsCapsule.setVisibility(View.GONE);
        ImageView share = createActionButton(context, R.drawable.msg_share, R.string.ShareFile);
        share.setOnClickListener(v -> withCurrentFile(this::openShareSheet));
        actionsCapsule.addView(share, LayoutHelper.createLinear(ACTION_SIZE_DP, CHROME_HEIGHT_DP));
        ImageView save = createActionButton(context, R.drawable.msg_download, R.string.Save);
        save.setOnClickListener(this::showSaveMenu);
        actionsCapsule.addView(save, LayoutHelper.createLinear(ACTION_SIZE_DP, CHROME_HEIGHT_DP));
        if (canEdit()) {
            ImageView edit = createActionButton(context, R.drawable.msg_edit, R.string.Edit);
            edit.setOnClickListener(v -> setEditing(true));
            actionsCapsule.addView(edit, LayoutHelper.createLinear(ACTION_SIZE_DP, CHROME_HEIGHT_DP));
        }
        FrameLayout.LayoutParams capsuleParams = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, CHROME_HEIGHT_DP, Gravity.TOP | Gravity.RIGHT, 0, 0, margin, 0);
        capsuleParams.topMargin = top;
        contentView.addView(actionsCapsule, capsuleParams);

        if (canEdit()) {
            doneButton = new TextView(context);
            doneButton.setText(LocaleController.getString(R.string.Done));
            doneButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            doneButton.setTypeface(AndroidUtilities.bold());
            doneButton.setTextColor(0xffffffff);
            doneButton.setGravity(Gravity.CENTER);
            doneButton.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
            // fixed iOS blue: the theme's button colour is white in the monochrome theme, under white text
            doneButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(CHROME_HEIGHT_DP / 2f), 0xff0a84ff, 0xff0060df));
            doneButton.setElevation(AndroidUtilities.dp(6));
            doneButton.setOnClickListener(v -> save(null));
            doneButton.setVisibility(View.GONE);
            FrameLayout.LayoutParams doneParams = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, CHROME_HEIGHT_DP, Gravity.TOP | Gravity.RIGHT, 0, 0, margin, 0);
            doneParams.topMargin = top;
            contentView.addView(doneButton, doneParams);
        }
    }

    private ImageView createActionButton(Context context, int icon, int description) {
        ImageView button = new ImageView(context);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN);
        button.setContentDescription(LocaleController.getString(description));
        button.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP, AndroidUtilities.dp(18)));
        return button;
    }

    private void updateButtons() {
        if (actionsCapsule != null) actionsCapsule.setVisibility(loaded && !editing ? View.VISIBLE : View.GONE);
        if (doneButton != null) doneButton.setVisibility(loaded && editing ? View.VISIBLE : View.GONE);
    }

    private void setEditing(boolean value) {
        editing = value;
        if (webView != null) webView.evaluateJavascript("UM.setEdit(" + value + ")", null);
        if (!value) AndroidUtilities.hideKeyboard(webView);
        updateButtons();
    }

    /**
     * Done: the edits go into a working copy and the page stays as it is; nothing is sent or exported until the
     * user picks Share or Save. {@code then} runs with the current file (null on failure).
     */
    private void save(Utilities.Callback<File> then) {
        if (saving || webView == null) return;
        saving = true;
        webView.evaluateJavascript("UM.collect()", raw -> {
            final JSONArray edits;
            try {
                Object value = new JSONTokener(raw).nextValue();
                edits = new JSONArray(value instanceof String ? (String) value : "[]");
            } catch (Throwable e) {
                FileLog.e(e);
                saving = false;
                return;
            }
            if (edits.length() == 0) {
                saving = false;
                unsaved = false;
                setEditing(false);
                if (then != null) then.run(savedCopy != null ? savedCopy : file);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                File out = null;
                try {
                    // Telegram's own media cache: files under the app's private data folder are refused as attachments
                    File dir = new File(org.telegram.messenger.FileLoader.getDirectory(org.telegram.messenger.FileLoader.MEDIA_DIR_CACHE), "umessage_office/" + System.currentTimeMillis());
                    if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("no dir");
                    out = new File(dir, safeName(UMessageOfficeDocument.savedName(name, kind)));
                    UMessageOfficeDocument.save(file, kind, edits, out);
                } catch (Throwable e) {
                    FileLog.e(e);
                    out = null;
                }
                final File result = out;
                AndroidUtilities.runOnUIThread(() -> {
                    saving = false;
                    if (closed) return;
                    if (result == null) {
                        BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.UMessageOfficeSaveFailed)).show();
                        if (then != null) then.run(null);
                        return;
                    }
                    savedCopy = result;
                    exported = false;
                    unsaved = false;
                    setEditing(false);
                    if (then != null) then.run(result);
                    else BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.UMessageOfficeChangesSaved)).show();
                });
            });
        });
    }

    /** The original keeps its own type; a saved copy of a .doc or .xls is a .docx or .xlsx. */
    private String mimeOf(File f) {
        return UMessageOfficeDocument.mimeOf(f == file ? kind : UMessageOfficeDocument.savedKind(kind));
    }

    private String displayName(File f) {
        return f == file ? name : f.getName();
    }

    private static String safeName(String name) {
        String n = TextUtils.isEmpty(name) ? "document" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    /** Telegram's own share sheet: pick chats, the file is sent to each of them. */
    private void openShareSheet(File f) {
        if (getParentActivity() == null) return;
        final String path = f.getAbsolutePath();
        final String mime = mimeOf(f);
        ShareAlert alert = new ShareAlert(getParentActivity(), null, null, false, null, false, getResourceProvider()) {
            @Override
            protected void sendInternal(boolean withSound) {
                if (selectedDialogs.size() == 0) return;
                CharSequence comment = getCommentText();
                String caption = TextUtils.isEmpty(comment) ? null : comment.toString();
                for (int i = 0; i < selectedDialogs.size(); i++) {
                    long did = selectedDialogs.keyAt(i);
                    TLRPC.TL_forumTopic topic = selectedDialogTopics.get(selectedDialogs.valueAt(i));
                    MessageObject replyTop = null;
                    if (topic != null && topic.topicStartMessage != null) {
                        replyTop = new MessageObject(currentAccount, topic.topicStartMessage, false, false);
                        replyTop.isTopicMainMessage = true;
                    }
                    SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(currentAccount), path, path, null, caption, mime, did,
                            replyTop, replyTop, null, null, null, withSound, 0, null, null, false);
                }
                exported = true;
                dismiss();
                BulletinFactory.of(UMessageOfficeViewerActivity.this).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.UMessageOfficeSent)).show();
            }
        };
        showDialog(alert);
    }

    private void saveToDownloads(File f, Runnable then) {
        MediaController.saveFile(f.getAbsolutePath(), getParentActivity(), 2, displayName(f), mimeOf(f), uri -> {
            exported = true;
            if (then != null) {
                then.run();
            } else if (!closed) {
                BulletinFactory.of(this).createDownloadBulletin(BulletinFactory.FileType.UNKNOWN).show();
            }
        });
    }

    /** Save as: the system picker lets the user rename the file and choose any folder or cloud drive. */
    private void saveAs(File f) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeOf(f));
            intent.putExtra(Intent.EXTRA_TITLE, displayName(f));
            saveAsSource = f;
            startActivityForResult(intent, SAVE_AS_REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
            saveToDownloads(f, null);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != SAVE_AS_REQUEST || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null || saveAsSource == null) return;
        final File source = saveAsSource;
        final Uri target = data.getData();
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try (java.io.InputStream in = new java.io.FileInputStream(source);
                 java.io.OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(target, "wt")) {
                if (out != null) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    ok = true;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (success) exported = true;
                if (closed) return;
                if (success) BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.UMessageOfficeChangesSaved)).show();
                else BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.UMessageOfficeSaveFailed)).show();
            });
        });
    }

    private void shareToApps(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", f);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeOf(f));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Unsaved edits are first written to the working copy, so every action uses what is on screen. */
    private void withCurrentFile(Utilities.Callback<File> action) {
        if (unsaved && canEdit()) {
            save(copy -> {
                if (copy != null) action.run(copy);
            });
        } else {
            action.run(savedCopy != null ? savedCopy : file);
        }
    }

    /** Save: to Downloads in one tap, or Save as… to rename and pick the place; both from the same button. */
    private void showSaveMenu(View anchor) {
        if (getParentActivity() == null) return;
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getParentActivity(), getResourceProvider());
        ActionBarPopupWindow popup = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        ActionBarMenuSubItem download = ActionBarMenuItem.addItem(true, false, layout, R.drawable.msg_download, LocaleController.getString(R.string.SaveToDownloads), false, getResourceProvider());
        download.setOnClickListener(v -> {
            popup.dismiss();
            withCurrentFile(f -> saveToDownloads(f, null));
        });
        ActionBarMenuSubItem saveAsItem = ActionBarMenuItem.addItem(false, false, layout, R.drawable.files_folder, LocaleController.getString(R.string.UMessageOfficeSaveAs), false, getResourceProvider());
        saveAsItem.setOnClickListener(v -> {
            popup.dismiss();
            withCurrentFile(this::saveAs);
        });
        ActionBarMenuSubItem apps = ActionBarMenuItem.addItem(false, false, layout, R.drawable.msg_shareout, LocaleController.getString(R.string.UMessageOfficeShareApps), false, getResourceProvider());
        apps.setOnClickListener(v -> {
            popup.dismiss();
            withCurrentFile(this::shareToApps);
        });
        ActionBarMenuSubItem open = ActionBarMenuItem.addItem(false, true, layout, R.drawable.msg_openin, LocaleController.getString(R.string.OpenInExternalApp), false, getResourceProvider());
        open.setOnClickListener(v -> {
            popup.dismiss();
            withCurrentFile(f -> AndroidUtilities.openForView(f, displayName(f), mimeOf(f), getParentActivity(), getResourceProvider(), false));
        });
        popup.setPauseNotifications(true);
        popup.setDismissAnimationDuration(220);
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);
        popup.setAnimationStyle(R.style.PopupContextAnimation);
        popup.setFocusable(true);
        layout.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST));
        popup.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.getContentView().setFocusableInTouchMode(true);
        morePopup = popup;
        popup.showAsDropDown(anchor, anchor.getWidth() - layout.getMeasuredWidth(), AndroidUtilities.dp(4));
    }

    /** Edits not yet on screen in a working copy, or a working copy never saved or sent anywhere. */
    private boolean hasUnexportedChanges() {
        return canEdit() && (unsaved || savedCopy != null && !exported);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (hasUnexportedChanges() && getParentActivity() != null) {
            if (invoked) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                builder.setTitle(LocaleController.getString(R.string.UnsavedChanges));
                builder.setMessage(LocaleController.getString(R.string.UMessageOfficeUnsaved));
                builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> withCurrentFile(f -> saveToDownloads(f, () -> {
                    exported = true;
                    finishFragment();
                })));
                builder.setNegativeButton(LocaleController.getString(R.string.Discard), (d, w) -> {
                    unsaved = false;
                    exported = true;
                    finishFragment();
                });
                AlertDialog dialog = builder.create();
                showDialog(dialog);
                TextView discard = (TextView) dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
                if (discard != null) discard.setTextColor(getThemedColor(Theme.key_text_RedBold));
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        // a swipe would drop unsaved edits; the back button asks first
        return !editing && !hasUnexportedChanges();
    }

    private void showError() {
        if (progress != null) progress.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.VISIBLE);
        if (actionsCapsule != null) actionsCapsule.setVisibility(View.GONE);
    }

    private void createErrorView(Context context) {
        errorView = new LinearLayout(context);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER_HORIZONTAL);
        errorView.setVisibility(View.GONE);

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.ErrorOccurred));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        errorView.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 0, 32, 0));

        TextView text = new TextView(context);
        text.setText(LocaleController.getString(R.string.UMessageReaderCantOpen));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        errorView.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 8, 32, 0));

        if (!isProtected) {
            TextView button = new TextView(context);
            button.setText(LocaleController.getString(R.string.OpenInExternalApp));
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            button.setTypeface(AndroidUtilities.bold());
            button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            button.setGravity(Gravity.CENTER);
            button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
            button.setOnClickListener(v -> AndroidUtilities.openForView(file, name, message.getDocument().mime_type, getParentActivity(), getResourceProvider(), false));
            errorView.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 32, 28, 32, 0));
        }
    }

    private ImageView createIconButton(Context context, int icon, int description) {
        ImageView button = new ImageView(context);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN);
        int padding = AndroidUtilities.dp((CHROME_HEIGHT_DP - 24) / 2f);
        button.setPadding(padding, padding, padding, padding);
        button.setContentDescription(LocaleController.getString(description));
        applyGlass(button);
        button.setForeground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP, AndroidUtilities.dp(CHROME_HEIGHT_DP / 2f)));
        return button;
    }

    /** Same translucent capsule as the PDF reader's chrome. */
    private void applyGlass(View view) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(AndroidUtilities.dp(CHROME_HEIGHT_DP / 2f));
        background.setColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhite), 0.85f));
        background.setStroke(Math.max(1, AndroidUtilities.dp(0.5f)), getThemedColor(Theme.key_divider));
        view.setBackground(background);
        view.setElevation(AndroidUtilities.dp(6));
        final float radius = AndroidUtilities.dp(CHROME_HEIGHT_DP / 2f);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isProtected && getParentActivity() != null) {
            if (flagSecure == null) flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
            flagSecure.attach();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (flagSecure != null) flagSecure.detach();
    }

    @Override
    public void onFragmentDestroy() {
        closed = true;
        if (flagSecure != null) flagSecure.detach();
        if (morePopup != null && morePopup.isShowing()) morePopup.dismiss();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onFragmentDestroy();
    }
}
