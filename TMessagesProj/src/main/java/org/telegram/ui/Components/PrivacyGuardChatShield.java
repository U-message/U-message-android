package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.privacyguard.PrivacyGuardController;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.messenger.privacyguard.PrivacyGuardStateMachine;
import org.telegram.messenger.privacyguard.PrivacyGuardStatus;
import org.telegram.messenger.privacyguard.PrivacyGuardTuning;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PhotoViewer;

import java.util.WeakHashMap;

/**
 * Hides the content of a chat while Privacy Guard reports that someone else may be viewing the screen.
 * Every view of the chat except the header is blurred in place (no reload, so scroll position, draft, selection,
 * reply and media state are untouched); a small indicator explains what happened.
 * Owned by the chat screen; the chat forwards its lifecycle and drawing hooks.
 */
public class PrivacyGuardChatShield implements PrivacyGuardController.Host {

    public interface ChildDrawer {
        void draw(View child);
    }

    private static final long HOLD_ON_RESUME_MS = 2000;
    private static final long REFRESH_MS = 300;

    private final BaseFragment fragment;
    private final ViewGroup contentView;
    private final View actionBar;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Runnable onProtect;
    private final IndicatorView indicator;
    private final Paint scrimPaint = new Paint();
    private final WeakHashMap<View, Integer> accessibilityBackup = new WeakHashMap<>();
    private final WeakHashMap<View, Float> blurApplied = new WeakHashMap<>();

    private FlagSecureReason flagSecure;
    private boolean attached;
    private boolean hidden;
    private boolean locked;
    // After an unlock the analyzer still has verdicts in flight from before it heard the dismiss; without
    // this grace one of them re-locked the screen right away and the cover could never be opened.
    private static final long REVEAL_GRACE_MS = 1500;
    private long revealedAt;
    private boolean wasProtected;
    private boolean holdUntilResult;
    private int reason = PrivacyGuardStateMachine.REASON_UNKNOWN_VIEWER;
    private float progress;
    private ValueAnimator animator;
    private View mediaView;
    private float mediaRadius;
    private View composerView;
    private boolean authenticating;

