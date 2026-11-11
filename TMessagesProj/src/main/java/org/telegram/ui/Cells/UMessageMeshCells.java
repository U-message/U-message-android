package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MsgClockDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/** Telegram-style cells for the offline (mesh) chat screens. */
public final class UMessageMeshCells {

    private UMessageMeshCells() {
    }

    /** RTL-aware frame params: margins are given as start / end. */
    static FrameLayout.LayoutParams frame(int w, int h, int vGravity, float start, float top, float end, float bottom) {
        boolean rtl = LocaleController.isRTL;
        return LayoutHelper.createFrame(w, h, vGravity | (rtl ? Gravity.RIGHT : Gravity.LEFT), rtl ? end : start, top, rtl ? start : end, bottom);
    }

    static FrameLayout.LayoutParams frameEnd(int w, int h, int vGravity, float top, float end, float bottom) {
        boolean rtl = LocaleController.isRTL;
        return LayoutHelper.createFrame(w, h, vGravity | (rtl ? Gravity.LEFT : Gravity.RIGHT), rtl ? end : 0, top, rtl ? 0 : end, bottom);
    }

    /* Delivery ticks: queued clock / one check (relayed) / two checks (delivered). */

    public static final class Ticks {
        public static final int NONE = -1, QUEUED = 0, SENT = 1, DELIVERED = 2;
        private final Drawable check, half;
        private final MsgClockDrawable clock = new MsgClockDrawable();

        public Ticks(Context context) {
            check = context.getResources().getDrawable(R.drawable.msg_check_s).mutate();
            half = context.getResources().getDrawable(R.drawable.msg_halfcheck).mutate();
        }

        public int width(int status) {
            if (status == QUEUED) return clock.getIntrinsicWidth();
            if (status == SENT) return check.getIntrinsicWidth();
            if (status == DELIVERED) return check.getIntrinsicWidth() + dp(4);
            return 0;
        }

        /** Draws with the left edge at x, vertically centred on cy. */
        public void draw(Canvas canvas, int status, int x, int cy, int color) {
            if (status == QUEUED) {
                clock.setColor(color);
                int s = clock.getIntrinsicWidth();
                clock.setBounds(x, cy - s / 2, x + s, cy + s / 2);
                clock.draw(canvas);
            } else if (status == SENT || status == DELIVERED) {
                check.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                int w = check.getIntrinsicWidth(), h = check.getIntrinsicHeight();
                check.setBounds(x, cy - h / 2, x + w, cy - h / 2 + h);
                check.draw(canvas);
                if (status == DELIVERED) {
                    half.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                    int hw = half.getIntrinsicWidth(), hh = half.getIntrinsicHeight();
                    int hx = x + dp(4);
                    half.setBounds(hx, cy - hh / 2, hx + hw, cy - hh / 2 + hh);
                    half.draw(canvas);
                }
            }
        }
    }

    public static class TicksView extends View {
        private final Ticks ticks;
        private int status = Ticks.NONE;
        private int color;

        public TicksView(Context context) {
            super(context);
            ticks = new Ticks(context);
        }

        public void set(int status, int color) {
            this.status = status;
            this.color = color;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(status == Ticks.NONE ? 0 : ticks.width(status) + dp(2), dp(16));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            ticks.draw(canvas, status, 0, getMeasuredHeight() / 2, color);
        }
    }

    /* Hero: big round icon, title and a one-line status, like Telegram feature headers. */

    public static class HeroCell extends LinearLayout {
        private final Theme.ResourcesProvider resourcesProvider;
        private final PulseView pulse;
        private final TextView title, status;

        public HeroCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, dp(8), 0, dp(20));

