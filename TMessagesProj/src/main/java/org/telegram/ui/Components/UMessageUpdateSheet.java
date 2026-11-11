package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * The in-app update sheet in "liquid glass": a translucent panel floating over the blurred app (real blur on
 * Android 12+, a deeper tint before that). One sheet walks through the whole flow — offer, download progress,
 * ready to install, error — so it never flickers between dialogs. The release notes scroll inside the panel,
 * which never grows past the screen, so the buttons always stay reachable however long the admin's text is.
 */
public class UMessageUpdateSheet {

    private static final int ACCENT = 0xff0a84ff;
    private static final int TEXT = 0xfff2f4f8;
    private static final int TEXT_2 = 0xc8f2f4f8;

    private final Activity activity;
    private final Dialog dialog;
    private final FrameLayout content;
    private final boolean blur;
    private ProgressRing ring;
    private TextView progressTitle, progressInfo;
    private long speedAt, speedBytes;
    private float speed;

    public UMessageUpdateSheet(Activity activity, boolean cancelable) {
        this.activity = activity;
        blur = Build.VERSION.SDK_INT >= 31;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        content = new FrameLayout(activity);
        dialog.setContentView(content);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(glass());
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(AndroidUtilities.displaySize.x, dp(480)) - dp(20);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(10);
            lp.dimAmount = 0.35f;
            lp.windowAnimations = R.style.DialogNoAnimation;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (blur) {
                try {
                    window.setBackgroundBlurRadius(dp(30)); // the glass itself, clipped to the rounded background
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    window.getAttributes().setBlurBehindRadius(dp(6)); // a soft focus on the rest of the app
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
    }

    public void setOnDismissListener(Runnable r) {
        dialog.setOnDismissListener(d -> r.run());
    }

    public void show() {
        try {
            dialog.show();
            content.setAlpha(0f);
            content.setTranslationY(dp(40));
            content.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void dismiss() {
        try {
            dialog.dismiss();
        } catch (Throwable ignore) {
        }
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    // ---------------------------------------------------------------- states

    /** "New version available": icon, version, size, scrollable release notes, Update / Later. */
    public void showOffer(String title, String versionName, long size, String notes, Runnable onUpdate, Runnable onLater) {
        LinearLayout panel = panel();
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(appIcon(), LayoutHelper.createLinear(60, 60, 0, 0, 14, 0));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(21, TEXT, true);
        t.setText(title);
        titles.addView(t);
        LinearLayout meta = new LinearLayout(activity);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        if (!TextUtils.isEmpty(versionName)) {
            TextView pill = text(12, TEXT, true);
            pill.setText(versionName);
            pill.setPadding(dp(9), dp(3), dp(9), dp(3));
            pill.setBackground(rounded(0x29ffffff, 0x38ffffff, dp(12)));
            meta.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        }
        if (size > 0) {
            TextView sz = text(13, TEXT_2, false);
            sz.setText(AndroidUtilities.formatFileSize(size) + " · " + LocaleController.getString(R.string.UMessageUpdateWifi));
            sz.setSingleLine(true);
            sz.setEllipsize(TextUtils.TruncateAt.END);
            meta.addView(sz);
        }
        titles.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        panel.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 0));

        TextView label = text(13, TEXT_2, true);
        label.setText(LocaleController.getString(R.string.UMessageUpdateNews).toUpperCase());
        label.setLetterSpacing(0.08f);
        panel.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 8));

        // the notes scroll inside a box capped to the screen, so long text never pushes the buttons away
        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.45f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFadingEdgeLength(dp(24));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        TextView body = text(15, TEXT, false);
        body.setLineSpacing(dp(3), 1f);
        body.setText(formatNotes(notes));
        body.setPadding(0, 0, 0, dp(12));
        scroll.addView(body);
        panel.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 0));

