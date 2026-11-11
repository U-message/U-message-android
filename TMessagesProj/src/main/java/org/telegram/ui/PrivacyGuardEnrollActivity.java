package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.privacyguard.PrivacyGuardEnrollment;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Settings -> Privacy Guard -> Register My Face. Presented like a phone face setup: intro, a round camera preview
 * with a ring of ticks that fill in as the head turns, then a drawn checkmark. Enrollment logic lives in
 * {@link PrivacyGuardEnrollment}; this screen only renders its progress.
 */
public class PrivacyGuardEnrollActivity extends BaseFragment implements PrivacyGuardEnrollment.Callback {

    private static final int STATE_INTRO = 0;
    private static final int STATE_SCAN = 1;
    private static final int STATE_DONE = 2;
    private static final int STATE_FAILED = 3;

    private static final int GREEN = 0xff34C759;

    private final boolean enableAfter;

    private int state = STATE_INTRO;
    private TextView privacyView;
    private boolean dark;
    private int bgColor, fgColor, grayColor;

    private TextureView textureView;
    private FrameLayout previewFrame;
    private TickRingView ringView;
    private FaceGlyphView glyphView;
    private CheckView checkView;
    private TextView titleView;
    private TextView subtitleView;
    private TextView hintView;
    private TextView buttonView;
    private PrivacyGuardEnrollment enrollment;
    private boolean resumed;
    private boolean done;
    private int size;

    public PrivacyGuardEnrollActivity(boolean enableAfter) {
        this.enableAfter = enableAfter;
    }