            pulse = new PulseView(context);
            addView(pulse, LayoutHelper.createLinear(132, 132, Gravity.CENTER_HORIZONTAL));

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 0, 24, 0));

            status = new TextView(context);
            status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            status.setEllipsize(TextUtils.TruncateAt.END);
            addView(status, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 6, 24, 0));
        }

        public void set(CharSequence titleText, CharSequence statusText, boolean active) {
            title.setText(titleText);
            status.setText(statusText);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            status.setTextColor(Theme.getColor(active ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            pulse.setActive(active);
        }

        private static class PulseView extends FrameLayout {
            private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final ImageView icon;
            private ValueAnimator animator;
            private float phase;
            private boolean active;
            private int lastSize;

            PulseView(Context context) {
                super(context);
                setWillNotDraw(false);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(dp(2));
                icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageResource(R.drawable.filled_calls_bluetooth_s);
                icon.setColorFilter(0xFFFFFFFF);
                addView(icon, LayoutHelper.createFrame(46, 46, Gravity.CENTER));
            }

            void setActive(boolean value) {
                if (active == value) return;
                active = value;
                updateAnimator();
                invalidate();
            }

            private void updateAnimator() {
                boolean run = active && isAttachedToWindow();
                if (run && animator == null) {
                    animator = ValueAnimator.ofFloat(0, 1);
                    animator.setDuration(2400);
                    animator.setRepeatCount(ValueAnimator.INFINITE);
                    animator.setInterpolator(new LinearInterpolator());
                    animator.addUpdateListener(a -> {
                        phase = (float) a.getAnimatedValue();
                        invalidate();
                    });
                    animator.start();
                } else if (!run && animator != null) {
                    animator.cancel();
                    animator = null;
                    phase = 0;
                }
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                updateAnimator();
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                updateAnimator();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = dp(42);
                int top = active ? org.telegram.ui.Components.IconBackgroundColors.BLUE.top : org.telegram.ui.Components.IconBackgroundColors.GRAY.top;
                int bottom = active ? org.telegram.ui.Components.IconBackgroundColors.BLUE.bottom : org.telegram.ui.Components.IconBackgroundColors.GRAY.bottom;
                if (lastSize != (top ^ getHeight())) {
                    lastSize = top ^ getHeight();
                    circle.setShader(new LinearGradient(0, cy - r, 0, cy + r, top, bottom, Shader.TileMode.CLAMP));
                }
                if (active) {
                    for (int i = 0; i < 2; i++) {
                        float p = (phase + i * 0.5f) % 1f;
                        ring.setColor(ColorUtils.setAlphaComponent(top, (int) (110 * (1f - p))));
                        canvas.drawCircle(cx, cy, r + dp(22) * p, ring);
                    }
                }
                canvas.drawCircle(cx, cy, r, circle);
            }
        }

        public static class Factory extends UItem.UItemFactory<HeroCell> {
            static { setup(new Factory()); }

            @Override
            public HeroCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new HeroCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((HeroCell) view).set(item.text, item.subtext, item.intValue == 1);
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            public static UItem of(CharSequence title, CharSequence status, boolean active) {
                UItem item = UItem.ofFactory(Factory.class);
                item.text = title;
                item.subtext = status;
                item.intValue = active ? 1 : 0;
                return item;
            }
        }
    }

    /* Settings row with a coloured rounded-square icon and a switch (SettingCell look + TextCheckCell switch). */

    public static class ToggleCell extends LinearLayout {
        private final Theme.ResourcesProvider resourcesProvider;
        private final SettingsActivity.SettingCell.Background iconBackground;
        private final ImageView iconView;
        private final TextView titleView, subtitleView;
        private final Switch switchView;
        private boolean twoLines, divider;

        public ToggleCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);
            setWillNotDraw(false);

            FrameLayout iconLayout = new FrameLayout(context);
            iconLayout.setBackground(iconBackground = new SettingsActivity.SettingCell.Background());
            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconLayout.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

            LinearLayout textLayout = new LinearLayout(context);
            textLayout.setOrientation(VERTICAL);
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            switchView = new Switch(context, resourcesProvider);
            switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            switchView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

            if (LocaleController.isRTL) {
                addView(switchView, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL, 22, 0, 0, 0));
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL, 16, 0, 18, 0));
                addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
            } else {
                addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL, 18, 0, 16, 0));
                addView(switchView, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL, 0, 0, 22, 0));
            }
        }

        public void set(int top, int bottom, int icon, CharSequence title, CharSequence subtitle, boolean checked, boolean divider) {
            boolean animated = getTag() != null && TextUtils.equals((CharSequence) getTag(), title);
            setTag(title);
            iconBackground.setColor(top, bottom);
            iconBackground.setDrawBorder(resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark());
            iconView.setImageResource(icon);
            iconView.setColorFilter(0xFFFFFFFF);
            titleView.setText(title);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            twoLines = !TextUtils.isEmpty(subtitle);
            subtitleView.setVisibility(twoLines ? VISIBLE : GONE);
            subtitleView.setText(subtitle);
            switchView.setChecked(checked, animated);
            this.divider = divider;
            setContentDescription(title);
            invalidate();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(switchView.isChecked());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(twoLines ? 60 : 50), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (divider) {
                Paint p = Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider);
                if (p != null) {
                    canvas.drawLine(LocaleController.isRTL ? 0 : dp(64), getHeight() - 1, getWidth() - (LocaleController.isRTL ? dp(64) : 0), getHeight() - 1, p);
                }
            }
        }

        public static class Factory extends UItem.UItemFactory<ToggleCell> {
            static { setup(new Factory()); }

            @Override
            public ToggleCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new ToggleCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((ToggleCell) view).set((int) item.longValue, (int) (item.longValue >>> 32), item.iconResId, item.text, item.subtext, item.checked, divider);
            }

            public static UItem of(int id, int top, int bottom, int icon, CharSequence title, CharSequence subtitle, boolean checked) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.iconResId = icon;
                item.text = title;
                item.subtext = subtitle;
                item.checked = checked;
                item.intValue = checked ? 1 : 0; // part of itemEquals, so a flip re-binds
                item.longValue = ((long) bottom << 32) | (top & 0xFFFFFFFFL);
                return item;
            }
        }
    }

    /* Dialog row: avatar, bold name, preview, time, ticks, unread badge. Also used as a contact row. */

    public static class DialogCell extends FrameLayout {
        private final Theme.ResourcesProvider resourcesProvider;
        private final int currentAccount;
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView name, time, preview, badge;
        private final TicksView ticks;
        private final LinearLayout topRow, bottomRow;
        private boolean divider, contact;

        public DialogCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.currentAccount = currentAccount;
            this.resourcesProvider = resourcesProvider;
            setWillNotDraw(false);

            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(27));
            addView(avatar, frame(54, 54, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

            boolean rtl = LocaleController.isRTL;
            topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTypeface(AndroidUtilities.bold());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
            ticks = new TicksView(context);
            time = new TextView(context);
            time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            time.setSingleLine(true);
            if (rtl) {
                topRow.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                topRow.addView(ticks, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 16, Gravity.CENTER_VERTICAL, 3, 0, 0, 0));
                topRow.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 8, 0, 0, 0));
            } else {
                topRow.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));
                topRow.addView(ticks, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 16, Gravity.CENTER_VERTICAL, 0, 0, 3, 0));
                topRow.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }
            addView(topRow, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 76, 11, 14, 0));

            bottomRow = new LinearLayout(context);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            preview = new TextView(context);
            preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            preview.setSingleLine(true);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            preview.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
            badge = new TextView(context);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(22));
            badge.setPadding(dp(7), 0, dp(7), 0);
            if (rtl) {
                bottomRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 22, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
                bottomRow.addView(preview, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            } else {
                bottomRow.addView(preview, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                bottomRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 22, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            }
            addView(bottomRow, frame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 76, 37, 14, 0));
        }

        /**
         * @param status Ticks.* for an own last message, Ticks.NONE otherwise
         * @param unread unread counter, 0 hides the badge
         */
        public void set(TLRPC.User user, CharSequence title, CharSequence subtitle, CharSequence timeText, int status, int unread, boolean contact, boolean divider) {
            this.contact = contact;
            this.divider = divider;
            avatarDrawable.setInfo(currentAccount, user);
            avatar.setForUserOrChat(user, avatarDrawable);
            name.setText(title);
            name.setTextColor(Theme.getColor(Theme.key_chats_name, resourcesProvider));
            preview.setText(subtitle);
            preview.setTextColor(Theme.getColor(Theme.key_chats_message, resourcesProvider));
            time.setText(timeText);
            time.setVisibility(TextUtils.isEmpty(timeText) ? GONE : VISIBLE);
            time.setTextColor(Theme.getColor(Theme.key_chats_date, resourcesProvider));
            ticks.set(status, Theme.getColor(status == Ticks.QUEUED ? Theme.key_chats_sentClock : Theme.key_chats_sentCheck, resourcesProvider));
            ticks.setVisibility(status == Ticks.NONE ? GONE : VISIBLE);
            if (unread > 0) {
                badge.setVisibility(VISIBLE);
                badge.setText(unread > 999 ? "999+" : String.valueOf(unread));
                badge.setTextColor(Theme.getColor(Theme.key_chats_unreadCounterText, resourcesProvider));
                badge.setBackground(Theme.createRoundRectDrawable(dp(11), Theme.getColor(Theme.key_chats_unreadCounter, resourcesProvider)));
            } else {
                badge.setVisibility(GONE);
            }
            ((LayoutParams) bottomRow.getLayoutParams()).topMargin = dp(contact ? 33 : 37);
            ((LayoutParams) topRow.getLayoutParams()).topMargin = dp(contact ? 10 : 11);
            requestLayout();
            invalidate();
        }

        public void setSubtitleColor(int color) {
            preview.setTextColor(color);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(contact ? 64 : 72), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (divider) {
                Paint p = Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider);
                if (p != null) {
                    canvas.drawLine(LocaleController.isRTL ? 0 : dp(76), getHeight() - 1, getWidth() - (LocaleController.isRTL ? dp(76) : 0), getHeight() - 1, p);
                }
            }
        }

        public static class Factory extends UItem.UItemFactory<DialogCell> {
            static { setup(new Factory()); }

            @Override
            public DialogCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new DialogCell(context, currentAccount, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                DialogCell cell = (DialogCell) view;
                boolean contact = item.accent;
                cell.set((TLRPC.User) item.object, item.text, item.subtext, item.textValue,
                        contact ? Ticks.NONE : (int) item.longValue, contact ? 0 : item.intValue, contact, divider);
                if (contact) {
                    cell.setSubtitleColor(Theme.getColor(item.intValue == 1 ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText, cell.resourcesProvider));
                }
            }

            /** status: Ticks.* (NONE for incoming). */
            public static UItem asDialog(int id, TLRPC.User user, CharSequence title, CharSequence preview, CharSequence time, int status, int unread) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = user;
                item.text = title;
                item.subtext = preview;
                item.textValue = time;
                item.longValue = status;
                item.intValue = unread;
                return item;
            }

            public static UItem asContact(int id, TLRPC.User user, CharSequence title, CharSequence status, boolean highlighted) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = user;
                item.text = title;
                item.subtext = status;
                item.accent = true;
                item.intValue = highlighted ? 1 : 0;
                return item;
            }
        }
    }

    /* Empty / loading state: illustration, title, hint and a prominent button. */

    public static class EmptyCell extends LinearLayout {
        private final Theme.ResourcesProvider resourcesProvider;
        private final RLottieImageView lottie;
        private final RadialProgressView progress;
        private final TextView title, hint;
        private final ButtonWithCounterView button;
        private Runnable onClick;
        private int lottieRes;

        public EmptyCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(32), dp(20), dp(32), dp(24));

            lottie = new RLottieImageView(context);
            lottie.setOnClickListener(v -> lottie.playAnimation());
            addView(lottie, LayoutHelper.createLinear(110, 110, Gravity.CENTER_HORIZONTAL));

            progress = new RadialProgressView(context, resourcesProvider);
            progress.setSize(dp(32));
            addView(progress, LayoutHelper.createLinear(44, 44, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

            hint = new TextView(context);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(dp(2), 1f);
            addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));

            button = new ButtonWithCounterView(context, resourcesProvider).setRound();
            button.setOnClickListener(v -> {
                if (onClick != null) onClick.run();
            });
            addView(button, LayoutHelper.createLinear(240, 44, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));
        }

        public void set(int lottieRes, CharSequence titleText, CharSequence hintText, CharSequence buttonText, boolean loading, Runnable onClick) {
            this.onClick = onClick;
            if (loading) {
                lottie.setVisibility(GONE);
                progress.setVisibility(VISIBLE);
                progress.setProgressColor(Theme.getColor(Theme.key_progressCircle, resourcesProvider));
            } else {
                progress.setVisibility(GONE);
                lottie.setVisibility(lottieRes != 0 ? VISIBLE : GONE);
                if (lottieRes != 0 && lottieRes != this.lottieRes) {
                    this.lottieRes = lottieRes;
                    lottie.setAnimation(lottieRes, 110, 110);
                    lottie.playAnimation();
                }
            }
            title.setVisibility(TextUtils.isEmpty(titleText) ? GONE : VISIBLE);
            title.setText(titleText);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            hint.setVisibility(TextUtils.isEmpty(hintText) ? GONE : VISIBLE);
            hint.setText(hintText);
            hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            button.setVisibility(TextUtils.isEmpty(buttonText) || loading ? GONE : VISIBLE);
            button.setText(buttonText, false);
        }

        public static class Factory extends UItem.UItemFactory<EmptyCell> {
            static { setup(new Factory()); }

            @Override
            public EmptyCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new EmptyCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((EmptyCell) view).set(item.iconResId, item.text, item.subtext, item.textValue, item.intValue == 1, (Runnable) item.object);
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            public static UItem of(int lottieRes, CharSequence title, CharSequence hint, CharSequence button, boolean loading, Runnable onClick) {
                UItem item = UItem.ofFactory(Factory.class);
                item.iconResId = lottieRes;
                item.text = title;
                item.subtext = hint;
                item.textValue = button;
                item.intValue = loading ? 1 : 0;
                item.object = onClick;
                return item;
            }
        }
    }

    /* Bubble meta: time + ticks, drawn at the bottom-end of a bubble like Telegram. */

    public static class MetaView extends View {
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Ticks ticks;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text = "";
        private int status = Ticks.NONE;
        private int tickColor;
        private boolean overlay;

        public MetaView(Context context) {
            super(context);
            ticks = new Ticks(context);
            paint.setTextSize(dp(12));
        }

        public void set(String text, int textColor, int status, int tickColor, boolean overlay, int overlayBg) {
            this.text = text == null ? "" : text;
            this.status = status;
            this.tickColor = tickColor;
            this.overlay = overlay;
            paint.setColor(textColor);
            bg.setColor(overlayBg);
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = (int) Math.ceil(paint.measureText(text));
            if (status != Ticks.NONE) w += dp(3) + ticks.width(status);
            if (overlay) w += dp(12);
            setMeasuredDimension(w, dp(overlay ? 20 : 16));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int h = getMeasuredHeight();
            int x = 0;
            if (overlay) {
                AndroidUtilities.rectTmp.set(0, 0, getMeasuredWidth(), h);
                canvas.drawRoundRect(AndroidUtilities.rectTmp, h / 2f, h / 2f, bg);
                x = dp(6);
            }
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, x, baseline, paint);
            if (status != Ticks.NONE) {
                ticks.draw(canvas, status, x + (int) Math.ceil(paint.measureText(text)) + dp(3), h / 2, tickColor);
            }
        }
    }
}
