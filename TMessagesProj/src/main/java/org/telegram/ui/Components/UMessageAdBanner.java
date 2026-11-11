package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageAds;
import org.telegram.messenger.browser.Browser;

/**
 * Brand ad card pinned to the bottom of feature pages: logo (or brand name) top-left,
 * headline under it, the brand picture on the right and a round arrow button bottom-right.
 * Colors and pictures come from the ad, so every brand gets its own look.
 */
public class UMessageAdBanner extends FrameLayout {

    public static final int HEIGHT_DP = 96;

    public UMessageAdBanner(Context context, UMessageAds.Ad ad) {
        super(context);

        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ad.colorStart, ad.colorEnd});
        background.setCornerRadius(dp(20));
        setBackground(background);
        setClipToOutline(true);
        final boolean lightBg = AndroidUtilities.computePerceivedBrightness(ColorUtils.blendARGB(ad.colorStart, ad.colorEnd, .5f)) > .721f;
        final int textColor = lightBg ? 0xff000000 : 0xffffffff;
        setOnClickListener(v -> {
            UMessageAds.track(ad, "click");
            if (org.telegram.messenger.UMessageAds.isSafeUrl(ad.url)) Browser.openUrl(context, ad.url);
        });
        UMessageAds.track(ad, "view");
        ScaleStateListAnimator.apply(this, .02f, 1.2f);

        if (ad.art != null || ad.artRes != 0) {
            ImageView art = new ImageView(context);
            if (ad.art != null) {
                art.setImageBitmap(ad.art);
            } else {
                art.setImageResource(ad.artRes);
            }
            art.setScaleType(ImageView.ScaleType.FIT_END);
            addView(art, LayoutHelper.createFrame(120, 84, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 36, 0));
        }

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20, Gravity.LEFT | Gravity.TOP, 16, 14, 0, 0));
        if (ad.logo != null || ad.logoRes != 0) {
            ImageView logo = new ImageView(context);
            if (ad.logo != null) {
                logo.setImageBitmap(ad.logo);
            } else {
                logo.setImageResource(ad.logoRes);
            }
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            header.addView(logo, LayoutHelper.createLinear(70, 16));
        } else {
            header.addView(text(context, ad.brand, 15, textColor, true));
        }
        TextView label = text(context, LocaleController.getString(R.string.UMessageAdLabel), 10, ColorUtils.setAlphaComponent(textColor, 0x99), false);
        header.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 1, 0, 0));

        TextView headline = text(context, ad.headline, 16, textColor, true);
        headline.setSingleLine(false);
        headline.setMaxLines(2);
        headline.setLineSpacing(0, 1.05f);
        addView(headline, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 130, 14));

        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.msg_arrow_forward);
        arrow.setColorFilter(new PorterDuffColorFilter(AndroidUtilities.computePerceivedBrightness(ad.ctaColor) > .721f ? 0xff000000 : 0xffffffff, PorterDuff.Mode.SRC_IN));
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ad.ctaColor);
        arrow.setBackground(circle);
        addView(arrow, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 12, 12));
    }

    private static TextView text(Context context, CharSequence value, int sizeDp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        if (bold) {
            view.setTypeface(AndroidUtilities.bold());
        }
        return view;
    }
}
