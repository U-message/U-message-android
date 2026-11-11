package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageAiEraser;
import org.telegram.ui.Components.blur3.LiquidGlassEffect;

/** Full screen brush editor with a reversible preview; nothing is saved until Done. */
public class UMessageAiEraserView extends FrameLayout {
    private static final DispatchQueue inferenceQueue = new DispatchQueue("aiEraser");
    private static final int ACCENT = 0xff3d8af7, ACCENT2 = 0xffa25cf6, SUCCESS = 0xff34c759, FAIL = 0xffff453a;
    private static final int STATE_IDLE = 0, STATE_PROGRESS = 1, STATE_DONE = 2, STATE_FAIL = 3;

    public interface Delegate { void onDone(Bitmap bitmap); }
    private Bitmap current, previous;
    private final Bitmap original, mask;
    private final Canvas maskCanvas;
    private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SelectionView selection;
    private final TextView status, doneButton;
    private final ImageView undo, compare;
    private final LinearLayout erase;
    private final LiquidGlassSlider brushSlider;
    private final FrameLayout topBar;
    private final LinearLayout panel;
    private Runnable onClose;
    private volatile boolean cancelled;
    private boolean busy, selected, changed, comparing;
    private float brushSize = 28;

    public UMessageAiEraserView(Context context, Bitmap bitmap, Delegate delegate) {
        super(context);
        setBackgroundColor(Color.BLACK);
        original = current = bitmap;
        mask = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        maskCanvas = new Canvas(mask);
        brush.setColor(Color.WHITE);
        brush.setStrokeCap(Paint.Cap.ROUND);
        brush.setStrokeJoin(Paint.Join.ROUND);
        brush.setStyle(Paint.Style.STROKE);

        selection = new SelectionView(context);
        addView(selection, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        topBar = new FrameLayout(context);
        addView(topBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));
        ImageView back = glassIcon(context, R.drawable.ic_ab_back);
        back.setOnClickListener(v -> close());
        topBar.addView(back, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
        TextView title = new TextView(context);
        title.setText(text(R.string.AiEraserTitle));
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        doneButton = new TextView(context);
        doneButton.setText(text(R.string.Done));
        doneButton.setTextColor(Color.WHITE);
        doneButton.setTextSize(15);
        doneButton.setTypeface(AndroidUtilities.bold());
        doneButton.setGravity(Gravity.CENTER);
        doneButton.setPadding(dp(18), 0, dp(18), 0);
        doneButton.setBackground(new GlassDrawable(22, ACCENT));
        ScaleStateListAnimator.apply(doneButton);
        doneButton.setOnClickListener(v -> { if (!busy && changed && !selected) delegate.onDone(current); });
        topBar.addView(doneButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new GlassDrawable(28, 0));
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 12, 0, 12, 12));

