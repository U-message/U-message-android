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
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
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
import org.telegram.messenger.privacyguard.OwnerFaceStore;
import org.telegram.messenger.privacyguard.PrivacyGuardController;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.messenger.privacyguard.PrivacyGuardStateMachine;
import org.telegram.messenger.privacyguard.PrivacyGuardStatus;
import org.telegram.messenger.privacyguard.PrivacyGuardTuning;
import org.telegram.ui.ActionBar.Theme;

import java.util.WeakHashMap;

/**
 * App-wide Privacy Guard cover. Unlike {@link PrivacyGuardChatShield} it is owned by the single
 * host activity, not a chat, so it keeps the whole interface (chat list, profile, settings, any open
 * chat) inactive until the front camera recognizes the owner looking at the screen. Everything behind
 * it is blurred in place; a small pill explains what happened and only the owner's biometric may open it.
 */
public class PrivacyGuardAppShield extends FrameLayout implements PrivacyGuardController.Host {

    private final FragmentActivity activity;
    private final ViewGroup root;
    private final Paint scrimPaint = new Paint();
    private final WeakHashMap<View, Integer> accessibilityBackup = new WeakHashMap<>();

    private final LinearLayout pill;
    private final TextView titleView;
    private final TextView subtitleView;

    private static final long HOLD_ON_RESUME_MS = 2500;

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
    private boolean authenticating;
    private int reason = PrivacyGuardStateMachine.REASON_UNKNOWN_VIEWER;
    private float progress;
    private ValueAnimator animator;
    private ValueAnimator pillAnimator;
    private float pillProgress;
    private PrivacyGuardUnlockView unlockView;

