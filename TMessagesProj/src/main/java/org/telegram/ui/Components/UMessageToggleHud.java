package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * U message: iOS-style status capsule at the top of the screen (like the iOS silent-mode banner).
 * Icon in a colored circle, a bold title and a status line; drops in, stays briefly, then fades away.
 */
public class UMessageToggleHud extends LinearLayout {

    private static UMessageToggleHud current;

    /** Account quick switch: the new account's avatar, name and @username. */
    public static void showAccount(Activity activity, int account, org.telegram.tgnet.TLRPC.User user) {
        String username = org.telegram.messenger.UserObject.getPublicUsername(user);
        show(activity, 0, org.telegram.messenger.UserObject.getUserName(user),
                username != null ? "@" + username : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.UMessageAccountSwitched), true, account, user);
    }

    public static void show(Activity activity, int iconRes, String title, String status, boolean on) {
        show(activity, iconRes, title, status, on, -1, null);
    }

    private static void show(Activity activity, int iconRes, String title, String status, boolean on, int account, org.telegram.tgnet.TLRPC.User user) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        if (current != null && current.getParent() != null) {
            ((ViewGroup) current.getParent()).removeView(current);
        }
        final UMessageToggleHud hud = current = new UMessageToggleHud(activity, iconRes, title, status, on, account, user);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = AndroidUtilities.statusBarHeight + dp(10);
        root.addView(hud, lp);

        hud.setAlpha(0f);
        hud.setTranslationY(-dp(40));
        hud.setScaleX(0.85f);
        hud.setScaleY(0.85f);
        hud.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        hud.postDelayed(() -> hud.animate().alpha(0f).translationY(-dp(24)).scaleX(0.9f).scaleY(0.9f)
                .setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_IN)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (hud.getParent() != null) {
                            ((ViewGroup) hud.getParent()).removeView(hud);
                        }
                        if (current == hud) {
                            current = null;
                        }
                    }
                }).start(), 1900);
    }

    private UMessageToggleHud(Context context, int iconRes, String title, String status, boolean on, int account, org.telegram.tgnet.TLRPC.User user) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), dp(8), dp(20), dp(8));
        setMinimumWidth(dp(200));

        final boolean dark = Theme.isCurrentThemeDark();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        bg.setColor(dark ? 0xf22c2c2e : 0xf7ffffff);
        setBackground(bg);
        setElevation(dp(10));
        setClickable(true);

        final int accent = user != null ? (dark ? 0xff98989f : 0xff6d6d72) : on ? 0xff34c759 : 0xff8e8e93; // iOS green / iOS gray
        if (user != null) {
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(22));
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(account, user);
            avatar.setForUserOrChat(user, avatarDrawable);
            addView(avatar, LayoutHelper.createLinear(44, 44));
        } else {
        FrameLayout circle = new FrameLayout(context);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(accent);
        circle.setBackground(circleBg);
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        circle.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(circle, LayoutHelper.createLinear(44, 44));
        }

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(dark ? 0xffffffff : 0xff000000);
        titleView.setSingleLine(true);
        texts.addView(titleView);
        TextView statusView = new TextView(context);
        statusView.setText(status);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setTypeface(Typeface.DEFAULT);
        statusView.setTextColor(on && user == null ? accent : (dark ? 0xff98989f : 0xff6d6d72));
        statusView.setSingleLine(true);
        texts.addView(statusView);
        addView(texts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        setOnClickListener(v -> animate().alpha(0f).setDuration(150).start());
    }
}
