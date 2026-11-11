package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageReaderBookmarks;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageReaderPagesSheet;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/** U message: reads a PDF from a message inside the app, continuous pages with zoom, remembering the last page. */
public class UMessageDocumentReaderActivity extends BaseFragment {

    private static final float MAX_SCALE = 3f;
    private static final float SHARP_ABOVE_SCALE = 1.6f;
    private static final int MAX_BITMAP_SIDE = 4096;
    private static final int NEAR_PAGES = 3;
    private static final int THUMBNAIL_WIDTH = 240;
    private static final int PAGE_GAP_DP = 12;
    private static final int TOP_CHROME_MARGIN_DP = 14;
    private static final int TOP_CHROME_HEIGHT_DP = 44;
    private static final int BOTTOM_BAR_HEIGHT_DP = 64;
    private static final int BOTTOM_BAR_MARGIN_DP = 26;
    private static final int FOCUS_PILL_MARGIN_DP = 30;

    private final MessageObject message;
    private final File file;
    private final String name;
    private final boolean isProtected;

    private static final int EAGER_MEASURED_PAGES = 20;

    private final DispatchQueue queue = new DispatchQueue("umessage_reader");
    // Touched only on the queue: PdfRenderer is not thread safe and allows one open page at a time.
    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;

    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>((int) Math.min(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private final HashSet<Integer> pending = new HashSet<>();
    private int generation;

    private final LruCache<Integer, Bitmap> thumbnails = new LruCache<Integer, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private final SparseArray<ArrayList<Runnable>> thumbnailWaiters = new SparseArray<>();
    // Queued thumbnails are dropped once the sheet closes, so they don't hold up the pages being read.
    private volatile boolean thumbnailsWanted;
    private final ArrayList<Integer> thumbnailStack = new ArrayList<>();
    private boolean thumbnailRendering;

    private int[] pageWidths;
    private int[] pageHeights;
    private volatile int currentPage;
    private volatile boolean closed;
    private boolean panelsVisible = true;
    private float scale = 1f;
    private int baseWidth;
    private float renderFactor = 1.5f;

    private FrameLayout contentView;
    private RecyclerView listView;
    private LinearLayoutManager layoutManager;
    private FrameLayout chromeLayer;
    private ImageView backButton;
    private TextView titleView;
    private ImageView moreButton;
    private FrameLayout bottomBar;
    private TextView pageText;
    private View progressFill;
    private ImageView bookmarkButton;
    private int bookmarkShownPage = -1;
    private TextView focusPill;
    private ActionBarPopupWindow morePopup;
    private LinearLayout errorView;
    private TextView errorText;
    private int pendingError;
    private FlagSecureReason flagSecure;
    private int navigationInset;

    public static boolean canOpen(MessageObject message) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.DOCUMENT_READER)) return false;
        if (message == null || message.getDocument() == null) return false;
        String mime = message.getDocument().mime_type;
        String fileName = message.getDocumentName();
        boolean pdf = "application/pdf".equalsIgnoreCase(mime) || fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        return pdf && resolveFile(message) != null;
    }

    private static File resolveFile(MessageObject message) {
        File f = null;
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            f = new File(message.messageOwner.attachPath);
        }
        if (f == null || !f.exists()) {
            f = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        }
        return f != null && f.exists() ? f : null;
    }

    public UMessageDocumentReaderActivity(MessageObject message) {
        this.message = message;
        this.file = resolveFile(message);
        this.name = message.getDocumentName();
        this.isProtected = message.messageOwner.noforwards
                || MessagesController.getInstance(message.currentAccount).isPeerNoForwards(message.getDialogId());
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_reader", Context.MODE_PRIVATE);
    }

    private long documentId() {
        TLRPC.Document document = message.getDocument();
        return document != null ? document.id : 0;
    }

    private String positionKey() {
        return "page_" + documentId();
    }

    @Override
    public boolean onFragmentCreate() {
        queue.postRunnable(this::openDocument);
        return super.onFragmentCreate();
    }

    private void openDocument() {
        int[] widths = null;
        int[] heights = null;
        int failure = 0;
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            int count = renderer.getPageCount();
            widths = new int[count];
            heights = new int[count];
            // only the first pages are opened up front; the rest take the last measured size and
            // are corrected when they are rendered (see render/updatePageSize)
            int eager = Math.min(count, EAGER_MEASURED_PAGES);
            for (int i = 0; i < eager; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    widths[i] = Math.max(1, page.getWidth());
                    heights[i] = Math.max(1, page.getHeight());
                }
            }
            for (int i = eager; i < count; i++) {
                widths[i] = widths[eager - 1];
                heights[i] = heights[eager - 1];
            }
            if (count == 0) failure = R.string.UMessageReaderCantOpen;
        } catch (SecurityException passwordProtected) {
            failure = R.string.UMessageReaderPassword;
        } catch (Throwable e) {
            FileLog.e(e);
            failure = R.string.UMessageReaderCantOpen;
        }
        int[] w = widths;
        int[] h = heights;
        int error = failure;
        AndroidUtilities.runOnUIThread(() -> {
            if (closed) return;
            if (error != 0) {
                showError(error);
            } else {
                pageWidths = w;
                pageHeights = h;
                onDocumentReady();
            }
        });
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean isLightStatusBar() {
        // There is no action bar any more: the status bar sits directly on the screen background.
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundGray)) > 0.7f;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        navigationInset = bottom;
        applyInsets();
    }

    private void applyInsets() {
        if (listView == null) return;
        int halfGap = AndroidUtilities.dp(PAGE_GAP_DP) / 2;
        int topClearance = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(TOP_CHROME_MARGIN_DP + TOP_CHROME_HEIGHT_DP + 20);
        int bottomClearance = navigationInset + AndroidUtilities.dp(BOTTOM_BAR_MARGIN_DP + BOTTOM_BAR_HEIGHT_DP + 14);
        listView.setPadding(0, topClearance - halfGap, 0, bottomClearance - halfGap);
        ((ViewGroup.MarginLayoutParams) bottomBar.getLayoutParams()).bottomMargin = navigationInset + AndroidUtilities.dp(BOTTOM_BAR_MARGIN_DP);
        ((ViewGroup.MarginLayoutParams) focusPill.getLayoutParams()).bottomMargin = navigationInset + AndroidUtilities.dp(FOCUS_PILL_MARGIN_DP);
        bottomBar.requestLayout();
        focusPill.requestLayout();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        contentView = new ZoomFrame(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        layoutManager = new LinearLayoutManager(context);
        listView = new RecyclerView(context);
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updatePosition();
            }
        });
        contentView.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        createErrorView(context);
        contentView.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        // While the chrome is hidden or sliding away its views must not catch touches meant for the pages.
        chromeLayer = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                return panelsVisible && super.dispatchTouchEvent(ev);
            }
        };
        contentView.addView(chromeLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        createTopChrome(context);
        createBottomBar(context);

        focusPill = new TextView(context);
        focusPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        focusPill.setTypeface(AndroidUtilities.bold());
        // Floats over white paper in every theme, so it stays dark rather than following the theme.
        focusPill.setTextColor(Color.WHITE);
        focusPill.setGravity(Gravity.CENTER);
        focusPill.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        focusPill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(15), 0xA81C1C1E));
        focusPill.setAlpha(0f);
        contentView.addView(focusPill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        navigationInset = AndroidUtilities.navigationBarHeight;
        applyInsets();

        fragmentView = contentView;
        ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onInsetsInternal);
        if (pageWidths != null) onDocumentReady();
        if (pendingError != 0) showError(pendingError);
        return fragmentView;
    }

    private void createTopChrome(Context context) {
        int sideMargin = TOP_CHROME_MARGIN_DP;
        int top = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(TOP_CHROME_MARGIN_DP);

        backButton = createIconButton(context, R.drawable.ic_ab_back, R.string.Back, TOP_CHROME_HEIGHT_DP);
        backButton.setOnClickListener(v -> finishFragment());
        applyGlass(backButton, TOP_CHROME_HEIGHT_DP / 2);
        FrameLayout.LayoutParams backParams = LayoutHelper.createFrame(TOP_CHROME_HEIGHT_DP, TOP_CHROME_HEIGHT_DP, Gravity.TOP | Gravity.LEFT, sideMargin, 0, 0, 0);
        backParams.topMargin = top;
        chromeLayer.addView(backButton, backParams);

        titleView = new TextView(context);
        titleView.setText(name);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
        titleView.setClickable(true);
        applyGlass(titleView, TOP_CHROME_HEIGHT_DP / 2);
        // Same margin on both sides, even without the more button, so the capsule stays centred and clear of the buttons.
        int titleMargin = sideMargin + TOP_CHROME_HEIGHT_DP + 10;
        FrameLayout.LayoutParams titleParams = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, TOP_CHROME_HEIGHT_DP, Gravity.TOP | Gravity.CENTER_HORIZONTAL, titleMargin, 0, titleMargin, 0);
        titleParams.topMargin = top;
        chromeLayer.addView(titleView, titleParams);

        if (!isProtected) {
            moreButton = createIconButton(context, R.drawable.ic_ab_other, R.string.AccDescrMoreOptions, TOP_CHROME_HEIGHT_DP);
            moreButton.setOnClickListener(this::showMoreMenu);
            applyGlass(moreButton, TOP_CHROME_HEIGHT_DP / 2);
            FrameLayout.LayoutParams moreParams = LayoutHelper.createFrame(TOP_CHROME_HEIGHT_DP, TOP_CHROME_HEIGHT_DP, Gravity.TOP | Gravity.RIGHT, 0, 0, sideMargin, 0);
            moreParams.topMargin = top;
            chromeLayer.addView(moreButton, moreParams);
        }
    }

    private void createBottomBar(Context context) {
        bottomBar = new FrameLayout(context);
        bottomBar.setClickable(true);
        bottomBar.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        applyGlass(bottomBar, BOTTOM_BAR_HEIGHT_DP / 2);
        bottomBar.setVisibility(View.GONE);
        chromeLayer.addView(bottomBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BOTTOM_BAR_HEIGHT_DP, Gravity.BOTTOM, 20, 0, 20, 0));

        ImageView pagesButton = createIconButton(context, R.drawable.msg_media, R.string.UMessageReaderPages, 48);
        pagesButton.setOnClickListener(v -> openPagesSheet());
        bottomBar.addView(pagesButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        bookmarkButton = createIconButton(context, R.drawable.msg_saved, R.string.UMessageReaderBookmark, 48);
        bookmarkButton.setOnClickListener(v -> {
            UMessageReaderBookmarks.toggle(documentId(), currentPage);
            updateBookmarkButton();
        });
        bottomBar.addView(bookmarkButton, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        LinearLayout center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomBar.addView(center, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        pageText = new TextView(context);
        pageText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        pageText.setTypeface(AndroidUtilities.bold());
        pageText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        pageText.setGravity(Gravity.CENTER);
        pageText.setSingleLine(true);
        center.addView(pageText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout track = new FrameLayout(context);
        track.setBackgroundColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), 0.14f));
        setRoundedOutline(track, AndroidUtilities.dp(1.5f));
        track.setClipToOutline(true);
        center.addView(track, LayoutHelper.createLinear(56, 3, Gravity.CENTER_HORIZONTAL, 0, 5, 0, 0));

        progressFill = new View(context);
        progressFill.setBackgroundColor(getThemedColor(Theme.key_featuredStickers_addButton));
        progressFill.setPivotX(0);
        progressFill.setScaleX(0);
        track.addView(progressFill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void createErrorView(Context context) {
        errorView = new LinearLayout(context);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER_HORIZONTAL);
        errorView.setClipChildren(false);
        errorView.setClipToPadding(false);
        errorView.setVisibility(View.GONE);

        ImageView tile = new ImageView(context);
        tile.setImageResource(R.drawable.msg_sendfile);
        tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN);
        int tilePadding = AndroidUtilities.dp(23);
        tile.setPadding(tilePadding, tilePadding, tilePadding, tilePadding);
        tile.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(26), getThemedColor(Theme.key_windowBackgroundWhite)));
        tile.setElevation(AndroidUtilities.dp(4));
        errorView.addView(tile, LayoutHelper.createLinear(88, 88, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.ErrorOccurred));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        errorView.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 22, 32, 0));

        errorText = new TextView(context);
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        errorText.setLineSpacing(AndroidUtilities.dp(3), 1f);
        errorText.setGravity(Gravity.CENTER);
        errorText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        errorView.addView(errorText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 8, 32, 0));

        if (!isProtected) {
            TextView button = new TextView(context);
            button.setText(LocaleController.getString(R.string.OpenInExternalApp));
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            button.setTypeface(AndroidUtilities.bold());
            button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
            button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
            button.setOnClickListener(v -> openExternally());
            errorView.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 32, 28, 32, 0));
        }
    }

    private ImageView createIconButton(Context context, int icon, int description, int sizeDp) {
        ImageView button = new ImageView(context);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN);
        int padding = AndroidUtilities.dp((sizeDp - 24) / 2f);
        button.setPadding(padding, padding, padding, padding);
        button.setContentDescription(LocaleController.getString(description));
        button.setForeground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP, AndroidUtilities.dp(sizeDp / 2f)));
        return button;
    }

    /** Translucent only, no backdrop blur: blur3 re-captures the list every frame and only works from API 31. */
    private void applyGlass(View view, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(AndroidUtilities.dp(radiusDp));
        background.setColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhite), 0.85f));
        background.setStroke(Math.max(1, AndroidUtilities.dp(0.5f)), getThemedColor(Theme.key_divider));
        view.setBackground(background);
        view.setElevation(AndroidUtilities.dp(6));
        // A provider of our own keeps the outline opaque, which stops the shadow darkening the translucent fill.
        setRoundedOutline(view, AndroidUtilities.dp(radiusDp));
    }

    private static void setRoundedOutline(View view, float radius) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    private void onDocumentReady() {
        if (listView == null || listView.getAdapter() != null) return;
        listView.setAdapter(new PagesAdapter());
        int saved = prefs().getInt(positionKey(), 0);
        if (saved > 0 && saved < pageWidths.length) {
            layoutManager.scrollToPositionWithOffset(saved, 0);
            currentPage = saved;
        }
        bottomBar.setVisibility(View.VISIBLE);
        listView.post(this::updatePosition);
    }

    private void showError(int text) {
        // The file can fail to open before the view exists.
        pendingError = text;
        if (errorView == null) return;
        errorText.setText(LocaleController.getString(text));
        errorView.setVisibility(View.VISIBLE);
        // The error screen has its own button for this.
        if (moreButton != null) moreButton.setVisibility(View.GONE);
    }

    private void showMoreMenu(View anchor) {
        if (getParentActivity() == null) return;
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getParentActivity(), getResourceProvider());
        ActionBarMenuSubItem openItem = ActionBarMenuItem.addItem(true, true, layout, R.drawable.msg_openin, LocaleController.getString(R.string.OpenInExternalApp), false, getResourceProvider());
        ActionBarPopupWindow popup = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        openItem.setOnClickListener(v -> {
            popup.dismiss();
            openExternally();
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

    private void openPagesSheet() {
        if (pageWidths == null || getParentActivity() == null) return;
        UMessageReaderPagesSheet sheet = new UMessageReaderPagesSheet(getParentActivity(), getResourceProvider(), new UMessageReaderPagesSheet.Delegate() {
            @Override
            public int getPageCount() {
                return pageWidths.length;
            }

            @Override
            public int getCurrentPage() {
                return currentPage;
            }

            @Override
            public long getDocumentId() {
                return documentId();
            }

            @Override
            public Bitmap getThumbnail(int page) {
                return thumbnails.get(page);
            }

            @Override
            public void requestThumbnail(int page, Runnable onReady) {
                requestPageThumbnail(page, onReady);
            }

            @Override
            public void onPageSelected(int page) {
                layoutManager.scrollToPositionWithOffset(page, 0);
                currentPage = page;
                listView.post(UMessageDocumentReaderActivity.this::updatePosition);
            }

            @Override
            public void onBookmarksChanged() {
                updateBookmarkButton();
            }
        });
        // The sheet is a window of its own and FLAG_SECURE on the activity doesn't cover it.
        if (isProtected && sheet.getWindow() != null) {
            sheet.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        thumbnailsWanted = showDialog(sheet, dialog -> thumbnailsWanted = false) != null;
    }

    private void updateBookmarkButton() {
        if (bookmarkButton == null) return;
        boolean bookmarked = UMessageReaderBookmarks.isBookmarked(documentId(), currentPage);
        bookmarkButton.setImageResource(bookmarked ? R.drawable.chats_saved : R.drawable.msg_saved);
        bookmarkButton.setColorFilter(getThemedColor(bookmarked ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN);
        bookmarkButton.setSelected(bookmarked);
    }

    private void openExternally() {
        AndroidUtilities.openForView(file, name, message.getDocument() != null ? message.getDocument().mime_type : null, getParentActivity(), getResourceProvider(), false);
    }

    private void updatePosition() {
        if (pageWidths == null || layoutManager == null) return;
        int count = pageWidths.length;
        int page = layoutManager.findFirstVisibleItemPosition();
        if (page == RecyclerView.NO_POSITION) return;
        // The first page can stay "first visible" with only its edge on screen: read the one at the top third.
        View top = layoutManager.findViewByPosition(page);
        if (top != null && top.getBottom() < listView.getHeight() / 3 && page + 1 < count) page++;
        if (!listView.canScrollVertically(1)) page = count - 1;
        currentPage = page;
        String position = String.format(Locale.US, "%d / %d", page + 1, count);
        pageText.setText(position);
        focusPill.setText(position);
        progressFill.setScaleX((page + 1) / (float) count);
        if (page != bookmarkShownPage) {
            bookmarkShownPage = page;
            updateBookmarkButton();
        }
        requestRender(page - 1);
        requestRender(page + 1);
    }

    // ---------- rendering ----------

    private void requestRender(int page) {
        if (pageWidths == null || page < 0 || page >= pageWidths.length || baseWidth <= 0) return;
        if (cache.get(page) != null || !pending.add(page)) return;
        int expected = generation;
        float side = baseWidth * renderFactor;
        queue.postRunnable(() -> {
            Bitmap bitmap = null;
            if (!closed && renderer != null && Math.abs(page - currentPage) <= NEAR_PAGES) {
                bitmap = render(page, side);
            }
            Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> onRendered(page, result, expected));
        });
    }

    private void requestPageThumbnail(int page, Runnable onReady) {
        if (closed) return;
        if (thumbnails.get(page) != null) {
            onReady.run();
            return;
        }
        ArrayList<Runnable> waiters = thumbnailWaiters.get(page);
        if (waiters != null) {
            waiters.add(onReady);
            return;
        }
        waiters = new ArrayList<>();
        waiters.add(onReady);
        thumbnailWaiters.put(page, waiters);
        thumbnailStack.add(page);
        pumpThumbnails();
    }

    /** One render at a time, newest request first: after a fast fling the cells on screen must not wait for every page passed. */
    private void pumpThumbnails() {
        if (thumbnailRendering || closed || thumbnailStack.isEmpty()) return;
        thumbnailRendering = true;
        int page = thumbnailStack.remove(thumbnailStack.size() - 1);
        queue.postRunnable(() -> {
            Bitmap bitmap = !closed && thumbnailsWanted && renderer != null ? render(page, THUMBNAIL_WIDTH) : null;
            AndroidUtilities.runOnUIThread(() -> {
                thumbnailRendering = false;
                ArrayList<Runnable> ready = thumbnailWaiters.get(page);
                thumbnailWaiters.remove(page);
                if (closed) return;
                if (bitmap != null) thumbnails.put(page, bitmap);
                if (ready != null) {
                    for (int i = 0; i < ready.size(); i++) {
                        ready.get(i).run();
                    }
                }
                pumpThumbnails();
            });
        });
    }

    private Bitmap render(int index, float targetWidth) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            final int realWidth = Math.max(1, page.getWidth()), realHeight = Math.max(1, page.getHeight());
            AndroidUtilities.runOnUIThread(() -> updatePageSize(index, realWidth, realHeight));
            float factor = targetWidth / page.getWidth();
            factor = Math.min(factor, MAX_BITMAP_SIDE / (float) Math.max(page.getWidth(), page.getHeight()));
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * factor)), Math.max(1, (int) (page.getHeight() * factor)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            Matrix matrix = new Matrix();
            matrix.setScale(factor, factor);
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Pages past the first few are sized lazily: fix the layout once the real size is known. */
    private void updatePageSize(int page, int width, int height) {
        if (closed || pageWidths == null || page < 0 || page >= pageWidths.length) return;
        if (pageWidths[page] == width && pageHeights[page] == height) return;
        pageWidths[page] = width;
        pageHeights[page] = height;
        if (listView == null) return;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof PageView && ((PageView) child).page == page) {
                child.requestLayout();
            }
        }
    }

    private void onRendered(int page, Bitmap bitmap, int expected) {
        if (closed || expected != generation) return;
        pending.remove(page);
        if (bitmap == null) return;
        cache.put(page, bitmap);
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof PageView && ((PageView) child).page == page) {
                child.invalidate();
            }
        }
    }

    private void applyRenderFactor() {
        float factor = scale > SHARP_ABOVE_SCALE ? 2.6f : 1.5f;
        if (factor == renderFactor) return;
        renderFactor = factor;
        generation++;
        pending.clear();
        cache.evictAll();
        listView.invalidate();
        for (int i = 0; i < listView.getChildCount(); i++) {
            listView.getChildAt(i).invalidate();
        }
    }

    // ---------- panels ----------

    private void togglePanels() {
        if (pageWidths == null) return;
        panelsVisible = !panelsVisible;
        chromeLayer.setImportantForAccessibility(panelsVisible ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        float topShift = panelsVisible ? 0 : -backButton.getBottom();
        animatePanel(backButton, topShift);
        animatePanel(titleView, topShift);
        if (moreButton != null) animatePanel(moreButton, topShift);
        animatePanel(bottomBar, panelsVisible ? 0 : chromeLayer.getHeight() - bottomBar.getTop());
        focusPill.animate().alpha(panelsVisible ? 0f : 1f).setDuration(220).start();
    }

    private void animatePanel(View view, float translationY) {
        view.animate().translationY(translationY).alpha(panelsVisible ? 1f : 0f).setDuration(220).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
    }

    private boolean isOverChrome(MotionEvent ev) {
        if (!panelsVisible || chromeLayer == null) return false;
        for (int i = 0; i < chromeLayer.getChildCount(); i++) {
            View child = chromeLayer.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE
                    && ev.getX() >= child.getX() && ev.getX() < child.getX() + child.getWidth()
                    && ev.getY() >= child.getY() && ev.getY() < child.getY() + child.getHeight()) {
                return true;
            }
        }
        return false;
    }

    // ---------- lifecycle ----------

    @Override
    public void onResume() {
        super.onResume();
        if (isProtected && getParentActivity() != null) {
            if (flagSecure == null) {
                flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
            }
            flagSecure.attach();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (flagSecure != null) flagSecure.detach();
        if (pageWidths != null) {
            prefs().edit().putInt(positionKey(), currentPage).apply();
        }
    }

    @Override
    public void onFragmentDestroy() {
        closed = true;
        if (flagSecure != null) flagSecure.detach();
        if (morePopup != null && morePopup.isShowing()) morePopup.dismiss();
        cache.evictAll();
        thumbnails.evictAll();
        queue.postRunnable(() -> {
            try {
                if (renderer != null) renderer.close();
                if (descriptor != null) descriptor.close();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            queue.recycle();
        });
        super.onFragmentDestroy();
    }

    // ---------- views ----------

    private class PagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(new PageView(parent.getContext())) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            PageView view = (PageView) holder.itemView;
            view.page = position;
            view.requestLayout();
            view.invalidate();
        }

        @Override
        public int getItemCount() {
            return pageWidths == null ? 0 : pageWidths.length;
        }
    }

    private class PageView extends View {
        int page;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect target = new Rect();

        PageView(Context context) {
            super(context);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = AndroidUtilities.dp(12);
            int halfGap = AndroidUtilities.dp(PAGE_GAP_DP) / 2;
            lp.setMargins(margin, halfGap, margin, halfGap);
            setLayoutParams(lp);
            setBackgroundColor(Color.WHITE);
            setElevation(AndroidUtilities.dp(2));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(14));
                }
            });
            setClipToOutline(true);
            numberPaint.setColor(0x66000000);
            numberPaint.setTextAlign(Paint.Align.CENTER);
            numberPaint.setTextSize(AndroidUtilities.dp(16));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (int) (width * (long) pageHeights[page] / pageWidths[page]);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap bitmap = cache.get(page);
            if (bitmap != null) {
                target.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(bitmap, null, target, bitmapPaint);
            } else {
                canvas.drawText(String.valueOf(page + 1), getWidth() / 2f, getHeight() / 2f, numberPaint);
                requestRender(page);
            }
        }
    }

    /** Pinch and double tap widen the list itself, so vertical scrolling keeps reaching the first and last page. */
    private class ZoomFrame extends FrameLayout {
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private boolean swallowing;
        private float lastX;

        ZoomFrame(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    setScale(scale * detector.getScaleFactor(), detector.getFocusX());
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                    applyRenderFactor();
                }
            });
            tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    togglePanels();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    setScale(scale > 1f ? 1f : 2f, e.getX());
                    applyRenderFactor();
                    return true;
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw) {
                baseWidth = w;
                post(() -> {
                    setScale(1f, 0);
                    cache.evictAll();
                    pending.clear();
                    generation++;
                    updatePosition();
                    requestRender(currentPage);
                });
            }
        }

        private void setScale(float value, float focusX) {
            if (listView == null || baseWidth <= 0) return;
            float next = Math.max(1f, Math.min(MAX_SCALE, value));
            float ratio = next / scale;
            int first = layoutManager.findFirstVisibleItemPosition();
            View firstView = first == RecyclerView.NO_POSITION ? null : layoutManager.findViewByPosition(first);
            int offset = firstView == null ? 0 : (int) ((firstView.getTop() - listView.getPaddingTop()) * ratio);
            float contentX = (focusX - listView.getTranslationX()) / scale;
            scale = next;
            ViewGroup.LayoutParams lp = listView.getLayoutParams();
            lp.width = next <= 1f ? ViewGroup.LayoutParams.MATCH_PARENT : (int) (baseWidth * next);
            listView.setLayoutParams(lp);
            setPan(focusX - contentX * next);
            if (first != RecyclerView.NO_POSITION) {
                layoutManager.scrollToPositionWithOffset(first, offset);
            }
        }

        private void setPan(float x) {
            listView.setTranslationX(Math.max(-(scale - 1f) * baseWidth, Math.min(0, x)));
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && isOverChrome(ev)) {
                touchingChrome = true;
            }
            if (touchingChrome) {
                boolean handled = super.dispatchTouchEvent(ev);
                if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    touchingChrome = false;
                }
                return handled;
            }
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swallowing = false;
                lastX = ev.getX();
            }
            if (ev.getPointerCount() > 1 || scaleDetector.isInProgress()) {
                if (!swallowing) {
                    swallowing = true;
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                return true;
            }
            if (swallowing) return true;
            if (action == MotionEvent.ACTION_MOVE && scale > 1f) {
                setPan(listView.getTranslationX() + ev.getX() - lastX);
            }
            lastX = ev.getX();
            return super.dispatchTouchEvent(ev);
        }

        private boolean touchingChrome;
    }
}
