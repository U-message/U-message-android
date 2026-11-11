package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * U message: iOS-Settings style building blocks for the U message settings pages.
 *
 * Pages keep filling their lists with the ordinary {@link UItem} helpers (asHeader, asButton, asButtonCheck,
 * asCheck, asRadio, asSlideView, asShadow) and then call {@link #apply} once: every such item is swapped for a
 * modern one with the same id, text, state and callbacks, so click handling stays untouched.
 *
 * Section titles and footers sit outside the rounded cards, every row gets a coloured icon tile, switches are
 * iOS-like and the slider becomes a segmented control. The tile colours and the green switch are explicit
 * colours on purpose: they stay coloured under the monochrome theme. Everything else uses theme keys.
 */
public final class UMessageModernList {

    private UMessageModernList() {}

    // iOS system palette
    public static final int INDIGO = 0xFF5856D6;
    public static final int GRAPHITE = 0xFF8E8E93;
    public static final int GREEN = 0xFF34C759;
    public static final int RED = 0xFFFF3B30;
    public static final int ORANGE = 0xFFFF9500;
    public static final int YELLOW = 0xFFFFCC00;
    public static final int BLUE = 0xFF007AFF;
    public static final int TEAL = 0xFF30B0C7;
    public static final int CYAN = 0xFF32ADE6;
    public static final int PINK = 0xFFFF2D55;
    public static final int PURPLE = 0xFFAF52DE;
    public static final int MINT = 0xFF00C7BE;
    public static final int BROWN = 0xFFA2845E;

    public static final int SWITCH_ON = 0xFF34C759;

    public static final int KIND_NAV = 0;     // title (+ value) + chevron
    public static final int KIND_SWITCH = 1;  // title (+ subtitle) + switch
    public static final int KIND_RADIO = 2;   // title + check mark when selected
    public static final int KIND_ACTION = 3;  // red / accent action, no chevron
    public static final int KIND_STATIC = 4;  // read-only value row

    private static final int TILE_DP = 30;
    private static final int ROW_PADDING_DP = 16;
    private static final int TILE_GAP_DP = 12;
    private static final int CARD_MARGIN_DP = 16;

    /** Maps an item to its tile: {iconResId, color}, or null to keep the item's own icon (if any). */
    public interface Icons {
        int[] of(UItem item);
    }

    public static int[] tile(int iconResId, int color) {
        return new int[]{iconResId, color};
    }

    /** Inset grouped cards: 16dp side margins and 16dp corners, no per-cell backgrounds. */
    public static void setup(UniversalRecyclerView listView) {
        listView.adapter.setApplyBackground(false);
        listView.setSections(dp(CARD_MARGIN_DP), dp(16), false);
    }

    /** Refreshes the modern rows when the theme changes (night mode switch, theme preview). */
    public static ArrayList<ThemeDescription> themeDescriptions(View background, RecyclerListView listView) {
        final ArrayList<ThemeDescription> list = new ArrayList<>();
        final ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (background != null) {
                background.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            }
            if (listView != null) {
                listView.invalidateViews();
                listView.invalidate();
            }
        };
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteGrayText));
        list.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_divider));
        return list;
    }

    /** Swaps the plain Telegram items for the modern ones, in place. Unknown items are left as they are. */
    public static void apply(ArrayList<UItem> items, Icons icons) {
        final ArrayList<UItem> out = new ArrayList<>(items.size() + 8);
        for (int i = 0; i < items.size(); i++) {
            final UItem original = items.get(i);
            final UItem converted = convert(original, icons);
            if (converted == null) {
                out.add(original);
                continue;
            }
            final boolean isRow = converted.viewType == RowFactory.viewType();
            // rows that open their own page (navigation, switch + page) need no description: the page explains itself
            if (isRow && (converted.intValue == KIND_NAV || converted.include)) {
                converted.subtext = null;
            }
            final CharSequence description = isRow && converted.intValue == KIND_SWITCH && !converted.include ? converted.subtext : null;
            if (TextUtils.isEmpty(description)) {
                out.add(converted);
                continue;
            }
            // Telegram style: the row keeps only its title, its description goes under the card as a footer
            converted.subtext = null;
            out.add(converted);
            final UItem next = i + 1 < items.size() ? items.get(i + 1) : null;
            if (next != null && next.viewType == UniversalAdapter.VIEW_TYPE_SHADOW) {
                out.add(FooterFactory.of(next.id, TextUtils.isEmpty(next.text) ? description : TextUtils.concat(description, "\n\n", next.text)));
                i++;
            } else {
                out.add(FooterFactory.of(0, description));
            }
        }
        items.clear();
        items.addAll(out);
    }

    private static UItem convert(UItem it, Icons icons) {
        if (it == null) return null;
        int kind;
        int color = 0;
        boolean settingCell = false;
        int icon = it.iconResId;
        switch (it.viewType) {
            case UniversalAdapter.VIEW_TYPE_HEADER:
                return HeaderFactory.of(it.id, it.text);
            case UniversalAdapter.VIEW_TYPE_SHADOW:
                return FooterFactory.of(it.id, it.text);
            case UniversalAdapter.VIEW_TYPE_SLIDE:
                return SegmentedFactory.of(it.id, it.texts, it.intValue, it.intCallback);
            case UniversalAdapter.VIEW_TYPE_TEXT:
                if (it.locked || it.object != null && !(it.object instanceof Drawable)) return null;
                kind = it.red || it.accent ? KIND_ACTION : it.id < 0 ? KIND_STATIC : KIND_NAV;
                break;
            case UniversalAdapter.VIEW_TYPE_TEXT_CHECK:
            case UniversalAdapter.VIEW_TYPE_CHECK:
                kind = KIND_SWITCH;
                break;
            case UniversalAdapter.VIEW_TYPE_RADIO:
                kind = KIND_RADIO;
                break;
            default:
                if (it.viewType >= UItem.factoryViewTypeStartsWith && it.instanceOf(SettingsActivity.SettingCell.Factory.class)) {
                    kind = KIND_NAV;
                    color = (int) it.longValue;
                    settingCell = true;
                    break;
                }
                return null;
        }
        final int[] mapped = icons == null ? null : icons.of(it);
        if (mapped != null) {
            // a row that already had a glyph keeps it; only rows without one take the mapped glyph
            if (icon == 0 && !(it.object instanceof Drawable)) icon = mapped[0];
            color = mapped[1];
        } else if (icon != 0 && color == 0) {
            color = it.red ? RED : GRAPHITE;
        }
        final UItem row = RowFactory.make();
        row.id = it.id;
        row.text = it.text;
        row.subtext = kind == KIND_RADIO && it.subtext == null ? it.textValue : it.subtext;
        row.textValue = kind == KIND_RADIO ? null : it.textValue;
        if (settingCell) {
            // main-page cells: a short status ("On", "@name") becomes the value, a long explanation is dropped
            if (TextUtils.isEmpty(row.textValue) && row.subtext != null && row.subtext.length() <= 18) {
                row.textValue = row.subtext;
            }
            row.subtext = null;
        }
        row.checked = it.checked;
        row.enabled = it.enabled;
        row.red = it.red;
        row.accent = it.accent;
        row.hideDivider = it.hideDivider;
        row.include = it.viewType == UniversalAdapter.VIEW_TYPE_TEXT_CHECK; // switch + its own page: split row
        row.iconResId = icon;
        row.object = icon == 0 && it.object instanceof Drawable ? it.object : null;
        row.intValue = kind;
        row.longValue = color;
        return row;
    }

    static boolean isDark(Theme.ResourcesProvider rp) {
        return AndroidUtilities.computePerceivedBrightness(Theme.getColor(Theme.key_windowBackgroundWhite, rp)) < 0.5f;
    }

    // ---------------------------------------------------------------------------------------------
    // Standalone helpers for pages built from plain layouts (ScrollView + LinearLayout).

    /** A rounded white card with 16dp side margins; add rows to it. */
    public static LinearLayout card(Context context, Theme.ResourcesProvider rp) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setClipToOutline(true);
        return card;
    }

    public static LinearLayout.LayoutParams cardParams() {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, CARD_MARGIN_DP, 0, CARD_MARGIN_DP, 0);
    }

    public static HeaderView header(Context context, CharSequence text, Theme.ResourcesProvider rp) {
        final HeaderView view = new HeaderView(context, rp);
        view.setText(text);
        return view;
    }

    public static FooterView footer(Context context, CharSequence text, Theme.ResourcesProvider rp) {
        final FooterView view = new FooterView(context, rp);
        view.set(text);
        return view;
    }

    /** A row for a {@link #card}; the row draws the list selector itself. */
    public static RowView row(Context context, Theme.ResourcesProvider rp, int kind, int iconResId, int color,
                              CharSequence title, CharSequence subtitle, CharSequence value, boolean checked, boolean divider) {
        final RowView row = new RowView(context, rp);
        row.setBackground(Theme.getSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), false));
        row.set(kind, iconResId, null, color, title, subtitle, value, checked, true, false, false, divider, false);
        return row;
    }

    // ---------------------------------------------------------------------------------------------
    // Views

    /** Coloured rounded-square icon tile with a white glyph. */
    public static class TileView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Drawable icon;
        private int iconRes;

        public TileView(Context context) {
            super(context);
        }

        public void set(int iconResId, Drawable drawable, int color) {
            paint.setColor(color);
            if (drawable != null) {
                icon = drawable.mutate();
                iconRes = 0;
            } else if (iconResId != iconRes || icon == null) {
                iconRes = iconResId;
                final Drawable d = iconResId == 0 ? null : ContextCompat.getDrawable(getContext(), iconResId);
                icon = d == null ? null : d.mutate();
            }
            if (icon != null) {
                icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            }
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, dp(8), dp(8), paint);
            if (icon != null) {
                final int size = dp(24);
                final int l = (getWidth() - size) / 2, t = (getHeight() - size) / 2;
                icon.setBounds(l, t, l + size, t + size);
                icon.draw(canvas);
            }
        }
    }

    /** The "›" on navigation rows. */
    private static class ChevronView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ChevronView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float inset = dp(1.5f);
            path.rewind();
            if (LocaleController.isRTL) {
                path.moveTo(w - inset, inset);
                path.lineTo(inset, h / 2f);
                path.lineTo(w - inset, h - inset);
            } else {
                path.moveTo(inset, inset);
                path.lineTo(w - inset, h / 2f);
                path.lineTo(inset, h - inset);
            }
            canvas.drawPath(path, paint);
        }
    }

    /** The check mark of a selected radio row. */
    private static class CheckMarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float progress;
        private ValueAnimator animator;

        CheckMarkView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setChecked(boolean checked, boolean animated) {
            final float to = checked ? 1f : 0f;
            if (animator != null) animator.cancel();
            if (!animated) {
                progress = to;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, to);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(200);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (progress <= 0) return;
            final float w = getWidth(), h = getHeight();
            path.rewind();
            path.moveTo(w * 0.12f, h * 0.52f);
            path.lineTo(w * 0.40f, h * 0.80f);
            path.lineTo(w * 0.90f, h * 0.20f);
            paint.setAlpha((int) (255 * progress));
            canvas.save();
            final float s = 0.6f + 0.4f * progress;
            canvas.scale(s, s, w / 2f, h / 2f);
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }

    /** iOS-like switch: green track when on, neutral grey when off, white knob, smooth slide. */
    public static class IosSwitch extends View {
        private final Theme.ResourcesProvider resourcesProvider;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean checked;
        private float progress;
        private ValueAnimator animator;

        public IosSwitch(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            knobPaint.setColor(Color.WHITE);
        }

        public boolean isChecked() {
            return checked;
        }

        public void setChecked(boolean checked, boolean animated) {
            if (this.checked == checked && (animator == null || !animator.isRunning()) && progress == (checked ? 1f : 0f)) {
                return;
            }
            this.checked = checked;
            if (animator != null) animator.cancel();
            final float to = checked ? 1f : 0f;
            if (!animated || !isAttachedToWindow()) {
                progress = to;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, to);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(320);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(51), dp(31));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final boolean dark = isDark(resourcesProvider);
            final int off = dark ? 0xFF39393D : 0xFFE3E3E8;
            final float w = getWidth(), h = getHeight();
            trackPaint.setColor(ColorUtils.blendARGB(off, SWITCH_ON, progress));
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint);

            final float pad = dp(2);
            final float r = h / 2f - pad;
            final float p = LocaleController.isRTL ? 1f - progress : progress;
            final float cx = AndroidUtilities.lerp(pad + r, w - pad - r, p);
            final float cy = h / 2f;
            shadowPaint.setColor(dark ? 0x40000000 : 0x26000000);
            canvas.drawCircle(cx, cy + dp(1), r + dp(0.5f), shadowPaint);
            canvas.drawCircle(cx, cy, r, knobPaint);
        }
    }

    /** A settings row: icon tile, title, full multi-line subtitle, and a value + chevron, switch or check mark. */
    public static class RowView extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TileView tileView;
        private final LinearLayout textLayout;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView valueView;
        private final ChevronView chevronView;
        private final IosSwitch switchView;
        private final CheckMarkView checkView;
        private final Paint dividerPaint = new Paint();

        private int kind;
        private boolean hasTile;
        private boolean divider;
        private boolean split; // switch row that also opens a page: a thin bar separates the switch
        private boolean red, accent, rowEnabled = true;
        private int boundId = Integer.MIN_VALUE;
        private CharSequence boundTitle;

        public RowView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
            setMinimumHeight(dp(48));
            setPaddingRelative(dp(ROW_PADDING_DP), 0, dp(ROW_PADDING_DP), 0);
            setWillNotDraw(false);

            tileView = new TileView(context);
            addView(tileView, lp(TILE_DP, TILE_DP, 0, 0, TILE_GAP_DP, 0));

            textLayout = new LinearLayout(context);
            textLayout.setOrientation(VERTICAL);
            textLayout.setLayoutDirection(getLayoutDirection());
            final LayoutParams textParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            textParams.topMargin = dp(8);
            textParams.bottomMargin = dp(8);
            addView(textLayout, textParams);

            final int gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
            titleView.setGravity(gravity);
            titleView.setIncludeFontPadding(false);
            titleView.setLineSpacing(dp(2), 1f);
            textLayout.addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            subtitleView.setGravity(gravity);
            subtitleView.setIncludeFontPadding(false);
            subtitleView.setLineSpacing(dp(2.5f), 1f);
            final LayoutParams subParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            subParams.topMargin = dp(4);
            textLayout.addView(subtitleView, subParams);

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            valueView.setGravity(Gravity.CENTER_VERTICAL);
            valueView.setIncludeFontPadding(false);
            addView(valueView, lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));

            chevronView = new ChevronView(context);
            addView(chevronView, lp(8, 14, 10, 0, 0, 0));

            switchView = new IosSwitch(context, resourcesProvider);
            addView(switchView, lp(51, 31, 24, 0, 0, 0));

            checkView = new CheckMarkView(context);
            addView(checkView, lp(20, 20, 12, 0, 0, 0));

            updateColors();
        }

        private static LayoutParams lp(int w, int h, int start, int top, int end, int bottom) {
            final LayoutParams lp = new LayoutParams(w > 0 ? dp(w) : w, h > 0 ? dp(h) : h);
            lp.setMarginStart(dp(start));
            lp.setMarginEnd(dp(end));
            lp.topMargin = dp(top);
            lp.bottomMargin = dp(bottom);
            lp.gravity = Gravity.CENTER_VERTICAL;
            return lp;
        }

        public IosSwitch getSwitch() {
            return switchView;
        }

        public void setChecked(boolean checked) {
            switchView.setChecked(checked, true);
            checkView.setChecked(checked, true);
        }

        public void setValue(CharSequence value) {
            valueView.setText(value);
            valueView.setVisibility(TextUtils.isEmpty(value) || kind == KIND_SWITCH || kind == KIND_RADIO ? GONE : VISIBLE);
        }

        public void bind(UItem item, boolean divider) {
            final boolean same = boundId == item.id && (item.id != 0 || TextUtils.equals(boundTitle, item.text));
            set(item.intValue, item.iconResId, item.object instanceof Drawable ? (Drawable) item.object : null, (int) item.longValue,
                    item.text, item.subtext, item.textValue, item.checked, item.enabled, item.red, item.accent, divider && !item.hideDivider, same);
            split = item.include && item.intValue == KIND_SWITCH;
            boundId = item.id;
            boundTitle = item.text;
        }

        public void set(int kind, int iconResId, Drawable drawable, int color, CharSequence title, CharSequence subtitle,
                        CharSequence value, boolean checked, boolean enabled, boolean red, boolean accent, boolean divider, boolean animated) {
            this.kind = kind;
            this.red = red;
            this.accent = accent;
            this.rowEnabled = enabled;
            this.divider = divider;
            hasTile = color != 0 && (iconResId != 0 || drawable != null);
            tileView.setVisibility(hasTile ? VISIBLE : GONE);
            if (hasTile) tileView.set(iconResId, drawable, color);

            titleView.setText(title);
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? GONE : VISIBLE);
            setValue(value);
            chevronView.setVisibility(kind == KIND_NAV ? VISIBLE : GONE);
            switchView.setVisibility(kind == KIND_SWITCH ? VISIBLE : GONE);
            checkView.setVisibility(kind == KIND_RADIO ? VISIBLE : GONE);
            switchView.setChecked(checked, animated);
            checkView.setChecked(checked, animated);

            final float alpha = enabled || kind == KIND_STATIC ? 1f : 0.45f;
            tileView.setAlpha(alpha);
            textLayout.setAlpha(alpha);
            valueView.setAlpha(alpha);
            chevronView.setAlpha(alpha);
            switchView.setAlpha(alpha);
            updateColors();
            invalidate();
        }

        @Override
        public void updateColors() {
            final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            titleView.setTextColor(red ? Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)
                    : accent ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider) : black);
            subtitleView.setTextColor(gray);
            valueView.setTextColor(gray);
            chevronView.setColor(ColorUtils.setAlphaComponent(gray, 0x99));
            checkView.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
            dividerPaint.setColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            switchView.invalidate();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width > 0) {
                valueView.setMaxWidth((int) (width * 0.42f));
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            super.dispatchDraw(canvas);
            if (split && switchView.getVisibility() == VISIBLE) {
                final float x = LocaleController.isRTL ? switchView.getRight() + dp(12) : switchView.getLeft() - dp(12);
                final float cy = getHeight() / 2f;
                canvas.drawRect(x - Math.max(1, AndroidUtilities.density * 0.5f), cy - dp(13), x + Math.max(1, AndroidUtilities.density * 0.5f), cy + dp(13), dividerPaint);
            }
            if (divider) {
                final int inset = dp(ROW_PADDING_DP + (hasTile ? TILE_DP + TILE_GAP_DP : 0));
                final float h = Math.max(1, AndroidUtilities.density * 0.5f);
                if (LocaleController.isRTL) {
                    canvas.drawRect(0, getHeight() - h, getWidth() - inset, getHeight(), dividerPaint);
                } else {
                    canvas.drawRect(inset, getHeight() - h, getWidth(), getHeight(), dividerPaint);
                }
            }
        }
    }

    /** Section title above a card. */
    public static class HeaderView extends TextView implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;

        public HeaderView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            setTypeface(AndroidUtilities.bold());
            setLetterSpacing(0.03f);
            setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.BOTTOM);
            setPadding(dp(CARD_MARGIN_DP + ROW_PADDING_DP), dp(14), dp(CARD_MARGIN_DP + ROW_PADDING_DP), dp(6));
            updateColors();
        }

        @Override
        public void updateColors() {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        }
    }

    /** Muted text under a card, or just the gap between two cards. */
    public static class FooterView extends FrameLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView textView;

        public FooterView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            textView.setLineSpacing(dp(2.5f), 1f);
            textView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            textView.setPadding(dp(CARD_MARGIN_DP + ROW_PADDING_DP), dp(6), dp(CARD_MARGIN_DP + ROW_PADDING_DP), dp(2));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            updateColors();
        }

        public void set(CharSequence text) {
            final boolean empty = TextUtils.isEmpty(text);
            textView.setText(text);
            textView.setVisibility(empty ? GONE : VISIBLE);
            setMinimumHeight(dp(empty ? 12 : 0));
            setPadding(0, 0, 0, empty ? 0 : dp(8));
        }

        @Override
        public void updateColors() {
            final int color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, resourcesProvider);
            textView.setTextColor(color);
            textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        }
    }

    /** iOS segmented control: pill track with a sliding selected segment. */
    public static class SegmentedView extends View implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint selectedPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String[] texts = new String[0];
        private int selected;
        private float position;
        private ValueAnimator animator;
        private Utilities.Callback<Integer> callback;
        private int pressedIndex = -1;

        public SegmentedView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            textPaint.setTextSize(dp(13.5f));
            selectedPaint.setTextSize(dp(13.5f));
            selectedPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setTextAlign(Paint.Align.CENTER);
            selectedPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void set(String[] texts, int selected, Utilities.Callback<Integer> callback, boolean animated) {
            final boolean sameTexts = Arrays.equals(this.texts, texts);
            this.texts = texts == null ? new String[0] : texts;
            this.callback = callback;
            setSelected(selected, animated && sameTexts);
        }

        private void setSelected(int index, boolean animated) {
            if (texts.length == 0) return;
            index = Math.max(0, Math.min(texts.length - 1, index));
            if (index == selected && (animator == null || !animator.isRunning()) && position == index) {
                invalidate();
                return;
            }
            selected = index;
            if (animator != null) animator.cancel();
            if (!animated || !isAttachedToWindow()) {
                position = index;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(position, index);
            animator.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(340);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(58));
        }

        private float trackLeft() { return dp(14); }
        private float trackRight() { return getWidth() - dp(14); }

        private int indexAt(float x) {
            final int n = texts.length;
            if (n == 0) return -1;
            final float w = (trackRight() - trackLeft()) / n;
            int i = (int) ((x - trackLeft()) / w);
            i = Math.max(0, Math.min(n - 1, i));
            return LocaleController.isRTL ? n - 1 - i : i;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // the list marks non-clickable rows as disabled; the control handles its own taps, so ignore that
            if (texts.length == 0) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressedIndex = indexAt(event.getX());
                    getParent().requestDisallowInterceptTouchEvent(true); // the list must not steal the tap
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP: {
                    final int index = indexAt(event.getX());
                    pressedIndex = -1;
                    if (index >= 0 && index != selected) {
                        setSelected(index, true);
                        try {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                        } catch (Exception ignore) {}
                        if (callback != null) callback.run(index);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    pressedIndex = -1;
                    invalidate();
                    return true;
            }
            return true;
        }

        @Override
        public void updateColors() {
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int n = texts.length;
            if (n == 0) return;
            final boolean dark = isDark(resourcesProvider);
            final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            trackPaint.setColor(dark ? 0x3D767680 : 0x1F767680);
            thumbPaint.setColor(dark ? 0xFF636366 : Color.WHITE);
            shadowPaint.setColor(dark ? 0x30000000 : 0x1A000000);
            final int separatorColor = dark ? 0x40FFFFFF : 0x30000000;
            textPaint.setColor(black);
            selectedPaint.setColor(dark ? Color.WHITE : black);

            final float left = trackLeft(), right = trackRight();
            final float top = dp(12), bottom = getHeight() - dp(12);
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, dp(9), dp(9), trackPaint);

            final float segment = (right - left) / n;
            final float visualPos = LocaleController.isRTL ? (n - 1 - position) : position;

            // separators between unselected segments
            for (int b = 1; b < n; b++) {
                final float d = Math.min(Math.abs(b - visualPos), Math.abs(b - visualPos - 1));
                final boolean inside = b > visualPos && b < visualPos + 1;
                final float a = inside ? 0 : Math.min(1f, d * 2f);
                if (a <= 0) continue;
                separatorPaint.setColor(ColorUtils.setAlphaComponent(separatorColor, (int) (Color.alpha(separatorColor) * a)));
                final float x = left + segment * b;
                canvas.drawRect(x - dp(0.5f), top + dp(8), x + dp(0.5f), bottom - dp(8), separatorPaint);
            }

            // sliding thumb
            final float inset = dp(2);
            final float tl = left + segment * visualPos + inset;
            rect.set(tl, top + inset, tl + segment - inset * 2, bottom - inset);
            if (!dark) {
                rect.offset(0, dp(1));
                canvas.drawRoundRect(rect, dp(7), dp(7), shadowPaint);
                rect.offset(0, -dp(1));
            }
            float scale = 1f;
            final int pressedVisual = pressedIndex < 0 ? -1 : (LocaleController.isRTL ? n - 1 - pressedIndex : pressedIndex);
            if (pressedIndex == selected) scale = 0.96f;
            canvas.save();
            canvas.scale(scale, scale, rect.centerX(), rect.centerY());
            canvas.drawRoundRect(rect, dp(7), dp(7), thumbPaint);
            canvas.restore();

            // labels
            final float cy = (top + bottom) / 2f;
            for (int i = 0; i < n; i++) {
                final int visual = LocaleController.isRTL ? n - 1 - i : i;
                final float cx = left + segment * visual + segment / 2f;
                final float closeness = Math.max(0f, 1f - Math.abs(visual - visualPos));
                final TextPaint paint = closeness > 0.5f ? selectedPaint : textPaint;
                final CharSequence label = TextUtils.ellipsize(texts[i], paint, segment - dp(10), TextUtils.TruncateAt.END);
                final float baseline = cy - (paint.descent() + paint.ascent()) / 2f;
                final int baseAlpha = Color.alpha(paint.getColor());
                paint.setAlpha((int) (baseAlpha * (visual == pressedVisual && i != selected ? 0.55f : 1f)));
                canvas.drawText(label, 0, label.length(), cx, baseline, paint);
                paint.setAlpha(baseAlpha);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // UniversalAdapter factories

    /** RecyclerView's default params are wrap_content; rows must always fill the card's full width. */
    private static <V extends View> V fullWidth(V view) {
        view.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(
                androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT, androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT));
        return view;
    }

    public static class RowFactory extends UItem.UItemFactory<RowView> {
        static { setup(new RowFactory()); }

        static UItem make() {
            return UItem.ofFactory(RowFactory.class);
        }

        static int viewType() {
            return UItem.getFactory(RowFactory.class).viewType;
        }

        @Override
        public RowView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return fullWidth(new RowView(context, resourcesProvider));
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((RowView) view).bind(item, divider);
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id && (a.id != 0 || TextUtils.equals(a.text, b.text));
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.intValue == b.intValue && a.longValue == b.longValue && a.iconResId == b.iconResId
                    && a.checked == b.checked && a.enabled == b.enabled && a.red == b.red && a.accent == b.accent
                    && a.hideDivider == b.hideDivider && a.object == b.object
                    && TextUtils.equals(a.text, b.text) && TextUtils.equals(a.subtext, b.subtext) && TextUtils.equals(a.textValue, b.textValue);
        }
    }

    public static class HeaderFactory extends UItem.UItemFactory<HeaderView> {
        static { setup(new HeaderFactory()); }

        public static UItem of(int id, CharSequence text) {
            final UItem item = UItem.ofFactory(HeaderFactory.class);
            item.id = id;
            item.text = text;
            return item;
        }

        @Override
        public HeaderView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return fullWidth(new HeaderView(context, resourcesProvider));
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((HeaderView) view).setText(item.text);
            ((HeaderView) view).updateColors();
        }

        @Override
        public boolean isShadow() {
            return true;
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return TextUtils.equals(a.text, b.text);
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return TextUtils.equals(a.text, b.text);
        }
    }

    public static class FooterFactory extends UItem.UItemFactory<FooterView> {
        static { setup(new FooterFactory()); }

        public static UItem of(int id, CharSequence text) {
            final UItem item = UItem.ofFactory(FooterFactory.class);
            item.id = id;
            item.text = text;
            return item;
        }

        @Override
        public FooterView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return fullWidth(new FooterView(context, resourcesProvider));
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((FooterView) view).set(item.text);
            ((FooterView) view).updateColors();
        }

        @Override
        public boolean isShadow() {
            return true;
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id && TextUtils.equals(a.text, b.text);
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return TextUtils.equals(a.text, b.text);
        }
    }

    public static class SegmentedFactory extends UItem.UItemFactory<SegmentedView> {
        static { setup(new SegmentedFactory()); }

        public static UItem of(int id, String[] texts, int selected, Utilities.Callback<Integer> callback) {
            final UItem item = UItem.ofFactory(SegmentedFactory.class);
            item.id = id;
            item.texts = texts;
            item.intValue = selected;
            item.intCallback = callback;
            return item;
        }

        @Override
        public SegmentedView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return fullWidth(new SegmentedView(context, resourcesProvider));
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((SegmentedView) view).set(item.texts, item.intValue, item.intCallback, true);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id && Arrays.equals(a.texts, b.texts);
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            // always rebind: a refused choice (e.g. a parental lock) has to slide back even if the value did not change
            return false;
        }
    }
}