        status = new TextView(context);
        status.setTextColor(0xccffffff);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setText(text(UMessageAiEraser.isModelReady() ? R.string.AiEraserHint : R.string.AiEraserDownloadHint));
        panel.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 6));

        LinearLayout brushRow = new LinearLayout(context);
        brushRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(brushRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        TextView brushLabel = new TextView(context);
        brushLabel.setText(text(R.string.AiEraserBrush));
        brushLabel.setTextColor(0x99ffffff);
        brushLabel.setTextSize(13);
        brushRow.addView(brushLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 0));
        brushSlider = new LiquidGlassSlider(context);
        brushSlider.setColors(ACCENT, 0x33ffffff);
        brushSlider.setProgress(20 / 72f);
        brushSlider.setDelegate((progress, stop) -> {
            brushSize = 8 + progress * 72;
            selection.showBrush(!stop);
        });
        brushRow.addView(brushSlider, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 6, 0, 0));
        undo = glassIcon(context, R.drawable.photo_undo2);
        actions.addView(undo, LayoutHelper.createLinear(52, 52));
        erase = new LinearLayout(context);
        erase.setGravity(Gravity.CENTER);
        erase.setBackground(new GradientPillDrawable());
        ImageView eraseIcon = new ImageView(context);
        eraseIcon.setImageResource(R.drawable.media_magic_cut);
        eraseIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        erase.addView(eraseIcon, LayoutHelper.createLinear(24, 24, 0, 0, 8, 0));
        TextView eraseText = new TextView(context);
        eraseText.setText(text(R.string.AiEraserRemove));
        eraseText.setTextColor(Color.WHITE);
        eraseText.setTextSize(15);
        eraseText.setTypeface(AndroidUtilities.bold());
        erase.addView(eraseText);
        ScaleStateListAnimator.apply(erase);
        actions.addView(erase, LayoutHelper.createLinear(0, 52, 1f, 10, 0, 10, 0));
        compare = glassIcon(context, 0);
        compare.setImageDrawable(new CompareDrawable());
        compare.setContentDescription(text(R.string.AiEraserCompare));
        actions.addView(compare, LayoutHelper.createLinear(52, 52));

        undo.setOnClickListener(v -> {
            if (selected) clearMask();
            else if (previous != null) { current = previous; previous = null; changed = current != original; }
            status.setText(text(R.string.AiEraserHint));
            selection.invalidate(); updateButtons();
        });
        erase.setOnClickListener(v -> remove());
        compare.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            boolean pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
            if (pressed != comparing) {
                comparing = pressed;
                v.animate().scaleX(pressed ? 0.9f : 1f).scaleY(pressed ? 0.9f : 1f).setDuration(160).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                if (pressed) status.setText(text(R.string.AiEraserCompare));
                selection.invalidate();
            }
            return true;
        });
        setOnApplyWindowInsetsListener((v, insets) -> {
            ((MarginLayoutParams) topBar.getLayoutParams()).topMargin = insets.getSystemWindowInsetTop();
            ((MarginLayoutParams) panel.getLayoutParams()).bottomMargin = dp(12) + insets.getSystemWindowInsetBottom();
            requestLayout();
            return insets.consumeSystemWindowInsets();
        });
        updateButtons();
    }

    public void setOnClose(Runnable onClose) { this.onClose = onClose; }
    private void close() { cancel(); if (onClose != null) onClose.run(); }

    private static String text(int id) { return LocaleController.getString(id); }
    private ImageView glassIcon(Context context, int icon) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        if (icon != 0) {
            view.setImageResource(icon);
            view.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        view.setBackground(new GlassDrawable(26, 0));
        ScaleStateListAnimator.apply(view);
        return view;
    }
    private void clearMask() { mask.eraseColor(Color.TRANSPARENT); selected = false; }
    private static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.animate().alpha(enabled ? 1f : 0.4f).setDuration(180).start();
    }
    private void updateButtons() {
        enable(erase, !busy && selected);
        enable(undo, !busy && (selected || previous != null));
        enable(doneButton, !busy && changed && !selected);
        enable(compare, !busy && changed);
        brushSlider.setEnabled(!busy);
    }

    private void remove() {
        if (busy || !selected) return;
        busy = true; updateButtons();
        boolean downloading = !UMessageAiEraser.isModelReady();
        status.setText(text(downloading ? R.string.AiEraserDownloading : R.string.AiEraserWorking));
        selection.startProgress(downloading);
        Bitmap input = current;
        Bitmap selectionMask = mask.copy(Bitmap.Config.ARGB_8888, false);
        inferenceQueue.postRunnable(() -> {
            try {
                Bitmap result = UMessageAiEraser.erase(input, selectionMask, new UMessageAiEraser.Progress() {
                    public boolean isCancelled() { return cancelled; }
                    public void onDownload(int percent) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (cancelled) return;
                            if (percent > 100) {
                                status.setText(text(R.string.AiEraserWorking));
                                selection.startWorking();
                            } else {
                                selection.setDownloadProgress(percent / 100f);
                            }
                        });
                    }
                });
                AndroidUtilities.runOnUIThread(() -> {
                    if (cancelled) { result.recycle(); return; }
                    selection.finish(true, () -> {
                        previous = current; current = result; changed = true; busy = false;
                        clearMask(); status.setText(text(R.string.AiEraserReady));
                        selection.invalidate(); updateButtons();
                    });
                });
            } catch (Exception | OutOfMemoryError error) {
                FileLog.e(error);
                AndroidUtilities.runOnUIThread(() -> {
                    if (cancelled) return;
                    selection.finish(false, () -> {
                        busy = false; status.setText(text(R.string.AiEraserError)); updateButtons();
                    });
                });
            } finally { selectionMask.recycle(); }
        });
    }

    public void cancel() { cancelled = true; }
    @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }

    /** Frosted glass surface: faint fill, top lit rim and a soft specular streak, optionally tinted. */
    private static class GlassDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final int tint, fillAlpha;
        private int alpha = 255;

        GlassDrawable(float radiusDp, int tint) {
            this.radius = dp(radiusDp);
            this.tint = tint;
            fill.setColor(tint != 0 ? ColorUtils.setAlphaComponent(tint, 0xd9) : 0x2effffff);
            fillAlpha = fill.getAlpha();
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1, dp(1f)));
        }

        @Override protected void onBoundsChange(@NonNull android.graphics.Rect bounds) {
            rim.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, new int[]{0x80ffffff, 0x14ffffff, 0x33ffffff}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
            shine.setShader(new LinearGradient(0, bounds.top, 0, bounds.top + bounds.height() * 0.5f, 0x26ffffff, 0, Shader.TileMode.CLAMP));
        }

        @Override public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            float r = Math.min(radius, rect.height() / 2f);
            fill.setAlpha(fillAlpha * alpha / 255);
            canvas.drawRoundRect(rect, r, r, fill);
            shine.setAlpha(alpha);
            canvas.drawRoundRect(rect, r, r, shine);
            rect.inset(rim.getStrokeWidth() / 2f, rim.getStrokeWidth() / 2f);
            rim.setAlpha(alpha);
            canvas.drawRoundRect(rect, r, r, rim);
        }

        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static class GradientPillDrawable extends GlassDrawable {
        private final Paint gradient = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        GradientPillDrawable() { super(26, 0); }

        @Override protected void onBoundsChange(@NonNull android.graphics.Rect b) {
            super.onBoundsChange(b);
            gradient.setShader(new LinearGradient(b.left, 0, b.right, 0, ACCENT2, ACCENT, Shader.TileMode.CLAMP));
        }

        @Override public void draw(@NonNull Canvas canvas) {
            bounds.set(getBounds());
            float r = bounds.height() / 2f;
            canvas.drawRoundRect(bounds, r, r, gradient);
            super.draw(canvas);
        }
    }

    /** Before/after split square. */
    private static class CompareDrawable extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        CompareDrawable() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.8f));
            stroke.setColor(Color.WHITE);
            fill.setColor(Color.WHITE);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            float cx = getBounds().centerX(), cy = getBounds().centerY(), s = dp(9);
            rect.set(cx - s, cy - s, cx + s, cy + s);
            canvas.drawRoundRect(rect, dp(4), dp(4), stroke);
            canvas.save();
            canvas.clipRect(cx, cy - s, cx + s, cy + s);
            canvas.drawRoundRect(rect, dp(4), dp(4), fill);
            canvas.restore();
            canvas.drawLine(cx, cy - s - dp(3), cx, cy + s + dp(3), stroke);
        }

        @Override public int getIntrinsicWidth() { return dp(24); }
        @Override public int getIntrinsicHeight() { return dp(24); }
        @Override public void setAlpha(int alpha) { stroke.setAlpha(alpha); fill.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private class SelectionView extends View {
        private final RectF bounds = new RectF(), card = new RectF(), arc = new RectF();
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG), cardRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringTrack = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint resultFill = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final AnimatedTextView.AnimatedTextDrawable percentText = new AnimatedTextView.AnimatedTextDrawable(false, true, true);
        private final AnimatedFloat brushAlpha = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final Matrix sweepMatrix = new Matrix();
        private final Path markPath = new Path(), markSegment = new Path();
        private final PathMeasure measure = new PathMeasure();
        private SweepGradient sweep;
        private RenderNode imageNode;
        private LiquidGlassEffect glass;
        private float lastX, lastY;
        private boolean drawing, brushVisible;

        private int state = STATE_IDLE;
        private boolean simulating;
        private long simulateStart, lastFrame;
        private float target, shown, appear, result;
        private int shownPercent = -1;
        private String label = "";
        private ValueAnimator appearAnimator, resultAnimator;
        private Runnable pendingFinish;
        private boolean pendingSuccess;

        SelectionView(Context context) {
            super(context);
            overlayPaint.setColorFilter(new PorterDuffColorFilter(ACCENT2, PorterDuff.Mode.SRC_IN));
            brushPaint.setStyle(Paint.Style.STROKE);
            brushPaint.setStrokeWidth(dp(2));
            brushPaint.setColor(Color.WHITE);
            cardPaint.setColor(0x8c1c1c1e);
            cardRim.setStyle(Paint.Style.STROKE);
            cardRim.setStrokeWidth(Math.max(1, dp(1)));
            ringTrack.setStyle(Paint.Style.STROKE);
            ringTrack.setStrokeWidth(dp(6));
            ringTrack.setColor(0x26ffffff);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(6));
            ring.setStrokeCap(Paint.Cap.ROUND);
            mark.setStyle(Paint.Style.STROKE);
            mark.setStrokeWidth(dp(5));
            mark.setStrokeCap(Paint.Cap.ROUND);
            mark.setStrokeJoin(Paint.Join.ROUND);
            mark.setColor(Color.WHITE);
            labelPaint.setColor(0xd9ffffff);
            labelPaint.setTextSize(dp(13));
            labelPaint.setTypeface(AndroidUtilities.bold());
            percentText.setCallback(this);
            percentText.setTextColor(Color.WHITE);
            percentText.setTextSize(dp(26));
            percentText.setTypeface(AndroidUtilities.bold());
            percentText.setGravity(Gravity.CENTER);
            percentText.setAnimationProperties(0.3f, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    imageNode = new RenderNode("AiEraserImage");
                    glass = new LiquidGlassEffect(imageNode);
                } catch (Throwable e) {
                    imageNode = null;
                    glass = null;
                }
            }
        }

        @Override protected boolean verifyDrawable(@NonNull Drawable who) { return who == percentText || super.verifyDrawable(who); }

        void showBrush(boolean show) { brushVisible = show; invalidate(); }

        void startProgress(boolean downloading) {
            state = STATE_PROGRESS;
            pendingFinish = null;
            target = shown = result = 0;
            shownPercent = -1;
            simulating = !downloading;
            simulateStart = SystemClock.elapsedRealtime();
            label = text(downloading ? R.string.AiEraserDownloading : R.string.AiEraserWorking);
            percentText.setText("0%", false);
            if (resultAnimator != null) resultAnimator.cancel();
            animateAppear(1f, null);
        }

        void setDownloadProgress(float value) {
            if (state != STATE_PROGRESS || simulating) return;
            target = value;
            invalidate();
        }

        /** Inference reports no progress, so ease toward 97% until the result arrives. */
        void startWorking() {
            if (state != STATE_PROGRESS || simulating) return;
            simulating = true;
            simulateStart = SystemClock.elapsedRealtime();
            target = shown = 0;
            shownPercent = -1;
            label = text(R.string.AiEraserWorking);
            invalidate();
        }

        void finish(boolean success, Runnable after) {
            if (state != STATE_PROGRESS) { after.run(); return; }
            simulating = false;
            pendingSuccess = success;
            pendingFinish = after;
            if (success) target = 1f;
            else startResult();
            invalidate();
        }

        private void startResult() {
            state = pendingSuccess ? STATE_DONE : STATE_FAIL;
            label = text(pendingSuccess ? R.string.Done : R.string.ErrorOccurred);
            resultFill.setColor(pendingSuccess ? SUCCESS : FAIL);
            markPath.reset();
            float s = dp(15);
            if (pendingSuccess) {
                markPath.moveTo(-s * 0.9f, 0);
                markPath.lineTo(-s * 0.25f, s * 0.65f);
                markPath.lineTo(s, -s * 0.6f);
            } else {
                float x = s * 0.7f;
                markPath.moveTo(-x, -x); markPath.lineTo(x, x);
                markPath.moveTo(x, -x); markPath.lineTo(-x, x);
            }
            try {
                performHapticFeedback(pendingSuccess ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) { }
            resultAnimator = ValueAnimator.ofFloat(0, 1);
            resultAnimator.setDuration(pendingSuccess ? 650 : 750);
            resultAnimator.addUpdateListener(a -> { result = (float) a.getAnimatedValue(); invalidate(); });
            resultAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    Runnable after = pendingFinish;
                    pendingFinish = null;
                    AndroidUtilities.runOnUIThread(() -> animateAppear(0f, () -> {
                        state = STATE_IDLE;
                        if (after != null) after.run();
                    }), pendingSuccess ? 450 : 900);
                }
            });
            resultAnimator.start();
        }

        private void animateAppear(float to, Runnable end) {
            if (appearAnimator != null) appearAnimator.cancel();
            appearAnimator = ValueAnimator.ofFloat(appear, to);
            appearAnimator.setDuration(to > appear ? 380 : 260);
            appearAnimator.setInterpolator(to > appear ? new android.view.animation.OvershootInterpolator(1.4f) : CubicBezierInterpolator.EASE_OUT_QUINT);
            appearAnimator.addUpdateListener(a -> { appear = (float) a.getAnimatedValue(); invalidate(); });
            if (end != null) appearAnimator.addListener(new AnimatorListenerAdapter() {
                private boolean canceled;
                @Override public void onAnimationCancel(Animator animation) { canceled = true; }
                @Override public void onAnimationEnd(Animator animation) { if (!canceled) end.run(); }
            });
            appearAnimator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            float top = topBar.getBottom() + dp(8), bottom = panel.getTop() - dp(8);
            if (bottom - top < dp(80)) { top = 0; bottom = getHeight(); }
            float availW = getWidth() - dp(16), availH = bottom - top;
            float scale = Math.min(availW / current.getWidth(), availH / current.getHeight());
            float w = current.getWidth() * scale, h = current.getHeight() * scale;
            float cy = (top + bottom) / 2f;
            bounds.set((getWidth() - w) / 2, cy - h / 2, (getWidth() + w) / 2, cy + h / 2);

            boolean overlay = state != STATE_IDLE && appear > 0.01f;
            if (overlay) layoutCard();
            if (overlay && glass != null && imageNode != null && canvas.isHardwareAccelerated()) {
                imageNode.setPosition(0, 0, getWidth(), getHeight());
                Canvas c = imageNode.beginRecording();
                drawImage(c);
                imageNode.endRecording();
                float r = dp(36) * Math.min(1f, appear);
                glass.update(card.left, card.top, card.right, card.bottom, r, r, r, r,
                    dp(16), 0.9f * Math.min(1f, appear), 1.5f, ColorUtils.setAlphaComponent(0xff101014, (int) (0x66 * Math.min(1f, appear))));
                canvas.drawRenderNode(imageNode);
            } else {
                drawImage(canvas);
                if (overlay) {
                    cardPaint.setAlpha((int) (0x8c * Math.min(1f, appear)));
                    canvas.drawRoundRect(card, dp(36), dp(36), cardPaint);
                }
            }

            float ba = brushAlpha.set(brushVisible && !busy);
            if (ba > 0) {
                brushPaint.setAlpha((int) (255 * ba));
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), dp(brushSize) / 2f, brushPaint);
            }
            if (overlay) drawProgress(canvas);
        }

        private void drawImage(Canvas canvas) {
            canvas.drawBitmap(comparing ? original : current, null, bounds, imagePaint);
            if (!comparing) {
                overlayPaint.setAlpha(150);
                canvas.drawBitmap(mask, null, bounds, overlayPaint);
            }
        }

        private void layoutCard() {
            float size = dp(168) * (0.7f + 0.3f * appear);
            float cx = bounds.centerX(), cy = bounds.centerY();
            if (state == STATE_FAIL && result < 1f) cx += (float) Math.sin(result * Math.PI * 6) * dp(10) * (1f - result);
            card.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        }

        private void drawProgress(Canvas canvas) {
            long now = SystemClock.elapsedRealtime();
            float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
            lastFrame = now;
            if (state == STATE_PROGRESS) {
                if (simulating) target = 0.97f * (1f - (float) Math.exp(-(now - simulateStart) / 3200f));
                shown += (target - shown) * Math.min(1f, dt / (pendingFinish != null ? 60f : 140f));
                if (Math.abs(target - shown) < 0.002f) shown = target;
                int percent = Math.round(shown * 100);
                if (percent != shownPercent) {
                    shownPercent = percent;
                    percentText.setText(percent + "%", true);
                }
                if (pendingFinish != null && pendingSuccess && shown >= 0.999f) startResult();
            }

            float alpha = Math.max(0, Math.min(1f, appear));
            int a = (int) (255 * alpha);
            float cx = card.centerX(), ringCy = card.centerY() - dp(10), radius = dp(36);
            float corner = dp(36) * (card.width() / dp(168));

            cardRim.setShader(new LinearGradient(0, card.top, 0, card.bottom, new int[]{0x8cffffff, 0x14ffffff, 0x40ffffff}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
            cardRim.setAlpha(a);
            canvas.drawRoundRect(card, corner, corner, cardRim);

            canvas.save();
            float s = card.width() / dp(168);
            canvas.scale(s, s, cx, card.centerY());

            ringTrack.setAlpha((int) (0x26 * alpha));
            canvas.drawCircle(cx, ringCy, radius, ringTrack);

            float res = state == STATE_DONE || state == STATE_FAIL ? result : 0f;
            float sweepValue = state == STATE_PROGRESS ? shown : (state == STATE_DONE ? 1f : shown);
            if (sweep == null) sweep = new SweepGradient(0, 0, new int[]{ACCENT2, ACCENT, 0xff5ad8ff, ACCENT2}, null);
            sweepMatrix.setRotate((now % 2400) / 2400f * 360f);
            sweepMatrix.postTranslate(cx, ringCy);
            sweep.setLocalMatrix(sweepMatrix);
            ring.setShader(sweep);
            ring.setAlpha((int) (a * (1f - Math.min(1f, res * 1.6f))));
            if (sweepValue > 0) {
                arc.set(cx - radius, ringCy - radius, cx + radius, ringCy + radius);
                canvas.drawArc(arc, -90, 360 * sweepValue, false, ring);
            }

            if (res > 0) {
                float pop = CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(Math.min(1f, res * 1.5f));
                resultFill.setAlpha(a);
                canvas.drawCircle(cx, ringCy, radius * pop, resultFill);
                float draw = Math.max(0, Math.min(1f, (res - 0.3f) / 0.6f));
                if (draw > 0) {
                    canvas.save();
                    canvas.translate(cx, ringCy);
                    markSegment.reset();
                    measure.setPath(markPath, false);
                    float total = 0;
                    do { total += measure.getLength(); } while (measure.nextContour());
                    measure.setPath(markPath, false);
                    float remaining = total * draw;
                    do {
                        float len = measure.getLength();
                        measure.getSegment(0, Math.min(len, remaining), markSegment, true);
                        remaining -= len;
                    } while (remaining > 0 && measure.nextContour());
                    mark.setAlpha(a);
                    canvas.drawPath(markSegment, mark);
                    canvas.restore();
                }
            }

            float textAlpha = 1f - Math.min(1f, res * 2.5f);
            if (textAlpha > 0) {
                percentText.setAlpha((int) (a * textAlpha));
                percentText.setBounds((int) (cx - radius), (int) (ringCy - dp(20)), (int) (cx + radius), (int) (ringCy + dp(20)));
                percentText.draw(canvas);
            }

            labelPaint.setColor(state == STATE_DONE ? SUCCESS : state == STATE_FAIL ? FAIL : 0xd9ffffff);
            labelPaint.setAlpha(a);
            CharSequence line = TextUtils.ellipsize(label, labelPaint, dp(150), TextUtils.TruncateAt.END);
            float lw = labelPaint.measureText(line, 0, line.length());
            canvas.drawText(line, 0, line.length(), cx - lw / 2f, ringCy + radius + dp(28), labelPaint);
            canvas.restore();

            if (state == STATE_PROGRESS) invalidate();
            else lastFrame = 0;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (busy || comparing || bounds.width() == 0) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!bounds.contains(event.getX(), event.getY())) return false;
                drawing = true; getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (!drawing) return false;
            float x = Math.max(0, Math.min(current.getWidth() - 1, (event.getX() - bounds.left) * current.getWidth() / bounds.width()));
            float y = Math.max(0, Math.min(current.getHeight() - 1, (event.getY() - bounds.top) * current.getHeight() / bounds.height()));
            brush.setStrokeWidth(dp(brushSize) * current.getWidth() / bounds.width());
            if (action == MotionEvent.ACTION_DOWN) {
                maskCanvas.drawPoint(x, y, brush); selected = true;
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                maskCanvas.drawLine(lastX, lastY, x, y, brush); selected = true;
            }
            lastX = x; lastY = y;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                drawing = false; getParent().requestDisallowInterceptTouchEvent(false);
            }
            invalidate(); updateButtons(); return true;
        }
    }
}
