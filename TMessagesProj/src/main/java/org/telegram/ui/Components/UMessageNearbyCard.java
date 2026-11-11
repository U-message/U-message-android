package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/** U message nearby share card: two phones glide together, touch with a ripple, then the peer's profile rises in. */
public class UMessageNearbyCard extends LinearLayout {

    private static final CubicBezierInterpolator EASE = CubicBezierInterpolator.EASE_OUT_QUINT;
    private static final CubicBezierInterpolator SMOOTH = CubicBezierInterpolator.EASE_BOTH;

    private final FrameLayout phones;
    private final View leftPhone, rightPhone, glow;
    private final View[] rings = new View[3];
    private final View[] details;
    private final ArrayList<Animator> running = new ArrayList<>();
    private boolean looping;

    public UMessageNearbyCard(Context context, String peerName, String username, String bio, Runnable share, Runnable cancel) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(22), dp(20), dp(22), dp(18));
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff1d2330, 0xff11141c});
        cardBg.setCornerRadius(dp(30));
        setBackground(cardBg);

        phones = new FrameLayout(context);
        phones.setClipChildren(false);
        addView(phones, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 170));

        glow = new View(context);
        GradientDrawable glowBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xaa9fd8ff, 0x00000000});
        glowBg.setShape(GradientDrawable.OVAL);
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setGradientRadius(dp(80));
        glow.setBackground(glowBg);
        glow.setAlpha(0f);
        phones.addView(glow, LayoutHelper.createFrame(160, 160, Gravity.CENTER));

        for (int i = 0; i < rings.length; i++) {
            View ring = new View(context);
            GradientDrawable ringBg = new GradientDrawable();
            ringBg.setShape(GradientDrawable.OVAL);
            ringBg.setStroke(dp(2), 0x90ffffff);
            ring.setBackground(ringBg);
            ring.setAlpha(0f);
            rings[i] = ring;
            phones.addView(ring, LayoutHelper.createFrame(110, 110, Gravity.CENTER));
        }

        leftPhone = phoneView(context, true);
        rightPhone = phoneView(context, false);
        phones.addView(leftPhone, LayoutHelper.createFrame(78, 132, Gravity.CENTER, -46, 0, 0, 0));
        phones.addView(rightPhone, LayoutHelper.createFrame(78, 132, Gravity.CENTER, 46, 0, 0, 0));

        LinearLayout profile = new LinearLayout(context);
        profile.setOrientation(HORIZONTAL);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable profileBg = new GradientDrawable();
        profileBg.setColor(0x18ffffff);
        profileBg.setCornerRadius(dp(22));
        profile.setBackground(profileBg);
        addView(profile, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 76, 0, 0, 0, 16));

        final String name = TextUtils.isEmpty(peerName) ? "U message" : peerName;
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(26));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(0, name, null);
        avatar.setImageDrawable(avatarDrawable);
        profile.addView(avatar, LayoutHelper.createLinear(52, 52));
        resolveAvatar(username, avatar, avatarDrawable);

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        profile.addView(info, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 12, 0, 0, 0));

        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextColor(Color.WHITE);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setTextSize(17);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView bioView = new TextView(context);
        bioView.setText(!TextUtils.isEmpty(bio) ? bio : (!TextUtils.isEmpty(username) ? "@" + username : "U message"));
        bioView.setTextColor(0xb3ffffff);
        bioView.setSingleLine(true);
        bioView.setEllipsize(TextUtils.TruncateAt.END);
        bioView.setTextSize(13);
        info.addView(bioView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.UMessageNearbyShare));
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.UMessageNearbyShareConfirm));
        subtitle.setTextColor(0xccffffff);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(HORIZONTAL);
        addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        buttons.addView(actionButton(context, LocaleController.getString(R.string.Cancel), 0x26ffffff, cancel), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 0, 0, 5, 0));
        buttons.addView(actionButton(context, LocaleController.getString(R.string.ShareContact), 0xff34c759, share), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 5, 0, 0, 0));

        details = new View[]{profile, title, subtitle, buttons};
    }

    /**
     * Plays the approach: phones slide in from the edges, touch, ripple, then the details rise in one by one.
     * With {@code reveal} false the phones touch but nothing is shared (feature off). Returns the total duration.
     */
    public long play(boolean reveal) {
        cancel();
        final float spread = dp(120);
        leftPhone.setTranslationX(-spread);
        rightPhone.setTranslationX(spread);
        leftPhone.setRotation(-18);
        rightPhone.setRotation(18);
        leftPhone.setAlpha(0f);
        rightPhone.setAlpha(0f);
        glow.setAlpha(0f);
        for (View ring : rings) {
            ring.setAlpha(0f);
        }
        for (View view : details) {
            view.setAlpha(0f);
            view.setTranslationY(dp(24));
        }

        final long approach = 1100;
        AnimatorSet slide = new AnimatorSet();
        slide.playTogether(
                ObjectAnimator.ofFloat(leftPhone, View.TRANSLATION_X, -spread, dp(4)),
                ObjectAnimator.ofFloat(rightPhone, View.TRANSLATION_X, spread, -dp(4)),
                ObjectAnimator.ofFloat(leftPhone, View.ROTATION, -18, -8),
                ObjectAnimator.ofFloat(rightPhone, View.ROTATION, 18, 8),
                ObjectAnimator.ofFloat(leftPhone, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(rightPhone, View.ALPHA, 0f, 1f)
        );
        slide.setDuration(approach);
        slide.setInterpolator(SMOOTH);
        start(slide, 0);

        // the touch: a soft bump back apart, a glow and rippling rings
        AnimatorSet bump = new AnimatorSet();
        bump.playTogether(
                ObjectAnimator.ofFloat(leftPhone, View.TRANSLATION_X, dp(4), 0),
                ObjectAnimator.ofFloat(rightPhone, View.TRANSLATION_X, -dp(4), 0),
                ObjectAnimator.ofFloat(glow, View.ALPHA, 0f, 1f, reveal ? 0.55f : 0f),
                ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.4f, 1.2f),
                ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.4f, 1.2f)
        );
        bump.setDuration(900);
        bump.setInterpolator(EASE);
        start(bump, approach);

        final int ringCount = reveal ? rings.length : 1;
        for (int i = 0; i < ringCount; i++) {
            final View ring = rings[i];
            AnimatorSet ripple = new AnimatorSet();
            ripple.playTogether(
                    ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.6f, 2.1f),
                    ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.6f, 2.1f),
                    ObjectAnimator.ofFloat(ring, View.ALPHA, 0.9f, 0f)
            );
            ripple.setDuration(1500);
            ripple.setInterpolator(EASE);
            start(ripple, approach + i * 260L);
        }

        if (!reveal) {
            return approach + 1500;
        }
        final long revealAt = approach + 450;
        for (int i = 0; i < details.length; i++) {
            final View view = details[i];
            AnimatorSet rise = new AnimatorSet();
            rise.playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, dp(24), 0)
            );
            rise.setDuration(700);
            rise.setInterpolator(EASE);
            start(rise, revealAt + i * 120L);
        }
        startBreathing(revealAt + 900);
        return revealAt + details.length * 120L + 700;
    }

    /** Keeps a slow ripple going while the card waits for an answer. */
    private void startBreathing(long delay) {
        looping = true;
        final View ring = rings[0];
        AnimatorSet pulse = new AnimatorSet();
        pulse.playTogether(
                ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.9f, 1.8f),
                ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.9f, 1.8f),
                ObjectAnimator.ofFloat(ring, View.ALPHA, 0.6f, 0f)
        );
        pulse.setDuration(2000);
        pulse.setInterpolator(SMOOTH);
        pulse.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!canceled && looping && isAttachedToWindow()) {
                    pulse.setStartDelay(0);
                    pulse.start();
                }
            }
        });
        start(pulse, delay);
    }

    public void cancel() {
        looping = false;
        for (Animator animator : new ArrayList<>(running)) {
            animator.cancel();
        }
        running.clear();
    }

    private void start(Animator animator, long delay) {
        animator.setStartDelay(delay);
        running.add(animator);
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancel();
    }

    private static View phoneView(Context context, boolean left) {
        FrameLayout phone = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{left ? 0xff64d2ff : 0xffffcc66, left ? 0xff0a84ff : 0xffff375f});
        bg.setCornerRadius(dp(18));
        phone.setBackground(bg);
        TextView icon = new TextView(context);
        icon.setText(left ? "U" : "M");
        icon.setTextColor(Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(34);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        phone.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        phone.setElevation(dp(8));
        return phone;
    }

    private static TextView actionButton(Context context, String text, int color, Runnable action) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
        button.setBackground(bg);
        if (action != null) {
            button.setOnClickListener(v -> action.run());
        }
        return button;
    }

    private static void resolveAvatar(String username, BackupImageView avatar, AvatarDrawable avatarDrawable) {
        if (TextUtils.isEmpty(username)) {
            return;
        }
        MessagesController.getInstance(UserConfig.selectedAccount).getUserNameResolver().resolve(username, peerId -> {
            if (peerId == null || peerId <= 0 || avatar.getParent() == null) {
                return;
            }
            TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(peerId);
            if (user != null) {
                avatarDrawable.setInfo(user);
                avatar.setForUserOrChat(user, avatarDrawable);
            }
        });
    }
}