    public PrivacyGuardChatShield(BaseFragment fragment, ViewGroup contentView, View actionBar, Theme.ResourcesProvider resourcesProvider, Runnable onProtect) {
        this.fragment = fragment;
        this.contentView = contentView;
        this.actionBar = actionBar;
        this.resourcesProvider = resourcesProvider;
        this.onProtect = onProtect;
        indicator = new IndicatorView(contentView.getContext());
        indicator.setVisibility(View.GONE);
        contentView.addView(indicator, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        contentView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                if (progress > 0) {
                    AndroidUtilities.runOnUIThread(() -> applyProgress(progress));
                }
                if (child != indicator && indicator.getParent() == contentView && contentView.indexOfChild(indicator) != contentView.getChildCount() - 1) {
                    AndroidUtilities.runOnUIThread(() -> indicator.bringToFront());
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                blurApplied.remove(child);
            }
        });
    }

    /** The chat became visible. */
    public void attach() {
        if (attached || !PrivacyGuardSettings.isSupported()) {
            return;
        }
        if (PrivacyGuardController.getInstance().isAppShieldActive()) {
            // The app-wide shield already covers this chat; do not fight over the single camera host.
            return;
        }
        attached = true;
        if (flagSecure == null && fragment.getParentActivity() != null) {
            flagSecure = new FlagSecureReason(fragment.getParentActivity().getWindow(),
                    () -> attached && PrivacyGuardSettings.isEnabled() && PrivacyGuardSettings.isProtectScreenshots());
        }
        if (flagSecure != null) {
            flagSecure.attach();
        }
        if (hidden) {
            // coming back while hidden: stay hidden until the camera has had a look
            holdUntilResult = true;
            AndroidUtilities.runOnUIThread(releaseHold, HOLD_ON_RESUME_MS);
        }
        PrivacyGuardController.getInstance().attach(this);
    }

    /** The chat is paused or covered. The visual state is kept, so leaving never reveals the chat. */
    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        AndroidUtilities.cancelRunOnUIThread(releaseHold);
        PrivacyGuardController.getInstance().detach(this);
        if (flagSecure != null) {
            flagSecure.detach();
        }
    }

    public void destroy() {
        detach();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        AndroidUtilities.cancelRunOnUIThread(refresh);
        applyProgress(0);
        restoreAccessibility();
    }

    /** The message input; kept sharp when the user chose not to protect it. */
    public void setComposerView(View view) {
        composerView = view;
    }

    private boolean isKeptSharp(View child) {
        return child == actionBar || child == indicator || child == composerView && !PrivacyGuardSettings.isProtectComposer();
    }

    public boolean isHidden() {
        return progress > 0;
    }

    /** The indicator is drawn by {@link #drawOverlay} above everything else. */
    public boolean skipsChild(View child) {
        return child == indicator;
    }

    private final Runnable releaseHold = () -> {
        holdUntilResult = false;
        onPrivacyGuardStatus(PrivacyGuardController.getInstance().getStatus());
    };

    @Override
    public void onPrivacyGuardStatus(PrivacyGuardStatus status) {
        if (!attached) {
            return;
        }
        if (holdUntilResult) {
            // only a clear verdict ends the hold; otherwise the timeout does
            final PrivacyGuardStateMachine.State state = status.state;
            if (state != PrivacyGuardStateMachine.State.SAFE_OWNER_ONLY && state != PrivacyGuardStateMachine.State.UNKNOWN_NOT_LOOKING
                    && state != PrivacyGuardStateMachine.State.PROTECTED && state != PrivacyGuardStateMachine.State.DISABLED) {
                return;
            }
            holdUntilResult = false;
            AndroidUtilities.cancelRunOnUIThread(releaseHold);
        }
        if (status.state == PrivacyGuardStateMachine.State.DISABLED) {
            locked = false;
        }
        final boolean protect = status.isProtected()
                && android.os.SystemClock.elapsedRealtime() - revealedAt >= REVEAL_GRACE_MS;
        if (protect) {
            reason = status.reason;
            // lock on a new detection only, not on every repeat of the same one
            if (!wasProtected && PrivacyGuardSettings.getAction() == PrivacyGuardSettings.ACTION_LOCK) {
                locked = true;
            }
        }
        wasProtected = protect;
        if (flagSecure != null) {
            flagSecure.invalidate();
        }
        setHidden(protect || locked);
    }

    private void setHidden(boolean hide) {
        if (hidden == hide) {
            indicator.update();
            return;
        }
        hidden = hide;
        indicator.update();
        if (animator != null) {
            animator.cancel();
        }
        if (hide) {
            hideAccessibility();
            if (onProtect != null) {
                onProtect.run();
            }
            contentView.announceForAccessibility(LocaleController.getString(R.string.PrivacyGuardActivated));
            AndroidUtilities.cancelRunOnUIThread(refresh);
            AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
        }
        animator = ValueAnimator.ofFloat(progress, hide ? 1f : 0f);
        animator.addUpdateListener(a -> applyProgress((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!hidden && animator == animation) {
                    AndroidUtilities.cancelRunOnUIThread(refresh);
                    restoreAccessibility();
                }
            }
        });
        animator.setDuration(hide ? PrivacyGuardTuning.BLUR_IN_MS : PrivacyGuardTuning.BLUR_OUT_MS);
        animator.setInterpolator(hide ? CubicBezierInterpolator.EASE_OUT : CubicBezierInterpolator.EASE_BOTH);
        animator.start();
    }

    /** Picks up views added while hidden (hints, sheets) and the media viewer opening. */
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (progress > 0) {
                applyProgress(progress);
                AndroidUtilities.runOnUIThread(this, REFRESH_MS);
            }
        }
    };

    private void applyProgress(float p) {
        progress = p;
        if (Build.VERSION.SDK_INT >= 31) {
            final float radius = p * dp(28);
            final RenderEffect effect = radius > 0.5f ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null;
            for (int i = 0, n = contentView.getChildCount(); i < n; i++) {
                final View child = contentView.getChildAt(i);
                final boolean sharp = isKeptSharp(child);
                final float target = sharp || effect == null ? 0 : radius;
                final Float applied = blurApplied.get(child);
                if ((applied == null ? 0 : applied) == target) {
                    continue;
                }
                child.setRenderEffect(target > 0 ? effect : null);
                if (target > 0) {
                    blurApplied.put(child, target);
                } else {
                    blurApplied.remove(child);
                }
            }
        }
        applyToMediaViewer(p);
        indicator.setProgress(p);
        contentView.invalidate();
    }

    private void applyToMediaViewer(float p) {
        View target = null;
        if (p > 0 && PrivacyGuardSettings.isProtectMedia() && PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
            target = PhotoViewer.getInstance().windowView;
        }
        if (mediaView != null && mediaView != target) {
            clearMediaView(mediaView);
            mediaView = null;
        }
        if (target == null) {
            return;
        }
        final float radius = p * dp(28);
        if (Build.VERSION.SDK_INT >= 31 && (mediaView != target || mediaRadius != radius)) {
            target.setRenderEffect(radius > 0.5f ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null);
        }
        mediaView = target;
        mediaRadius = radius;
        if (Build.VERSION.SDK_INT >= 23) {
            // also covers video drawn on a SurfaceView, which view effects cannot reach
            Drawable foreground = target.getForeground();
            if (!(foreground instanceof ShieldDrawable)) {
                foreground = new ShieldDrawable();
                target.setForeground(foreground);
            }
            foreground.setAlpha((int) (255 * p * (Build.VERSION.SDK_INT >= 31 ? 0.6f : 0.95f)));
        }
        target.setImportantForAccessibility(p > 0 ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private static void clearMediaView(View view) {
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(null);
        }
        if (Build.VERSION.SDK_INT >= 23 && view.getForeground() instanceof ShieldDrawable) {
            view.setForeground(null);
        }
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private static class ShieldDrawable extends ColorDrawable {
        ShieldDrawable() {
            super(0xff000000);
        }
    }

    /**
     * Called at the very end of the chat's dispatchDraw. Dims the (blurred) content, or fully covers it where view
     * blur is unavailable, then draws the header and the indicator on top, sharp.
     */
    public void drawOverlay(Canvas canvas, ChildDrawer drawer) {
        if (progress <= 0) {
            return;
        }
        final boolean blurSupported = Build.VERSION.SDK_INT >= 31;
        scrimPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        scrimPaint.setAlpha((int) (255 * progress * (blurSupported ? 0.35f : 0.97f)));
        canvas.drawRect(0, 0, contentView.getWidth(), contentView.getHeight(), scrimPaint);
        if (composerView != null && !PrivacyGuardSettings.isProtectComposer() && composerView.getVisibility() == View.VISIBLE) {
            drawer.draw(composerView);
        }
        if (actionBar.getVisibility() == View.VISIBLE) {
            drawer.draw(actionBar);
        }
        drawer.draw(indicator);
    }

    private void hideAccessibility() {
        for (int i = 0, n = contentView.getChildCount(); i < n; i++) {
            final View child = contentView.getChildAt(i);
            if (isKeptSharp(child) || accessibilityBackup.containsKey(child)) {
                continue;
            }
            accessibilityBackup.put(child, child.getImportantForAccessibility());
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    private void restoreAccessibility() {
        for (java.util.Map.Entry<View, Integer> e : accessibilityBackup.entrySet()) {
            if (e.getKey() != null) {
                e.getKey().setImportantForAccessibility(e.getValue());
            }
        }
        accessibilityBackup.clear();
    }

    private void onIndicatorClick() {
        // A tap never plainly reveals: only the owner's biometric (or the camera recognizing the
        // owner again, which clears it automatically) may open it, so a bystander cannot tap to show.
        unlock();
    }

    /** The user chose to see the chat now; new viewers will hide it again. */
    private void reveal() {
        locked = false;
        wasProtected = false;
        revealedAt = android.os.SystemClock.elapsedRealtime();
        PrivacyGuardController.getInstance().dismiss();
        setHidden(false);
    }

    private void unlock() {
        if (authenticating) {
            return;
        }
        final FragmentActivity activity = fragment.getParentActivity() instanceof FragmentActivity ? (FragmentActivity) fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        final int authenticators = Build.VERSION.SDK_INT >= 30
                ? BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_WEAK;
        final boolean available = Build.VERSION.SDK_INT >= 30
                ? BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
                : isDeviceSecure(activity);
        if (!available) {
            // nothing to verify with on this device
            reveal();
            return;
        }
        authenticating = true;
        BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                authenticating = false;
                reveal();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                authenticating = false;
            }
        });
        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(LocaleController.getString(R.string.PrivacyGuardUnlockTitle))
                .setConfirmationRequired(false);
        if (Build.VERSION.SDK_INT >= 30) {
            info.setAllowedAuthenticators(authenticators);
        } else {
            //noinspection deprecation
            info.setDeviceCredentialAllowed(true);
        }
        try {
            prompt.authenticate(info.build());
        } catch (Exception e) {
            authenticating = false;
        }
    }

    private static boolean isDeviceSecure(Context context) {
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && Build.VERSION.SDK_INT >= 23 && keyguard.isDeviceSecure();
    }

    /** Full size, so it can block touches while locked; draws a small pill below the header. */
    private class IndicatorView extends FrameLayout {

        private final LinearLayout pill;
        private final TextView titleView;
        private final TextView subtitleView;

        IndicatorView(Context context) {
            super(context);
            pill = new LinearLayout(context);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(12), dp(8), dp(16), dp(8));
            pill.setBackground(Theme.createRoundRectDrawable(dp(22), 0xe61c1c1e));
            pill.setOnClickListener(v -> onIndicatorClick());
            ScaleStateListAnimator.apply(pill);

            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.outline_shield_check);
            icon.setColorFilter(0xffffffff);
            pill.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(0xffffffff);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView);
            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subtitleView.setTextColor(0xb3ffffff);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView);
            pill.addView(texts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        }

        void update() {
            if (locked) {
                titleView.setText(LocaleController.getString(R.string.PrivacyGuardChatLocked));
                subtitleView.setText(LocaleController.getString(R.string.PrivacyGuardTapToUnlock));
            } else {
                titleView.setText(LocaleController.getString(R.string.PrivacyGuardActivated));
                final String why = LocaleController.getString(reason == PrivacyGuardStateMachine.REASON_OWNER_AWAY ? R.string.PrivacyGuardLookedAway : R.string.PrivacyGuardMayBeViewing);
                subtitleView.setText(why + " · " + LocaleController.getString(R.string.PrivacyGuardTapToUnlock));
            }
            pill.setContentDescription(titleView.getText() + ". " + subtitleView.getText());
        }

        void setProgress(float p) {
            setVisibility(p > 0 ? VISIBLE : GONE);
            pill.setAlpha(p);
            pill.setScaleX(0.9f + 0.1f * p);
            pill.setScaleY(0.9f + 0.1f * p);
            pill.setTranslationY(actionBar.getY() + actionBar.getHeight() + dp(12) - dp(8) * (1f - p));
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (progress <= 0) {
                return false;
            }
            final float x = ev.getX() - pill.getX(), y = ev.getY() - pill.getY();
            final boolean onPill = x >= 0 && y >= 0 && x <= pill.getWidth() && y <= pill.getHeight();
            if (onPill || ev.getActionMasked() != MotionEvent.ACTION_DOWN && pill.isPressed()) {
                return super.dispatchTouchEvent(ev);
            }
            // while hidden nothing below the header can be used until the owner unlocks
            return ev.getY() > actionBar.getY() + actionBar.getHeight();
        }
    }
}