    public PrivacyGuardAppShield(@NonNull FragmentActivity activity, @NonNull ViewGroup root) {
        super(activity);
        this.activity = activity;
        this.root = root;
        setVisibility(GONE);
        setWillNotDraw(false);

        pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(8), dp(16), dp(8));
        pill.setBackground(Theme.createRoundRectDrawable(dp(22), 0xe61c1c1e));
        pill.setOnClickListener(v -> onPillClick());
        ScaleStateListAnimator.apply(pill);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.outline_shield_check);
        icon.setColorFilter(0xffffffff);
        pill.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(activity);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(0xffffffff);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        subtitleView = new TextView(activity);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        subtitleView.setTextColor(0xb3ffffff);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitleView);
        pill.addView(texts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
    }

    /** Adds the shield on top of the host content and starts guarding while the app is in the foreground. */
    public void attach() {
        if (attached || !PrivacyGuardSettings.isSupported()) {
            return;
        }
        if (getParent() != root) {
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
            root.addView(this, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            root.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    if (child != PrivacyGuardAppShield.this && getParent() == root) {
                        AndroidUtilities.runOnUIThread(() -> {
                            bringToFront();
                            // the passcode screen must stay above the shield
                            if (unlockView != null && unlockView.getParent() == root) {
                                unlockView.bringToFront();
                            }
                        });
                    }
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
        }
        bringToFront();
        attached = true;
        PrivacyGuardController.getInstance().setAppShieldActive(true);
        if (flagSecure == null) {
            flagSecure = new FlagSecureReason(activity.getWindow(),
                    () -> attached && PrivacyGuardSettings.isEnabled() && PrivacyGuardSettings.isProtectScreenshots());
        }
        flagSecure.attach();
        // Start covered so nothing shows before the camera has had a look; a clear verdict (or the
        // timeout) lifts the hold. This keeps the whole app inactive until the owner is recognized.
        if (PrivacyGuardSettings.isEnabled() && OwnerFaceStore.hasOwner() && PrivacyGuardController.hasCameraPermission()) {
            holdUntilResult = true;
            reason = PrivacyGuardStateMachine.REASON_OWNER_AWAY;
            setHidden(true);
            AndroidUtilities.runOnUIThread(releaseHold, HOLD_ON_RESUME_MS);
        }
        PrivacyGuardController.getInstance().attach(this);
    }

    /** The app went to the background. The visual state is kept so returning never flashes the content. */
    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        AndroidUtilities.cancelRunOnUIThread(releaseHold);
        removePasscode();
        PrivacyGuardController.getInstance().detach(this);
        PrivacyGuardController.getInstance().setAppShieldActive(false);
        if (flagSecure != null) {
            flagSecure.detach();
        }
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
        final PrivacyGuardStateMachine.State state = status.state;
        if (holdUntilResult) {
            // Only a clear verdict ends the hold; a mere PAUSED (camera busy/starting) does not,
            // so we neither flash the content nor lock forever if the camera never opens.
            if (state != PrivacyGuardStateMachine.State.SAFE_OWNER_ONLY && state != PrivacyGuardStateMachine.State.UNKNOWN_NOT_LOOKING
                    && state != PrivacyGuardStateMachine.State.PROTECTED && state != PrivacyGuardStateMachine.State.DISABLED) {
                return;
            }
            holdUntilResult = false;
            AndroidUtilities.cancelRunOnUIThread(releaseHold);
        }
        if (state == PrivacyGuardStateMachine.State.DISABLED) {
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
        // While the passcode screen is open, keep the cover behind it as-is; if the camera recognizes
        // the owner, just open — no need to finish typing.
        if (unlockView != null) {
            if (!protect && !locked && state == PrivacyGuardStateMachine.State.SAFE_OWNER_ONLY) {
                removePasscode();
                reveal();
            }
            return;
        }
        setHidden(protect || locked);
    }

    private void setHidden(boolean hide) {
        if (hidden == hide) {
            updatePillText();
            return;
        }
        hidden = hide;
        updatePillText();
        if (animator != null) {
            animator.cancel();
        }
        if (pillAnimator != null) {
            pillAnimator.cancel();
        }
        if (hide) {
            setVisibility(VISIBLE);
            hideAccessibility();
            contentAnnounce();
            pillProgress = 0f;
            pillAnimator = ValueAnimator.ofFloat(0f, 1f);
            pillAnimator.addUpdateListener(a -> {
                pillProgress = (float) a.getAnimatedValue();
                layoutPill();
            });
            pillAnimator.setStartDelay(90);
            pillAnimator.setDuration(420);
            pillAnimator.setInterpolator(new OvershootInterpolator(1.6f));
            pillAnimator.start();
        }
        animator = ValueAnimator.ofFloat(progress, hide ? 1f : 0f);
        animator.addUpdateListener(a -> applyProgress((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!hidden && animator == animation) {
                    setVisibility(GONE);
                    restoreAccessibility();
                }
            }
        });
        animator.setDuration(hide ? PrivacyGuardTuning.BLUR_IN_MS + 120 : PrivacyGuardTuning.BLUR_OUT_MS);
        animator.setInterpolator(hide ? CubicBezierInterpolator.EASE_OUT_QUINT : CubicBezierInterpolator.EASE_BOTH);
        animator.start();
    }

    private void applyProgress(float p) {
        progress = p;
        if (Build.VERSION.SDK_INT >= 31) {
            final float radius = p * dp(30);
            final RenderEffect effect = radius > 0.5f ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null;
            for (int i = 0, n = root.getChildCount(); i < n; i++) {
                final View child = root.getChildAt(i);
                if (child == this) {
                    continue;
                }
                child.setRenderEffect(effect);
            }
        }
        layoutPill();
        invalidate();
    }

    private void layoutPill() {
        final float top = AndroidUtilities.statusBarHeight + dp(12);
        pill.setTranslationY(top - dp(16) * (1f - pillProgress));
        pill.setAlpha(Math.min(1f, pillProgress) * Math.min(1f, progress * 1.6f));
        final float scale = 0.7f + 0.3f * pillProgress;
        pill.setScaleX(scale);
        pill.setScaleY(scale);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (progress > 0f) {
            final boolean blurSupported = Build.VERSION.SDK_INT >= 31;
            scrimPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            scrimPaint.setAlpha((int) (255 * progress * (blurSupported ? 0.35f : 0.97f)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        }
        super.dispatchDraw(canvas);
    }

    private float downX, downY;
    private long downTime;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // While covering, take every touch: nothing behind may be used, and a tap anywhere unlocks.
        return progress > 0f;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (progress <= 0f) {
            return super.dispatchTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTime = System.currentTimeMillis();
                pill.setPressed(true);
                break;
            case MotionEvent.ACTION_UP:
                pill.setPressed(false);
                final boolean tap = Math.abs(ev.getX() - downX) < AndroidUtilities.dp(12)
                        && Math.abs(ev.getY() - downY) < AndroidUtilities.dp(12)
                        && System.currentTimeMillis() - downTime < 400;
                if (tap && unlockView == null) {
                    onPillClick();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                pill.setPressed(false);
                break;
        }
        // Consume everything; the whole cover is the unlock target.
        return true;
    }

    private void updatePillText() {
        titleView.setText(LocaleController.getString(R.string.PrivacyGuardChatLocked));
        subtitleView.setText(LocaleController.getString(R.string.PrivacyGuardTapToUnlock));
        pill.setContentDescription(titleView.getText() + ". " + subtitleView.getText());
    }

    private void contentAnnounce() {
        announceForAccessibility(LocaleController.getString(R.string.PrivacyGuardActivated));
    }

    private void hideAccessibility() {
        for (int i = 0, n = root.getChildCount(); i < n; i++) {
            final View child = root.getChildAt(i);
            if (child == this || accessibilityBackup.containsKey(child)) {
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

    private void onPillClick() {
        // A tap never plainly reveals: only the owner's Guard passcode (or the camera recognizing the
        // owner again) may open it, so a bystander cannot tap to show.
        unlock();
    }

    /** The owner unlocked (passcode) or was recognized: drop the cover (a new detection raises it again). */
    private void reveal() {
        locked = false;
        wasProtected = false;
        revealedAt = android.os.SystemClock.elapsedRealtime();
        PrivacyGuardController.getInstance().dismiss();
        setHidden(false);
    }

    private void unlock() {
        if (unlockView != null) {
            return;
        }
        // The Guard uses its own passcode (set under the face enrollment). With no passcode set the
        // camera is the only key, so a manual tap just reveals to avoid trapping the owner.
        if (PrivacyGuardSettings.hasGuardPasscode()) {
            showPasscode();
        } else {
            reveal();
        }
    }

    private void showPasscode() {
        if (unlockView != null) {
            return;
        }
        unlockView = new PrivacyGuardUnlockView(activity, null, PrivacyGuardUnlockView.MODE_VERIFY, false, new PrivacyGuardUnlockView.Callback() {
            @Override
            public void onUnlocked() {
                removePasscode();
                reveal();
            }

            @Override
            public void onCancel() {
                removePasscode();
            }

            @Override
            public void onBiometric() {
            }
        });
        root.addView(unlockView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        unlockView.bringToFront();
        unlockView.requestFocus();
    }

    private void removePasscode() {
        if (unlockView != null) {
            root.removeView(unlockView);
            unlockView = null;
        }
    }

    private boolean biometricAvailable() {
        final int authenticators = Build.VERSION.SDK_INT >= 30
                ? BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_WEAK;
        return Build.VERSION.SDK_INT >= 30
                ? BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
                : isDeviceSecure(activity);
    }

    private void biometricAuthenticate() {
        if (authenticating) {
            return;
        }
        final int authenticators = Build.VERSION.SDK_INT >= 30
                ? BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_WEAK;
        if (!biometricAvailable()) {
            if (!PrivacyGuardSettings.hasGuardPasscode()) {
                reveal();
            }
            return;
        }
        authenticating = true;
        BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                authenticating = false;
                removePasscode();
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
}
