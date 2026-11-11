package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/** Warns about a chat on the Supreme Court list. Show with a dismiss listener that checks {@link #isOpenAnyway()}. */
public class UMessageBannedSourceSheet extends BottomSheet {

    private static final int CARD_RADIUS_DP = 34;
    private static final int INSET_RADIUS_DP = 18;
    private static final int BUTTON_HEIGHT_DP = 52;
    private static final float DISABLED_ALPHA = 0.35f;

    private boolean openAnyway;
    private boolean sure;

    public UMessageBannedSourceSheet(Context context, Theme.ResourcesProvider rp, String name, String date) {
        super(context, false, rp);
        shadowDrawable = new ColorDrawable(Color.TRANSPARENT);
        navBarColorKey = -1;
        navBarColor = Color.TRANSPARENT;
        setCanDismissWithTouchOutside(false);
        setCanDismissWithSwipe(false);
        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, rp);
        final boolean dark = AndroidUtilities.computePerceivedBrightness(Theme.getColor(Theme.key_dialogBackground, rp)) < 0.5f;
        final int blue = dark ? 0xFF0A84FF : 0xFF007AFF;
        final int red = dark ? 0xFFFF453A : 0xFFFF3B30;

        FrameLayout card = new FrameLayout(context);
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(CARD_RADIUS_DP), Theme.getColor(Theme.key_dialogBackground, rp)));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        card.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        RLottieImageView duck = new RLottieImageView(context);
        duck.setAutoRepeat(true);
        duck.setAnimation(R.raw.media_forbidden, 120, 120);
        duck.playAnimation();
        layout.addView(duck, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));

        TextView title = new TextView(context);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.UMessageBannedSourceTitle));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 10, 24, 0));

        TextView body = makeText(context, ColorUtils.setAlphaComponent(textColor, 0xB3), LocaleController.formatString(R.string.UMessageBannedSourceText, name, date));
        body.setGravity(Gravity.CENTER);
        layout.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));

        final int insetColor = ColorUtils.setAlphaComponent(textColor, 0x14);
        LinearLayout inset = new LinearLayout(context);
        inset.setOrientation(LinearLayout.VERTICAL);
        inset.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(INSET_RADIUS_DP), insetColor));
        layout.addView(inset, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        inset.addView(makeText(context, textColor, LocaleController.getString(R.string.UMessageBannedSourceAdvice)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 12));

        View divider = new View(context);
        divider.setBackgroundColor(insetColor);
        inset.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 16, 0, 0, 0));

        LinearLayout sureRow = new LinearLayout(context);
        sureRow.setOrientation(LinearLayout.HORIZONTAL);
        sureRow.setGravity(Gravity.CENTER_VERTICAL);
        // The system-blue fill matches the primary button whatever the app theme says.
        CheckBox2 check = new CheckBox2(context, 22, new Theme.ResourcesProvider() {
            @Override
            public int getColor(int key) {
                return key == Theme.key_radioBackgroundChecked ? blue : key == Theme.key_checkboxCheck ? Color.WHITE : Theme.getColor(key, rp);
            }
        });
        check.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
        check.setDrawUnchecked(true);
        check.setDrawBackgroundAsArc(10);
        check.setChecked(false, false);
        sureRow.addView(check, LayoutHelper.createLinear(26, 26, Gravity.CENTER_VERTICAL, 14, 0, 10, 0));
        TextView sureText = makeText(context, textColor, LocaleController.getString(R.string.UMessageBannedSourceSure));
        sureRow.addView(sureText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        inset.addView(sureRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        TextView leave = new TextView(context);
        leave.setTypeface(AndroidUtilities.bold());
        leave.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        leave.setTextColor(Color.WHITE);
        leave.setGravity(Gravity.CENTER);
        leave.setText(LocaleController.getString(R.string.UMessageBannedSourceLeave));
        leave.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(BUTTON_HEIGHT_DP / 2), blue));
        ScaleStateListAnimator.apply(leave);
        leave.setOnClickListener(v -> dismiss());
        layout.addView(leave, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, BUTTON_HEIGHT_DP, 16, 14, 16, 0));

        TextView open = new TextView(context);
        open.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        open.setTextColor(red);
        open.setGravity(Gravity.CENTER);
        open.setText(LocaleController.getString(R.string.UMessageBannedSourceOpen));
        open.setEnabled(false);
        open.setAlpha(DISABLED_ALPHA);
        open.setOnClickListener(v -> {
            openAnyway = true;
            dismiss();
        });
        layout.addView(open, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 4, 16, 0));

        TextView disclaimer = new TextView(context);
        disclaimer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        disclaimer.setTextColor(ColorUtils.setAlphaComponent(textColor, 0x80));
        disclaimer.setGravity(Gravity.CENTER);
        disclaimer.setText(LocaleController.getString(R.string.UMessageBannedSourceDisclaimer));
        layout.addView(disclaimer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 14));

        sureRow.setOnClickListener(v -> {
            sure = !sure;
            check.setChecked(sure, true);
            open.setEnabled(sure);
            open.animate().alpha(sure ? 1f : DISABLED_ALPHA).setDuration(150).start();
        });

        ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.msg_close);
        close.setColorFilter(new PorterDuffColorFilter(ColorUtils.setAlphaComponent(textColor, 0x99), PorterDuff.Mode.SRC_IN));
        close.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(30), insetColor));
        ScaleStateListAnimator.apply(close);
        close.setOnClickListener(v -> dismiss());
        card.addView(close, LayoutHelper.createFrame(30, 30, Gravity.TOP | Gravity.RIGHT, 0, 14, 14, 0));

        FrameLayout root = new FrameLayout(context);
        root.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 8, 0, 8, 8));
        setCustomView(root);
    }

    public boolean isOpenAnyway() {
        return openAnyway;
    }

    private static TextView makeText(Context context, int color, String text) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(color);
        view.setLineSpacing(AndroidUtilities.dp(2), 1f);
        view.setText(text);
        return view;
    }
}
