package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Same layout as the cache clearing sheet: animation, percent, progress bar, status, cancel. */
public class UMessageStoryProgressView extends FrameLayout {
    private final AnimatedTextView percent;
    private final Bar bar;
    private final TextView subtitle;

    public UMessageStoryProgressView(Context context, CharSequence titleText, CharSequence cancelText, Runnable onCancel, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_switchTrackChecked, resourcesProvider);

        RLottieImageView image = new RLottieImageView(context);
        image.setAutoRepeat(true);
        image.setAnimation(R.raw.umessage_story_privacy, 150, 150);
        addView(image, LayoutHelper.createFrame(150, 150, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 16, 0, 0));
        image.playAnimation();

        percent = new AnimatedTextView(context, false, true, true);
        percent.setAnimationProperties(.35f, 0, 120, CubicBezierInterpolator.EASE_OUT);
        percent.setGravity(Gravity.CENTER_HORIZONTAL);
        percent.setTextColor(textColor);
        percent.setTextSize(AndroidUtilities.dp(24));
        percent.setTypeface(AndroidUtilities.bold());
        addView(percent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 32, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 176, 0, 0));

        bar = new Bar(context, accent);
        addView(bar, LayoutHelper.createFrame(240, 5, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 226, 0, 0));

        TextView title = new TextView(context);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTextColor(textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(titleText);
        addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 24, 261, 24, 0));

        subtitle = new TextView(context);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTextColor(textColor);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        addView(subtitle, LayoutHelper.createFrame(280, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 289, 0, 0));

        TextView cancel = new TextView(context);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(accent);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        cancel.setTypeface(AndroidUtilities.bold());
        cancel.setText(cancelText);
        cancel.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(accent, .12f), 8, 8));
        cancel.setOnClickListener(v -> onCancel.run());
        addView(cancel, LayoutHelper.createFrame(160, 44, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 340, 0, 0));

        setProgress(0);
    }

    public void setProgress(float value) {
        float t = Math.max(0, Math.min(1, value));
        percent.cancelAnimation();
        percent.setText((int) Math.ceil(t * 100) + "%", true);
        bar.setProgress(t);
    }

    public void setStatus(CharSequence text) {
        subtitle.setText(text);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(396), MeasureSpec.EXACTLY)
        );
    }

    private static final class Bar extends View {
        private final Paint in = new Paint(Paint.ANTI_ALIAS_FLAG), out = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final AnimatedFloat animated = new AnimatedFloat(this, 350, CubicBezierInterpolator.EASE_OUT);
        private float progress;

        Bar(Context context, int color) {
            super(context);
            in.setColor(color);
            out.setColor(Theme.multAlpha(color, .2f));
        }

        void setProgress(float value) {
            progress = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            AndroidUtilities.rectTmp.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
            canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(3), AndroidUtilities.dp(3), out);
            AndroidUtilities.rectTmp.set(0, 0, getMeasuredWidth() * animated.set(progress), getMeasuredHeight());
            canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(3), AndroidUtilities.dp(3), in);
        }
    }
}
