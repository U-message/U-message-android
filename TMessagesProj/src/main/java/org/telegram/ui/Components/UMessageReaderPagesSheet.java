package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageReaderBookmarks;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;

public class UMessageReaderPagesSheet extends BottomSheet {

    public interface Delegate {
        int getPageCount();
        int getCurrentPage();
        long getDocumentId();
        /** @return cached thumbnail or null */
        Bitmap getThumbnail(int page);
        /** Renders off the UI thread if needed; onReady runs on the UI thread. */
        void requestThumbnail(int page, Runnable onReady);
        void onPageSelected(int page);
        void onBookmarksChanged();
    }

    private static final int COLUMNS = 3;
    private static final float PAGE_ASPECT = 0.72f;
    private static final int TAB_ALL = 0;
    private static final int TAB_BOOKMARKS = 1;

    // A cell owns half of each gap so the ring and shadow can draw past the tile without clipping.
    private static final int CELL_INSET_X = 7;
    private static final int CELL_INSET_Y = 8;
    private static final int LIST_SIDE_PADDING = 20 - CELL_INSET_X;

    private final Delegate pagesDelegate;
    private final int pageCount;
    private final int currentPage;
    private final long documentId;

    private final RecyclerListView listView;
    private final GridLayoutManager layoutManager;
    private final PagesAdapter adapter;
    private final TextView emptyView;
    private final int[] location = new int[2];

    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint currentLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable bookmarkIcon;

    private ArrayList<Integer> bookmarks;
    private int tab = TAB_ALL;
    private boolean dragsSheet = true;

    public UMessageReaderPagesSheet(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context, false, resourcesProvider);
        pagesDelegate = delegate;
        pageCount = Math.max(0, delegate.getPageCount());
        currentPage = delegate.getCurrentPage();
        documentId = delegate.getDocumentId();
        bookmarks = loadBookmarks();

        int background = getThemedColor(Theme.key_windowBackgroundGray);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        int textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        int grayColor = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
        int controlTint = ColorUtils.setAlphaComponent(grayColor, 36);

