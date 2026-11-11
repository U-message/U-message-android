package org.telegram.ui.Stories;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageAds;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

/**
 * A brand "story" shown between two people's stories in the story viewer (never in the stories row).
 * Plays for a few seconds like a normal story; tap or the close button moves on, the button opens the ad.
 */
public class UMessageStoryAdView extends FrameLayout {

    private final Runnable onDone;
    private final ValueAnimator timer;
    private float progress;
    private boolean finished;

    public UMessageStoryAdView(Context context, UMessageAds.Ad ad, Runnable onDone) {
        super(context);
        this.onDone = onDone;
        setWillNotDraw(false);
        setClickable(true);

        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ad.colorStart, ad.colorEnd});
        setBackground(background);
        setOnClickListener(v -> finish());
        UMessageAds.track(ad, "view");

        setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (ad.logo != null || ad.logoRes != 0) {
            ImageView logo = new ImageView(context);
            if (ad.logo != null) {
                logo.setImageBitmap(ad.logo);
            } else {
                logo.setImageResource(ad.logoRes);
            }
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            header.addView(logo, LayoutHelper.createLinear(88, 20));
        } else {
            header.addView(text(context, ad.brand, 16, 0xffffffff, true));
        }
        header.addView(text(context, LocaleController.getString(R.string.UMessageAdLabel), 12, 0xb3ffffff, false),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 2, 0, 0));
        addView(header, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.TOP | Gravity.LEFT, 16, 16, 0, 0));

        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.ic_close_white);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setOnClickListener(v -> finish());
        addView(close, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 16, 8, 0));

        LinearLayout center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        if (ad.art != null || ad.artRes != 0) {
            ImageView art = new ImageView(context);
            if (ad.art != null) {
                art.setImageBitmap(ad.art);
            } else {
                art.setImageResource(ad.artRes);
            }
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            center.addView(art, LayoutHelper.createLinear(260, 196));
        }
        TextView headline = text(context, ad.headline, 26, 0xffffffff, true);
        headline.setGravity(Gravity.CENTER);
        headline.setLineSpacing(0, 1.1f);
        center.addView(headline, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 24, 28, 24, 0));
        addView(center, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 0, 0, 40));

        TextView cta = text(context, LocaleController.getString(R.string.UMessageAdOpen), 16, 0xff000000, true);
        cta.setGravity(Gravity.CENTER);
        GradientDrawable ctaBg = new GradientDrawable();
        ctaBg.setCornerRadius(dp(26));
        ctaBg.setColor(0xffffffff);
        cta.setBackground(ctaBg);
        cta.setOnClickListener(v -> {
            UMessageAds.track(ad, "click");
            if (org.telegram.messenger.UMessageAds.isSafeUrl(ad.url)) Browser.openUrl(context, ad.url);
            finish();
        });
        ScaleStateListAnimator.apply(cta, .04f, 1.2f);
        addView(cta, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52, Gravity.BOTTOM, 24, 0, 24, 40));

        timer = ValueAnimator.ofFloat(0, 1);
        timer.setDuration(UMessageAds.storyDurationMs());
        timer.setInterpolator(new LinearInterpolator());
        timer.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
            if (progress >= 1f) {
                finish();
            }
        });
    }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Story-style progress bar at the top.
        final float y = AndroidUtilities.statusBarHeight + dp(8);
        final float left = dp(8), right = getWidth() - dp(8);
        barRect.set(left, y, right, y + dp(2));
        barPaint.setColor(0x4dffffff);
        canvas.drawRoundRect(barRect, dp(1), dp(1), barPaint);
        barRect.right = left + (right - left) * progress;
        barPaint.setColor(0xffffffff);
        canvas.drawRoundRect(barRect, dp(1), dp(1), barPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setAlpha(0f);
        animate().alpha(1f).setDuration(180).start();
        timer.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        timer.cancel();
    }

    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        timer.cancel();
        animate().alpha(0f).setDuration(180).withEndAction(onDone).start();
    }

    private static TextView text(Context context, CharSequence value, int sizeDp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setIncludeFontPadding(false);
        if (bold) {
            view.setTypeface(AndroidUtilities.bold());
        }
        return view;
    }
}