        LinearLayout buttons = buttonBar();
        buttons.addView(primary(LocaleController.getString(R.string.UMessageUpdateNow), onUpdate), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));
        if (onLater != null) {
            buttons.addView(secondary(LocaleController.getString(R.string.UMessageUpdateLater), () -> {
                onLater.run();
                dismiss();
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 10, 0, 0));
        }
        panel.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        setPanel(panel);
    }

    /** Ring with the percentage, size done / total, speed and time left; Cancel. */
    public void showProgress(Runnable onCancel) {
        LinearLayout panel = panel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        ring = new ProgressRing(activity);
        panel.addView(ring, LayoutHelper.createLinear(132, 132, Gravity.CENTER_HORIZONTAL, 0, 22, 0, 0));
        progressTitle = text(20, TEXT, true);
        progressTitle.setGravity(Gravity.CENTER);
        progressTitle.setText(LocaleController.getString(R.string.UMessageUpdateDownloading));
        panel.addView(progressTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 18, 22, 0));
        progressInfo = text(14, TEXT_2, false);
        progressInfo.setGravity(Gravity.CENTER);
        panel.addView(progressInfo, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 6, 22, 0));
        panel.addView(note(LocaleController.getString(R.string.UMessageUpdateKeepsGoing)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 0));
        LinearLayout buttons = buttonBar();
        buttons.setBackground(null);
        buttons.addView(secondary(LocaleController.getString(R.string.Cancel), onCancel), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        panel.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        speedAt = 0;
        speed = 0;
        setPanel(panel);
    }

    public void setProgress(long done, long total) {
        if (ring == null) return;
        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        ring.setProgress(percent);
        long now = System.currentTimeMillis();
        if (speedAt == 0) {
            speedAt = now;
            speedBytes = done;
        } else if (now - speedAt >= 1000) {
            float current = (done - speedBytes) * 1000f / (now - speedAt);
            speed = speed == 0 ? current : speed * 0.6f + current * 0.4f;
            speedAt = now;
            speedBytes = done;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(AndroidUtilities.formatFileSize(done));
        if (total > 0) sb.append(" / ").append(AndroidUtilities.formatFileSize(total));
        if (speed > 0) {
            sb.append(" · ").append(AndroidUtilities.formatFileSize((long) speed)).append("/s");
            if (total > done) {
                long left = (long) ((total - done) / speed);
                sb.append(" · ").append(LocaleController.formatString(R.string.UMessageUpdateLeft, LocaleController.formatDuration((int) Math.max(1, left))));
            }
        }
        progressInfo.setText(sb);
    }

    /** Downloaded and verified: what was checked, then Install. */
    public void showReady(Runnable onInstall) {
        LinearLayout panel = panel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(badge(0xff34c759, true), LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 22, 0, 0));
        TextView title = text(21, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.UMessageUpdateReadyTitle));
        panel.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 18, 22, 0));
        TextView text = text(14, TEXT_2, false);
        text.setGravity(Gravity.CENTER);
        text.setText(LocaleController.getString(R.string.UMessageUpdateReadyText));
        panel.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 0));
        LinearLayout checks = new LinearLayout(activity);
        checks.setOrientation(LinearLayout.VERTICAL);
        checks.setPadding(dp(14), dp(12), dp(14), dp(12));
        checks.setBackground(rounded(0x14ffffff, 0x24ffffff, dp(18)));
        int[] lines = {R.string.UMessageUpdateCheckSign, R.string.UMessageUpdateCheckIntact, R.string.UMessageUpdateCheckData};
        for (int i = 0; i < lines.length; i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(new CheckMark(activity, 0xff34c759), LayoutHelper.createLinear(16, 16, 0, 0, 8, 0));
            TextView line = text(13, TEXT, false);
            line.setText(LocaleController.getString(lines[i]));
            row.addView(line);
            checks.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 8, 0, 0));
        }
        panel.addView(checks, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 0));
        LinearLayout buttons = buttonBar();
        buttons.setBackground(null);
        buttons.addView(primary(LocaleController.getString(R.string.UMessageUpdateInstall), onInstall), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));
        panel.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        setPanel(panel);
    }

    /** Something went wrong: the reason in plain words, Try again / Later. */
    public void showError(String message, Runnable onRetry, Runnable onLater) {
        LinearLayout panel = panel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(badge(0xffff9f0a, false), LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 22, 0, 0));
        TextView title = text(21, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.UMessageUpdateFailedTitle));
        panel.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 18, 22, 0));
        TextView text = text(14, TEXT_2, false);
        text.setGravity(Gravity.CENTER);
        text.setText(message);
        panel.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 0));
        LinearLayout buttons = buttonBar();
        buttons.setBackground(null);
        if (onRetry != null) {
            buttons.addView(primary(LocaleController.getString(R.string.TryAgain), onRetry), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));
        }
        if (onLater != null) {
            buttons.addView(secondary(LocaleController.getString(R.string.UMessageUpdateLater), () -> {
                onLater.run();
                dismiss();
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, onRetry != null ? 10 : 0, 0, 0));
        }
        panel.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        setPanel(panel);
    }

    // ---------------------------------------------------------------- building blocks

    private void setPanel(View panel) {
        boolean first = content.getChildCount() == 0;
        content.removeAllViews();
        content.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (!first) {
            panel.setAlpha(0f);
            panel.animate().alpha(1f).setDuration(220).start();
        }
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        View grabber = new View(activity);
        grabber.setBackground(rounded(0x66ffffff, 0, dp(3)));
        panel.addView(grabber, LayoutHelper.createLinear(40, 5, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));
        return panel;
    }

    private LinearLayout buttonBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(18), dp(14), dp(18), dp(22));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x05ffffff, 0x0fffffff});
        bar.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{bg, topLine()}));
        return bar;
    }

    private android.graphics.drawable.Drawable topLine() {
        GradientDrawable line = new GradientDrawable();
        line.setColor(0x1fffffff);
        LayerDrawable d = new LayerDrawable(new android.graphics.drawable.Drawable[]{line});
        d.setLayerHeight(0, Math.max(1, dp(0.5f)));
        d.setLayerGravity(0, Gravity.TOP);
        return d;
    }

    /** Translucent body + light rim + top sheen: the glass itself (more opaque without real blur). */
    private android.graphics.drawable.Drawable glass() {
        GradientDrawable base = new GradientDrawable();
        base.setColor(blur ? 0xc8141a2a : 0xf0161b28);
        base.setCornerRadius(dp(38));
        GradientDrawable tint = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2bffffff, 0x12ffffff});
        tint.setCornerRadius(dp(38));
        tint.setStroke(Math.max(1, dp(1)), 0x3dffffff);
        GradientDrawable sheen = new GradientDrawable();
        sheen.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        sheen.setGradientCenter(0.2f, 0f);
        sheen.setGradientRadius(dp(220));
        sheen.setColors(new int[]{0x26ffffff, 0x00ffffff});
        sheen.setCornerRadius(dp(38));
        return new LayerDrawable(new android.graphics.drawable.Drawable[]{base, tint, sheen});
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(AndroidUtilities.bold());
        return t;
    }

    private TextView primary(String label, Runnable action) {
        TextView b = text(17, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setText(label);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff4aa3ff, ACCENT});
        bg.setCornerRadius(dp(27));
        bg.setStroke(Math.max(1, dp(1)), 0x59ffffff);
        b.setBackground(bg);
        b.setElevation(dp(4));
        b.setOnClickListener(v -> action.run());
        pressScale(b);
        return b;
    }

    private TextView secondary(String label, Runnable action) {
        TextView b = text(16, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setText(label);
        b.setBackground(rounded(0x1affffff, 0x33ffffff, dp(25)));
        b.setOnClickListener(v -> action.run());
        pressScale(b);
        return b;
    }

    private View note(String text) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(0x14ffffff, 0x24ffffff, dp(18)));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.msg_secret);
        icon.setColorFilter(0xff9fd0ff);
        row.addView(icon, LayoutHelper.createLinear(20, 20, 0, 0, 10, 0));
        TextView t = text(13, TEXT_2, false);
        t.setText(text);
        row.addView(t, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    private View appIcon() {
        ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            icon.setImageDrawable(activity.getPackageManager().getApplicationIcon(activity.getApplicationInfo()));
        } catch (Throwable e) {
            icon.setImageResource(R.mipmap.ic_launcher);
        }
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        icon.setClipToOutline(true);
        icon.setElevation(dp(6));
        return icon;
    }

    private View badge(int color, boolean ok) {
        return new View(activity) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            @Override
            protected void onDraw(Canvas canvas) {
                float r = getWidth() / 2f;
                p.setStyle(Paint.Style.FILL);
                p.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight(), color, darker(color), android.graphics.Shader.TileMode.CLAMP));
                p.setAlpha(235);
                canvas.drawCircle(r, r, r - dp(1), p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(0x66ffffff);
                canvas.drawCircle(r, r, r - dp(1), p);
                p.setColor(Color.WHITE);
                p.setStrokeWidth(dp(4));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                if (ok) {
                    path.moveTo(r - dp(16), r + dp(1));
                    path.lineTo(r - dp(5), r + dp(12));
                    path.lineTo(r + dp(17), r - dp(11));
                    canvas.drawPath(path, p);
                } else {
                    canvas.drawLine(r, r - dp(18), r, r + dp(4), p);
                    p.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(r, r + dp(15), dp(3), p);
                }
            }
        };
    }

    private static int darker(int color) {
        return Color.rgb((int) (Color.red(color) * 0.8f), (int) (Color.green(color) * 0.8f), (int) (Color.blue(color) * 0.8f));
    }

    private static void pressScale(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            }
            return false;
        });
    }

    /** Lines ending with ":" or starting with "#" become headings; "-" / "•" lines become bullets. */
    private static CharSequence formatNotes(String notes) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (notes == null) return sb;
        String[] lines = notes.replace("\r", "").split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (sb.length() > 0) sb.append('\n');
            if (line.isEmpty()) continue;
            boolean heading = line.startsWith("#") || line.endsWith(":") && line.length() < 60;
            if (heading) {
                line = line.replaceFirst("^#+\\s*", "");
                int start = sb.length();
                sb.append(line);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")) {
                sb.append("•  ").append(line.substring(2).trim());
            } else {
                sb.append(line);
            }
        }
        return sb;
    }

    // ---------------------------------------------------------------- small views

    private static class ProgressRing extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint label = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float shown, target;

        ProgressRing(Context context) {
            super(context);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(dp(10));
            track.setColor(0x24ffffff);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(dp(10));
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(ACCENT);
            fill.setColor(0x1affffff);
            label.setColor(TEXT);
            label.setTypeface(AndroidUtilities.bold());
            label.setTextSize(dp(30));
            label.setTextAlign(Paint.Align.CENTER);
        }

        void setProgress(int percent) {
            target = percent;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            shown += (target - shown) * 0.2f; // ease toward the real value
            float w = getWidth(), h = getHeight(), pad = dp(6);
            rect.set(pad, pad, w - pad, h - pad);
            canvas.drawArc(rect, 0, 360, false, track);
            canvas.drawArc(rect, -90, 360 * shown / 100f, false, arc);
            canvas.drawCircle(w / 2, h / 2, w / 2 - dp(22), fill);
            canvas.drawText(Math.round(shown) + "%", w / 2, h / 2 + dp(11), label);
            if (Math.abs(target - shown) > 0.3f) invalidate();
        }
    }

    private static class CheckMark extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CheckMark(Context context, int color) {
            super(context);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getWidth() / 24f;
            path.reset();
            path.moveTo(5 * s, 12.5f * s);
            path.lineTo(9.5f * s, 17 * s);
            path.lineTo(19 * s, 7.5f * s);
            canvas.drawPath(path, p);
        }
    }
}