        tilePaint.setColor(Color.WHITE);
        ringPaint.setColor(accent);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(AndroidUtilities.dpf2(2.5f));
        pillPaint.setColor(accent);
        labelPaint.setColor(grayColor);
        labelPaint.setTextSize(dp(13));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        currentLabelPaint.setColor(Color.WHITE);
        currentLabelPaint.setTextSize(dp(13));
        currentLabelPaint.setTextAlign(Paint.Align.CENTER);
        currentLabelPaint.setTypeface(AndroidUtilities.bold());
        bookmarkIcon = context.getResources().getDrawable(R.drawable.msg_saved).mutate();
        bookmarkIcon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));

        // The stock sheet background has a fixed corner radius, so the sheet supplies its own containerView.
        backgroundPaddingLeft = 0;
        backgroundPaddingTop = 0;
        setBackgroundColor(background);
        fixNavigationBar(background);

        LinearLayout root = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = Math.min(MeasureSpec.getSize(heightMeasureSpec), (int) (AndroidUtilities.displaySize.y * 0.85f));
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBackground = new GradientDrawable();
        rootBackground.setColor(background);
        float radius = dp(28);
        rootBackground.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        root.setBackground(rootBackground);
        containerView = root;

        View grabber = new View(context);
        grabber.setBackground(Theme.createRoundRectDrawable(dp(3), ColorUtils.setAlphaComponent(grayColor, 72)));
        root.addView(grabber, LayoutHelper.createLinear(38, 5, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 12));

        FrameLayout header = new FrameLayout(context);
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY, 20, 4, 20, 14));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        int textGravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        header.addView(titles, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, textGravity | Gravity.TOP, LocaleController.isRTL ? 52 : 0, 0, LocaleController.isRTL ? 0 : 52, 0));

        TextView titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.UMessageReaderPages));
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setLetterSpacing(-0.02f);
        titleView.setGravity(textGravity);
        titleView.setSingleLine(true);
        titles.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView countView = new TextView(context);
        countView.setText(LocaleController.formatString(R.string.UMessageReaderPageCount, pageCount));
        countView.setTextColor(grayColor);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        countView.setGravity(textGravity);
        countView.setSingleLine(true);
        titles.addView(countView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY, 0, 2, 0, 0));

        ImageView closeButton = new ImageView(context);
        closeButton.setImageResource(R.drawable.msg_close);
        closeButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        closeButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        closeButton.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));
        closeButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(44), controlTint, ColorUtils.setAlphaComponent(grayColor, 72)));
        closeButton.setContentDescription(LocaleController.getString(R.string.Close));
        closeButton.setOnClickListener(v -> dismiss());
        header.addView(closeButton, LayoutHelper.createFrame(44, 44, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP));

        SegmentedControl segments = new SegmentedControl(context, controlTint, textColor, grayColor);
        root.addView(segments, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 0));

        FrameLayout content = new FrameLayout(context);
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context, resourcesProvider);
        listView.setClipToPadding(false);
        listView.setPadding(dp(LIST_SIDE_PADDING), dp(20 - CELL_INSET_Y), dp(LIST_SIDE_PADDING), dp(8));
        listView.setItemAnimator(null);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listView.setSelectorDrawableColor(0);
        listView.setLayoutManager(layoutManager = new GridLayoutManager(context, COLUMNS));
        listView.setAdapter(adapter = new PagesAdapter());
        listView.setOnItemClickListener((view, position) -> {
            if (view instanceof PageCell) {
                pagesDelegate.onPageSelected(((PageCell) view).page);
                dismiss();
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (!(view instanceof PageCell)) {
                return false;
            }
            toggleBookmark((PageCell) view, position);
            return true;
        });
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.UMessageReaderNoBookmarks));
        emptyView.setTextColor(grayColor);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), 0, dp(32), dp(32));
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        scrollToCurrentPage();
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
        // Decided once per gesture: the sheet's drag and nested scrolling are mutually exclusive and must not swap mid-drag.
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragsSheet = !listView.canScrollVertically(-1) || !isOverList(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return dragsSheet;
    }

    private boolean isOverList(MotionEvent ev) {
        listView.getLocationInWindow(location);
        float x = ev.getX() - location[0];
        float y = ev.getY() - location[1];
        return x >= 0 && x < listView.getWidth() && y >= 0 && y < listView.getHeight();
    }

    private ArrayList<Integer> loadBookmarks() {
        ArrayList<Integer> stored = UMessageReaderBookmarks.list(documentId);
        for (int i = stored.size() - 1; i >= 0; i--) {
            if (stored.get(i) >= pageCount) {
                stored.remove(i);
            }
        }
        return stored;
    }

    private void scrollToCurrentPage() {
        if (currentPage >= 0 && currentPage < pageCount) {
            layoutManager.scrollToPositionWithOffset(currentPage, dp(48));
        }
    }

    private void setTab(int newTab) {
        if (tab == newTab) {
            return;
        }
        tab = newTab;
        adapter.notifyDataSetChanged();
        if (tab == TAB_ALL) {
            scrollToCurrentPage();
        } else {
            layoutManager.scrollToPositionWithOffset(0, 0);
        }
        updateEmptyView();
    }

    private void updateEmptyView() {
        emptyView.setVisibility(tab == TAB_BOOKMARKS && bookmarks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void toggleBookmark(PageCell cell, int position) {
        boolean bookmarked = UMessageReaderBookmarks.toggle(documentId, cell.page);
        bookmarks = loadBookmarks();
        cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (tab == TAB_BOOKMARKS) {
            adapter.notifyItemRemoved(position);
            updateEmptyView();
        } else {
            cell.setBookmarked(bookmarked);
        }
        pagesDelegate.onBookmarksChanged();
    }

    private class PagesAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return tab == TAB_ALL ? pageCount : bookmarks.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new PageCell(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((PageCell) holder.itemView).bind(tab == TAB_ALL ? position : bookmarks.get(position));
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            ((PageCell) holder.itemView).page = -1;
        }
    }

    private class PageCell extends View {

        private final RectF tile = new RectF();
        private final RectF ring = new RectF();
        private final RectF pill = new RectF();
        private final RectF bitmapRect = new RectF();
        private final Path tilePath = new Path();

        int page = -1;
        private String label = "";
        private float labelWidth;
        private boolean bookmarked;
        private boolean thumbnailRequested;

        PageCell(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Casts the shadow from the tile only, not from the page number below it.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect((int) tile.left, (int) tile.top, (int) tile.right, (int) tile.bottom, dp(10));
                }
            });
            setElevation(dp(2));
        }

        void bind(int page) {
            this.page = page;
            label = String.valueOf(page + 1);
            labelWidth = currentLabelPaint.measureText(label);
            bookmarked = isBookmarked(page);
            thumbnailRequested = false;
            setContentDescription(LocaleController.formatString(R.string.UMessageReaderPageLabel, page + 1));
            if (thumbnail() == null) {
                requestThumbnail();
            }
            invalidate();
        }

        void setBookmarked(boolean value) {
            bookmarked = value;
            invalidate();
        }

        private boolean isBookmarked(int page) {
            return Collections.binarySearch(bookmarks, page) >= 0;
        }

        private Bitmap thumbnail() {
            Bitmap bitmap = pagesDelegate.getThumbnail(page);
            return bitmap == null || bitmap.isRecycled() ? null : bitmap;
        }

        private void requestThumbnail() {
            thumbnailRequested = true;
            final int requestedPage = page;
            pagesDelegate.requestThumbnail(requestedPage, () -> {
                if (page == requestedPage) {
                    invalidate();
                }
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int tileHeight = (int) ((width - dp(CELL_INSET_X * 2)) / PAGE_ASPECT);
            setMeasuredDimension(width, tileHeight + dp(CELL_INSET_Y * 2 + 7 + 22));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            tile.set(dp(CELL_INSET_X), dp(CELL_INSET_Y), w - dp(CELL_INSET_X), h - dp(CELL_INSET_Y + 7 + 22));
            float ringOffset = dp(2) + ringPaint.getStrokeWidth() / 2f;
            ring.set(tile);
            ring.inset(-ringOffset, -ringOffset);
            tilePath.rewind();
            tilePath.addRoundRect(tile, dp(10), dp(10), Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (page < 0) {
                return;
            }
            float tileRadius = dp(10);
            canvas.drawRoundRect(tile, tileRadius, tileRadius, tilePaint);

            Bitmap bitmap = thumbnail();
            if (bitmap != null) {
                float scale = Math.min(tile.width() / bitmap.getWidth(), tile.height() / bitmap.getHeight());
                float width = bitmap.getWidth() * scale;
                float height = bitmap.getHeight() * scale;
                bitmapRect.set(tile.centerX() - width / 2f, tile.centerY() - height / 2f, tile.centerX() + width / 2f, tile.centerY() + height / 2f);
                canvas.save();
                canvas.clipPath(tilePath);
                canvas.drawBitmap(bitmap, null, bitmapRect, bitmapPaint);
                canvas.restore();
            } else if (!thumbnailRequested) {
                // The delegate's cache can evict a thumbnail that was present at bind time.
                requestThumbnail();
            }

            if (bookmarked) {
                int right = (int) tile.right - dp(8);
                int top = (int) tile.top;
                bookmarkIcon.setBounds(right - dp(18), top, right, top + dp(18));
                bookmarkIcon.draw(canvas);
            }

            float centerX = tile.centerX();
            float centerY = tile.bottom + dp(7 + 11);
            if (page == currentPage) {
                float ringRadius = tileRadius + dp(2) + ringPaint.getStrokeWidth() / 2f;
                canvas.drawRoundRect(ring, ringRadius, ringRadius, ringPaint);
                float pillWidth = Math.max(dp(22), labelWidth + dp(14));
                pill.set(centerX - pillWidth / 2f, centerY - dp(11), centerX + pillWidth / 2f, centerY + dp(11));
                canvas.drawRoundRect(pill, dp(11), dp(11), pillPaint);
                drawLabel(canvas, currentLabelPaint, centerX, centerY);
            } else {
                drawLabel(canvas, labelPaint, centerX, centerY);
            }
        }

        private void drawLabel(Canvas canvas, TextPaint paint, float x, float centerY) {
            canvas.drawText(label, x, centerY - (paint.ascent() + paint.descent()) / 2f, paint);
        }
    }

    private class SegmentedControl extends LinearLayout {

        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF activeRect = new RectF();
        private final TextView[] labels = new TextView[2];
        private final int textColor;
        private final int grayColor;
        private float progress;
        private ValueAnimator animator;

        SegmentedControl(Context context, int trackColor, int textColor, int grayColor) {
            super(context);
            this.textColor = textColor;
            this.grayColor = grayColor;
            setOrientation(HORIZONTAL);
            setPadding(dp(2), dp(2), dp(2), dp(2));
            setBackground(Theme.createRoundRectDrawable(dp(12), trackColor));
            activePaint.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
            activePaint.setShadowLayer(dp(2), 0, dp(1), 0x24000000);
            addSegment(context, TAB_ALL, R.string.UMessageReaderAll);
            addSegment(context, TAB_BOOKMARKS, R.string.UMessageReaderBookmarks);
            updateLabels();
        }

        private void addSegment(Context context, int index, int textRes) {
            TextView label = new TextView(context);
            label.setText(LocaleController.getString(textRes));
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setTypeface(AndroidUtilities.bold());
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setOnClickListener(v -> select(index));
            labels[index] = label;
            addView(label, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, index == 0 ? 0 : 2, 0, 0, 0));
        }

        private void select(int index) {
            if (tab == index) {
                return;
            }
            setTab(index);
            updateLabels();
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(progress, index);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(220);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        private void updateLabels() {
            for (int i = 0; i < labels.length; i++) {
                labels[i].setTextColor(i == tab ? textColor : grayColor);
                labels[i].setSelected(i == tab);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            float width = labels[0].getWidth();
            float left = labels[0].getLeft() + (labels[1].getLeft() - labels[0].getLeft()) * progress;
            activeRect.set(left, labels[0].getTop(), left + width, labels[0].getBottom());
            canvas.drawRoundRect(activeRect, dp(10), dp(10), activePaint);
            super.dispatchDraw(canvas);
        }
    }
}