    @Override
    public View createView(Context context) {
        dark = AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundWhite)) < 0.5f;
        bgColor = dark ? 0xff000000 : 0xffffffff;
        fgColor = dark ? 0xffffffff : 0xff000000;
        grayColor = dark ? 0xff98989f : 0xff6c6c70;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setBackgroundColor(bgColor);
        actionBar.setItemsColor(fgColor, false);
        actionBar.setItemsBackgroundColor(Theme.multAlpha(fgColor, 0.12f), false);
        actionBar.setTitleColor(fgColor);
        actionBar.setCastShadows(false);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(bgColor);

        size = Math.min(AndroidUtilities.displaySize.x - dp(136), dp(240));
        final int ringSize = size + dp(64);

        FrameLayout visual = new FrameLayout(context);
        // ringSize is already in pixels; LayoutHelper would treat it as dp and make the frame huge
        LinearLayout.LayoutParams visualParams = new LinearLayout.LayoutParams(ringSize, ringSize);
        visualParams.gravity = Gravity.CENTER_HORIZONTAL;
        visualParams.topMargin = dp(28);
        content.addView(visual, visualParams);

        glyphView = new FaceGlyphView(context);
        visual.addView(glyphView, LayoutHelper.createFrame(150, 150, Gravity.CENTER));

        previewFrame = new FrameLayout(context);
        visual.addView(previewFrame, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));

        FrameLayout clip = new FrameLayout(context);
        clip.setBackgroundColor(dark ? 0xff1c1c1e : 0xfff2f2f7);
        clip.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        clip.setClipToOutline(true);
        textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                startIfReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                stopEnrollment();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
        clip.addView(textureView, new FrameLayout.LayoutParams(size, size));
        previewFrame.addView(clip, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        ringView = new TickRingView(context);
        previewFrame.addView(ringView, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));

        checkView = new CheckView(context);
        visual.addView(checkView, LayoutHelper.createFrame(132, 132, Gravity.CENTER));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(fgColor);
        titleView.setGravity(Gravity.CENTER);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 28, 32, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitleView.setTextColor(grayColor);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setLineSpacing(dp(2), 1f);
        content.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 36, 10, 36, 0));

        hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hintView.setTypeface(AndroidUtilities.bold());
        hintView.setTextColor(fgColor);
        hintView.setGravity(Gravity.CENTER);
        content.addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 36, 12, 36, 0));

        View spacer = new View(context);
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        buttonView = new TextView(context);
        buttonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        buttonView.setTypeface(AndroidUtilities.bold());
        buttonView.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        buttonView.setGravity(Gravity.CENTER);
        buttonView.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 14));
        content.addView(buttonView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 24, 12, 24, 28));

        privacyView = new TextView(context);
        privacyView.setText(LocaleController.getString(R.string.PrivacyGuardEnrollPrivacy));
        privacyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        privacyView.setTextColor(grayColor);
        privacyView.setGravity(Gravity.CENTER);
        // no privacy note on this screen (owner's choice)

        showState(STATE_INTRO, false);
        if (animationsEnabled()) {
            glyphView.setScaleX(0.88f);
            glyphView.setScaleY(0.88f);
            glyphView.setAlpha(0f);
            glyphView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setStartDelay(80)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
        // the root must be a FrameLayout: the action bar is added on top of it, a LinearLayout root pushed everything off screen
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(bgColor);
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 0));
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return !dark;
    }

    @Override
    public int getNavigationBarColor() {
        return bgColor;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (getParentActivity() != null) {
            AndroidUtilities.lockOrientation(getParentActivity(), ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        startIfReady();
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
        stopEnrollment();
        if (getParentActivity() != null) {
            AndroidUtilities.unlockOrientation(getParentActivity());
        }
    }

    @Override
    public void onFragmentDestroy() {
        stopEnrollment();
        super.onFragmentDestroy();
    }

    private void startIfReady() {
        if (state != STATE_SCAN || !resumed || done || enrollment != null || textureView == null || !textureView.isAvailable()) {
            return;
        }
        ringView.reset();
        updateScanTexts(PrivacyGuardEnrollment.HINT_NONE);
        enrollment = new PrivacyGuardEnrollment(getParentActivity(), this);
        enrollment.start(textureView.getSurfaceTexture());
    }

    private void stopEnrollment() {
        if (enrollment != null) {
            enrollment.stop();
            enrollment = null;
        }
    }

    // ---- states ----

    private void showState(int newState, boolean animated) {
        state = newState;
        animated = animated && animationsEnabled();
        fadeView(glyphView, newState == STATE_INTRO || newState == STATE_FAILED, animated, 0.9f);
        glyphView.setDimmed(newState == STATE_FAILED);
        fadeView(previewFrame, newState == STATE_SCAN, animated, newState == STATE_DONE ? 0.94f : 0.7f);
        fadeView(checkView, newState == STATE_DONE, animated, 0.8f);
        fadeView(buttonView, newState != STATE_SCAN, animated, 1f);
        switch (newState) {
            case STATE_INTRO:
                setText(titleView, LocaleController.getString(R.string.PrivacyGuardEnrollTitle), animated);
                setText(subtitleView, LocaleController.getString(R.string.PrivacyGuardEnrollIntro), animated);
                setText(hintView, "", animated);
                buttonView.setText(LocaleController.getString(R.string.PrivacyGuardEnrollStart));
                buttonView.setOnClickListener(v -> {
                    showState(STATE_SCAN, true);
                    startIfReady();
                });
                break;
            case STATE_SCAN:
                ringView.reset();
                updateScanTexts(PrivacyGuardEnrollment.HINT_NONE);
                buttonView.setOnClickListener(null);
                break;
            case STATE_DONE:
                setText(titleView, LocaleController.getString(R.string.PrivacyGuardEnrollDone), animated);
                setText(subtitleView, LocaleController.getString(R.string.PrivacyGuardEnrollDoneInfo), animated);
                setText(hintView, "", animated);
                buttonView.setText(LocaleController.getString(R.string.PrivacyGuardEnrollFinish));
                buttonView.setOnClickListener(v -> finishFragment());
                checkView.play(animated);
                break;
            case STATE_FAILED:
                setText(titleView, LocaleController.getString(R.string.PrivacyGuardEnrollFailed), animated);
                setText(hintView, "", animated);
                buttonView.setText(LocaleController.getString(R.string.PrivacyGuardTryAgain));
                buttonView.setOnClickListener(v -> {
                    showState(STATE_SCAN, true);
                    startIfReady();
                });
                break;
        }
    }

    private void updateScanTexts(int hint) {
        // free-form: no directions, only a problem when there is one
        setText(titleView, LocaleController.getString(R.string.PrivacyGuardEnrollFillRing), true);
        setText(subtitleView, "", true);
        final int problem = isProblemHint(hint) ? hintText(hint) : 0;
        setText(hintView, problem != 0 ? LocaleController.getString(problem) : "", true);
    }

    private static boolean isProblemHint(int hint) {
        return hint == PrivacyGuardEnrollment.HINT_NO_FACE || hint == PrivacyGuardEnrollment.HINT_MULTIPLE_FACES
                || hint == PrivacyGuardEnrollment.HINT_MOVE_CLOSER || hint == PrivacyGuardEnrollment.HINT_TOO_DARK
                || hint == PrivacyGuardEnrollment.HINT_OPEN_EYES;
    }

    // ---- enrollment callbacks ----

    @Override
    public void onCameraStarted(int width, int height) {
        // portrait: the upright buffer is the short side wide; crop it to fill the circle
        final float contentAspect = Math.min(width, height) / (float) Math.max(width, height);
        Matrix matrix = new Matrix();
        matrix.setScale(1f, 1f / contentAspect, size / 2f, size / 2f);
        textureView.setTransform(matrix);
    }

    @Override
    public void onCoverage(float[] fill, int hint) {
        if (done || state != STATE_SCAN) {
            return;
        }
        if (ringView.setCoverage(fill)) {
            haptic(HapticFeedbackConstants.KEYBOARD_TAP); // a part of the ring was completed
        }
        updateScanTexts(hint);
    }

    @Override
    public void onFinished(boolean success, int error) {
        final float sideSign = enrollment != null ? enrollment.getSideSign() : 0f;
        stopEnrollment();
        done = success;
        if (success) {
            if (enableAfter) {
                PrivacyGuardSettings.setEnabled(true);
            }
            ringView.setCoverage(new float[]{1f});
            setText(hintView, "", true);
            haptic(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView != null) {
                    showState(STATE_DONE, true);
                }
            }, scaled(650));
        } else {
            final int message;
            switch (error) {
                case PrivacyGuardEnrollment.ERROR_INCONSISTENT: message = R.string.PrivacyGuardEnrollFailedInconsistent; break;
                case PrivacyGuardEnrollment.ERROR_CAMERA: message = R.string.PrivacyGuardEnrollFailedCamera; break;
                default: message = R.string.PrivacyGuardEnrollFailedGeneric; break;
            }
            showState(STATE_FAILED, true);
            setText(subtitleView, LocaleController.getString(message), true);
        }
    }

    private static int stepTitle(int step) {
        switch (step) {
            case PrivacyGuardEnrollment.STEP_SIDE: return R.string.PrivacyGuardEnrollSide;
            case PrivacyGuardEnrollment.STEP_OTHER_SIDE: return R.string.PrivacyGuardEnrollOtherSide;
            case PrivacyGuardEnrollment.STEP_UP: return R.string.PrivacyGuardEnrollUp;
            case PrivacyGuardEnrollment.STEP_DOWN: return R.string.PrivacyGuardEnrollDown;
            default: return R.string.PrivacyGuardEnrollStraight;
        }
    }

    private static int hintText(int hint) {
        switch (hint) {
            case PrivacyGuardEnrollment.HINT_NO_FACE: return R.string.PrivacyGuardEnrollHintNoFace;
            case PrivacyGuardEnrollment.HINT_MULTIPLE_FACES: return R.string.PrivacyGuardEnrollHintMultiple;
            case PrivacyGuardEnrollment.HINT_MOVE_CLOSER: return R.string.PrivacyGuardEnrollHintCloser;
            case PrivacyGuardEnrollment.HINT_TOO_DARK: return R.string.PrivacyGuardEnrollHintDark;
            case PrivacyGuardEnrollment.HINT_OPEN_EYES: return R.string.PrivacyGuardEnrollHintEyes;
            case PrivacyGuardEnrollment.HINT_TURN_MORE: return R.string.PrivacyGuardEnrollHintMore;
            case PrivacyGuardEnrollment.HINT_TURN_LESS: return R.string.PrivacyGuardEnrollHintLess;
            default: return 0;
        }
    }

    // ---- helpers ----

    private static boolean animationsEnabled() {
        return AndroidUtilities.getAnimatorDurationScale() > 0f;
    }

    private static long scaled(long ms) {
        return (long) (ms * Math.max(0f, AndroidUtilities.getAnimatorDurationScale()));
    }

    private void haptic(int type) {
        if (fragmentView == null) {
            return;
        }
        try {
            fragmentView.performHapticFeedback(type, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {
        }
    }

    private static void fadeView(View view, boolean show, boolean animated, float hiddenScale) {
        view.animate().cancel();
        if (!animated) {
            view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            view.setAlpha(show ? 1f : 0f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        if (show) {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 1f) {
                if (view.getVisibility() != View.VISIBLE) {
                    view.setAlpha(0f);
                    view.setScaleX(hiddenScale);
                    view.setScaleY(hiddenScale);
                }
                view.setVisibility(View.VISIBLE);
            }
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setListener(null).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).scaleX(hiddenScale).scaleY(hiddenScale).setStartDelay(0).setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_IN)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            view.setVisibility(View.INVISIBLE);
                            view.setScaleX(1f);
                            view.setScaleY(1f);
                        }
                    }).start();
        }
    }

    private static void setText(TextView view, CharSequence text, boolean animated) {
        final CharSequence current = (CharSequence) view.getTag();
        if (TextUtils.equals(current, text)) {
            return;
        }
        view.setTag(text);
        view.animate().cancel();
        if (!animated || !animationsEnabled() || view.getVisibility() != View.VISIBLE) {
            view.setText(text);
            view.setAlpha(1f);
            return;
        }
        view.animate().alpha(0f).setDuration(110).setStartDelay(0).setInterpolator(CubicBezierInterpolator.EASE_IN)
                .withEndAction(() -> {
                    view.setText(text);
                    view.animate().alpha(1f).setDuration(170).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
                }).start();
    }

    // ---- views ----

    /**
     * Ring of radial ticks around the preview. Sector layout (screen space, preview is mirrored like a mirror):
     * top = head up, bottom = head down, left/right = the two side turns. The straight step brings the ring from
     * faint to ready; each later step fills its sector from the middle outward.
     */
    private class TickRingView extends View {

        private static final int TICKS = 72;
        private static final int SECTOR_UP = 0, SECTOR_RIGHT = 1, SECTOR_DOWN = 2, SECTOR_LEFT = 3;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final int SECTORS = 24; // must match PrivacyGuardEnrollment
        private final float[] shown = new float[SECTORS];
        private final float[] target = new float[SECTORS];
        private final ValueAnimator[] animators = new ValueAnimator[SECTORS];
        private float readyShown, readyTarget;
        private ValueAnimator readyAnimator;
        int step;
        private int targetSector = -1;
        private boolean bothSidesTarget;

        TickRingView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(2.6f));
        }

        void reset() {
            for (int i = 0; i < SECTORS; i++) {
                if (animators[i] != null) {
                    animators[i].cancel();
                }
                shown[i] = target[i] = 0;
            }
            if (readyAnimator != null) {
                readyAnimator.cancel();
            }
            readyShown = readyTarget = 0;
            step = 0;
            targetSector = -1;
            bothSidesTarget = false;
            invalidate();
        }

        /** Fills each quarter from its bucket (any order). Returns true when a quarter just became full. */
        boolean setCoverage(float[] fill) {
            boolean completed = false;
            for (int i = 0; i < SECTORS; i++) {
                final float v = fill.length > 1 + i ? fill[1 + i] : 1f;
                if (v >= 1f && target[i] < 1f) completed = true;
                animateSector(i, v);
            }
            targetSector = -1;
            bothSidesTarget = false;
            final float ready = fill[0];
            if (ready != readyTarget) {
                readyTarget = ready;
                if (readyAnimator != null) {
                    readyAnimator.cancel();
                }
                readyAnimator = ValueAnimator.ofFloat(readyShown, ready);
                readyAnimator.addUpdateListener(a -> {
                    readyShown = (float) a.getAnimatedValue();
                    invalidate();
                });
                readyAnimator.setDuration(260);
                readyAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
                readyAnimator.start();
            }
            invalidate();
            return completed;
        }

        private void animateSector(int i, float value) {
            if (value == target[i]) {
                return;
            }
            target[i] = value;
            if (animators[i] != null) {
                animators[i].cancel();
            }
            final int index = i;
            final float from = shown[i];
            // ~150 ms per tick crossfade; longer sweeps take a little longer so ticks light one after another
            final long duration = 180;
            ValueAnimator animator = ValueAnimator.ofFloat(from, value);
            animator.addUpdateListener(a -> {
                shown[index] = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(duration);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animators[i] = animator;
            animator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float inner = size / 2f + dp(11);
            final float baseLength = dp(9);
            final float litExtra = dp(5);
            final boolean anim = animationsEnabled();
            final float pulse = anim ? (float) (0.5 + 0.5 * Math.sin(SystemClock.uptimeMillis() / 260.0)) : 1f;
            final float idleAlpha = 0.18f + 0.22f * readyShown;
            boolean pulsing = false;
            for (int k = 0; k < TICKS; k++) {
                final float deg = k * 360f / TICKS; // 0 = top, clockwise
                final float lit = shown[Math.min(SECTORS - 1, (int) (deg / (360f / SECTORS)))];
                float alpha = idleAlpha;
                final int idle = Theme.multAlpha(fgColor, alpha);
                paint.setColor(ColorUtils.blendARGB(idle, GREEN, lit));
                final float length = baseLength + litExtra * lit;
                final double rad = Math.toRadians(deg - 90f);
                final float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                canvas.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * (inner + length), cy + sin * (inner + length), paint);
            }
            if (pulsing && anim) {
                postInvalidateOnAnimation();
            }
        }

        private int sectorOf(float deg) {
            if (deg >= 315f || deg < 45f) return SECTOR_UP;
            if (deg < 135f) return SECTOR_RIGHT;
            if (deg < 225f) return SECTOR_DOWN;
            return SECTOR_LEFT;
        }
    }

    /** Intro / failure glyph: a ring of ticks around a simple line-drawn face. */
    private class FaceGlyphView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean dimmed;

        FaceGlyphView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setDimmed(boolean dimmed) {
            if (this.dimmed != dimmed) {
                this.dimmed = dimmed;
                invalidate();
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float w = getWidth(), cx = w / 2f, cy = getHeight() / 2f;
            final int color = dimmed ? grayColor : fgColor;
            // ticks
            paint.setStrokeWidth(dp(2.4f));
            paint.setColor(Theme.multAlpha(color, 0.35f));
            final float rIn = w * 0.40f, rOut = w * 0.48f;
            for (int k = 0; k < 48; k++) {
                final double rad = Math.toRadians(k * 7.5);
                final float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                canvas.drawLine(cx + cos * rIn, cy + sin * rIn, cx + cos * rOut, cy + sin * rOut, paint);
            }
            // face
            paint.setColor(color);
            paint.setStrokeWidth(dp(3.6f));
            final float u = w * 0.07f;
            canvas.drawLine(cx - u * 1.5f, cy - u * 1.4f, cx - u * 1.5f, cy - u * 0.6f, paint);
            canvas.drawLine(cx + u * 1.5f, cy - u * 1.4f, cx + u * 1.5f, cy - u * 0.6f, paint);
            rect.set(cx - u * 2.1f, cy - u * 1.2f, cx + u * 2.1f, cy + u * 2.1f);
            canvas.drawArc(rect, 30, 120, false, paint);
        }
    }

    /** Green circle drawn on, then a checkmark stroked inside it. */
    private class CheckView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path check = new Path();
        private final Path segment = new Path();
        private final PathMeasure measure = new PathMeasure();
        private float progress = 1f;
        private ValueAnimator animator;

        CheckView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(GREEN);
        }

        void play(boolean animated) {
            if (animator != null) {
                animator.cancel();
            }
            if (!animated) {
                progress = 1f;
                invalidate();
                return;
            }
            progress = 0f;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(820);
            animator.setStartDelay(60);
            animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            animator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float stroke = dp(5);
            paint.setStrokeWidth(stroke);
            rect.set(stroke, stroke, w - stroke, h - stroke);
            final float circle = Math.min(1f, progress / 0.55f);
            if (circle > 0) {
                canvas.drawArc(rect, -90, 360 * circle, false, paint);
            }
            final float tick = Math.max(0f, Math.min(1f, (progress - 0.45f) / 0.55f));
            if (tick > 0) {
                check.reset();
                check.moveTo(w * 0.29f, h * 0.52f);
                check.lineTo(w * 0.44f, h * 0.67f);
                check.lineTo(w * 0.72f, h * 0.37f);
                measure.setPath(check, false);
                segment.reset();
                measure.getSegment(0, measure.getLength() * tick, segment, true);
                paint.setStrokeWidth(dp(6));
                canvas.drawPath(segment, paint);
            }
        }
    }
}
