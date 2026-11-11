package org.telegram.ui.Stories.recorder;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageMusic;
import org.telegram.messenger.camera.CameraView;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.DarkThemeResourceProvider;

import java.io.File;
import java.util.ArrayList;

/**
 * U message: TikTok-like tools over the story camera — a sound to record with (it plays from its
 * trending part while recording and is put on the video), live camera effects and a teleprompter
 * that scrolls while recording.
 */
public class UMessageStoryTools extends FrameLayout {

    /** fxType values of camera_frag, in the order of the effects tray. */
    private static final int[] EFFECTS = {0, 9, 17, 16, 25, 18, 28, 20, 21, 19, 26, 5, 7, 13, 10, 11, 29, 22, 23, 24, 27, 8, 6, 12, 14, 15, 2, 4, 3};
    private static final String[] EFFECT_EMOJI = {
            "🚫", "✨", "🌈", "🖤", "🎞", "⚡", "📼", "💡", "💥", "🐟", "🔮", "🪞", "👾", "💓", "🍭", "🔥",
            "👯", "🌀", "🤏", "🌊", "🔲", "🔳", "🕶", "🦄", "✏️", "🦒", "🫨", "🩻", "🪐", "🏞"
    };
    private static final int[][] EFFECT_COLORS = {
            {0xff3a3a3c, 0xff2c2c2e}, {0xffff9ec7, 0xffff5f8f}, {0xff00d2ff, 0xff3a7bd5}, {0xff606060, 0xff151515},
            {0xffd6a35e, 0xff8a5a2b}, {0xff00f0ff, 0xffff00c8}, {0xff9d50bb, 0xff6e48aa}, {0xff12c2e9, 0xfff64f59},
            {0xfff7971e, 0xffffd200}, {0xff56ab2f, 0xffa8e063}, {0xff8e2de2, 0xff4a00e0}, {0xffbdc3c7, 0xff2c3e50},
            {0xff11998e, 0xff38ef7d}, {0xffff416c, 0xffff4b2b}, {0xffee9ca7, 0xff7f53ac}, {0xffff512f, 0xffdd2476},
            {0xfffc5c7d, 0xff6a82fb}, {0xff00c6ff, 0xff0072ff}, {0xfff857a6, 0xffff5858}, {0xff2193b0, 0xff6dd5ed},
            {0xfff7971e, 0xffffd200}, {0xff833ab4, 0xfffd1d1d}, {0xffe52d27, 0xff00c3ff}, {0xffff6a00, 0xffee0979},
            {0xffbdc3c7, 0xff8e9eab}, {0xfff09819, 0xffedde5d}, {0xffcb356b, 0xffbd3f32}, {0xff0f2027, 0xff2c5364},
            {0xff1d976c, 0xff93f9b9}, {0xff4facfe, 0xff00f2fe}
    };
    private static final int[] EFFECT_NAMES = {
            R.string.UMessageMakeupOriginal, R.string.UMessageRoundBeauty, R.string.UMessageFxVivid, R.string.UMessageFxNoir,
            R.string.UMessageFxVintage, R.string.UMessageFxGlitch, R.string.UMessageFxVhs, R.string.UMessageFxNeon,
            R.string.UMessageFxComic, R.string.UMessageFxFisheye, R.string.UMessageFxKaleido, R.string.UMessageFxMirror,
            R.string.UMessageFxPixel, R.string.UMessageFxPulse, R.string.UMessageFxDuotone, R.string.UMessageFxThermal,
            R.string.UMessageFxTwin, R.string.UMessageFxSwirl, R.string.UMessageFxPinch, R.string.UMessageFxWave,
            R.string.UMessageFxGrid4, R.string.UMessageFxGrid9, R.string.UMessageFx3d, R.string.UMessageFxRainbow,
            R.string.UMessageFxSketch, R.string.UMessageFxStretch, R.string.UMessageFxShake, R.string.UMessageFxXray,
            R.string.UMessageFxPlanet, R.string.UMessageFxReflection
    };

    private static final String PREFS = "umessage_story_tools";

    private final Runnable onOpenSheet;
    private final LinearLayout musicPill;
    private final TextView musicText;
    private final ImageView musicRemove;
    private final LinearLayout sideColumn;
    private final View effectsButton;
    private final HorizontalScrollView effectsTray;
    private final ArrayList<EffectItem> effectItems = new ArrayList<>();
    private final LinearLayout effectsRow;
    private final TextView effectToast;
    private final FrameLayout prompter;
    private final ScrollView prompterScroll;
    private final TextView prompterText;
    private final TextView speedText;

    private CameraView cameraView;
    private int effect;
    /** Retouch (skin smoothing) level: 0, 50 or 100. */
    private int retouch;
    /** Story video format {height, fps, native}, as the camera app's "HD·30 / 4K·60" switch. */
    private final ArrayList<int[]> formats = new ArrayList<>();
    private int formatHeight, formatFps;
    private TextView formatText;
    private Runnable onFormatChanged;
    /** The capsule of formats that opens to the left of the format circle. */
    private LinearLayout formatPicker;
    private boolean formatPickerOpen;
    private TextView retouchLabel;
    private final RetouchSlider retouchSlider;
    /** Face lens id (Snap Camera Kit), null for none; a lens and a shader effect never run together. */
    private String lens;
    private boolean shown = true, recording;

    /** Countdown before the shot starts, seconds (0 = off), as the TikTok timer. */
    private static final int[] TIMERS = {0, 3, 5, 10};
    private int timer;
    private TextView timerLabel;
    private ImageView timerIcon;
    private final TextView countdownText;
    private Runnable countdownDone;
    private int countdownLeft;
    private final Runnable countdownRunnable = this::countdownTick;

    private UMessageMusic.Track track;
    private File trackFile;
    /*
     * ExoPlayer (PCM through the mixer), not MediaPlayer: MediaPlayer sends AAC to the DSP offload path, whose
     * volume this phone lowers as soon as the camera opens the microphone, so the sound got quieter while recording.
     */
    private org.telegram.ui.Components.VideoPlayer player;

    private float prompterSpeed;
    private float scrollRemainder;
    private boolean scrolling;
    private long lastScrollFrameNanos;
    private final Choreographer.FrameCallback scrollFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!scrolling) {
                return;
            }
            // speed 1..10 → about 25..160 dp per second, by real frame time (60/90/120 Hz screens, dropped frames)
            float dt = lastScrollFrameNanos == 0 ? 1 / 60f : (frameTimeNanos - lastScrollFrameNanos) / 1e9f;
            dt = Math.max(0f, Math.min(0.1f, dt));
            lastScrollFrameNanos = frameTimeNanos;
            scrollRemainder += dp(10 + 15 * prompterSpeed) * dt;
            final int step = (int) scrollRemainder;
            scrollRemainder -= step;
            prompterScroll.scrollBy(0, step);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public UMessageStoryTools(Context context, Runnable onOpenSheet) {
        super(context);
        this.onOpenSheet = onOpenSheet;
        final SharedPreferences prefs = prefs();
        prompterSpeed = prefs.getFloat("prompter_speed", 3);

        // sound pill, top center
        musicPill = new LinearLayout(context);
        musicPill.setOrientation(LinearLayout.HORIZONTAL);
        musicPill.setGravity(Gravity.CENTER_VERTICAL);
        musicPill.setPadding(dp(12), 0, dp(6), 0);
        musicPill.setBackground(Theme.createRoundRectDrawable(dp(18), 0x59000000));
        final ImageView note = new ImageView(context);
        note.setImageResource(R.drawable.umessage_story_music);
        note.setScaleType(ImageView.ScaleType.FIT_CENTER);
        musicPill.addView(note, LayoutHelper.createLinear(18, 18));
        musicText = new TextView(context);
        musicText.setTextColor(0xffffffff);
        musicText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        musicText.setTypeface(AndroidUtilities.bold());
        musicText.setSingleLine(true);
        musicText.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        musicText.setMarqueeRepeatLimit(-1);
        musicText.setSelected(true);
        musicText.setMaxWidth(dp(190));
        musicPill.addView(musicText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 6, 0));
        musicRemove = new ImageView(context);
        musicRemove.setImageResource(R.drawable.ic_close_white);
        musicRemove.setScaleType(ImageView.ScaleType.CENTER);
        musicRemove.setBackground(Theme.createSelectorDrawable(0x30ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        musicRemove.setContentDescription(LocaleController.getString(R.string.UMessageMusicRemove));
        musicRemove.setOnClickListener(v -> setTrack(null, null));
        musicPill.addView(musicRemove, LayoutHelper.createLinear(28, 28));
        musicPill.setOnClickListener(v -> {
            if (!recording) {
                openMusic();
            }
        });
        ScaleStateListAnimator.apply(musicPill, 0.04f, 1.4f);
        addView(musicPill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 64, 0, 0));

        // side column, right
        sideColumn = new LinearLayout(context);
        sideColumn.setOrientation(LinearLayout.VERTICAL);
        effectsButton = createSideButton(context, R.drawable.umessage_story_effects, LocaleController.getString(R.string.UMessageStoryEffects), v -> toggleEffects());
        formatHeight = prefs.getInt("format_height", 2160);
        formatFps = prefs.getInt("format_fps", 60);
        formatPicker = new LinearLayout(context);
        formatPicker.setOrientation(LinearLayout.HORIZONTAL);
        formatPicker.setGravity(Gravity.CENTER_VERTICAL);
        formatPicker.setPadding(dp(5), 0, dp(30), 0);
        formatPicker.setBackground(Theme.createRoundRectDrawable(dp(21), 0x8c000000));
        formatPicker.setVisibility(View.GONE);
        // under the circle (added before the side column), its right end tucked behind it
        addView(formatPicker, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 42, Gravity.TOP | Gravity.RIGHT, 0, 158, 4 + 32, 0));
        sideColumn.addView(createFormatButton(context), LayoutHelper.createLinear(64, 62, 0, 0, 0, 6));
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<int[]> available = org.telegram.messenger.camera.Camera2Session.availableFormats();
            AndroidUtilities.runOnUIThread(() -> {
                formats.clear();
                formats.addAll(available);
                updateFormat();
            });
        });
        sideColumn.addView(effectsButton, LayoutHelper.createLinear(64, 62));
        retouch = prefs.getInt("retouch", 50);
        final View retouchButton = createSideButton(context, R.drawable.umessage_story_retouch, "", v -> toggleRetouchSlider());
        retouchLabel = (TextView) ((LinearLayout) retouchButton).getChildAt(1);
        sideColumn.addView(retouchButton, LayoutHelper.createLinear(64, 62, 0, 6, 0, 0));
        retouchSlider = new RetouchSlider(context);
        retouchSlider.setVisibility(View.GONE);
        updateRetouch();
        sideColumn.addView(createSideButton(context, R.drawable.umessage_story_prompter, LocaleController.getString(R.string.UMessageTeleprompter), v -> togglePrompter()), LayoutHelper.createLinear(64, 62, 0, 6, 0, 0));
        timer = prefs.getInt("timer", 0);
        final View timerButton = createSideButton(context, R.drawable.umessage_story_timer, "", v -> cycleTimer());
        timerIcon = (ImageView) ((LinearLayout) timerButton).getChildAt(0);
        timerLabel = (TextView) ((LinearLayout) timerButton).getChildAt(1);
        sideColumn.addView(timerButton, LayoutHelper.createLinear(64, 62, 0, 6, 0, 0));
        updateTimer();
        addView(sideColumn, LayoutHelper.createFrame(64, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 158, 4, 0));

        // effects tray, bottom
        effectsTray = new HorizontalScrollView(context);
        effectsTray.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        effectsRow = row;
        effectsTray.addView(row);
        effectsTray.setVisibility(View.GONE);
        // added by StoryRecorder right above the record button: the record controls cover the bottom of the camera

        effectToast = new TextView(context);
        effectToast.setTextColor(0xffffffff);
        effectToast.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        effectToast.setTypeface(AndroidUtilities.bold());
        effectToast.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        effectToast.setAlpha(0);
        addView(effectToast, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        countdownText = new TextView(context);
        countdownText.setTextColor(0xffffffff);
        countdownText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 120);
        countdownText.setTypeface(AndroidUtilities.bold());
        countdownText.setShadowLayer(dp(12), 0, dp(2), 0x80000000);
        countdownText.setAlpha(0);
        addView(countdownText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        // teleprompter
        prompter = new FrameLayout(context);
        prompter.setBackground(Theme.createRoundRectDrawable(dp(16), 0x8c000000));
        prompterScroll = new ScrollView(context) {
            @Override
            public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                return !scrolling && super.onInterceptTouchEvent(ev);
            }
        };
        prompterScroll.setVerticalScrollBarEnabled(false);
        prompterScroll.setVerticalFadingEdgeEnabled(true);
        prompterScroll.setFadingEdgeLength(dp(28));
        prompterText = new TextView(context);
        prompterText.setTextColor(0xffffffff);
        prompterText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        prompterText.setTypeface(AndroidUtilities.bold());
        prompterText.setLineSpacing(dp(4), 1f);
        prompterText.setGravity(Gravity.CENTER_HORIZONTAL);
        prompterText.setPadding(dp(16), dp(40), dp(16), dp(160));
        prompterText.setOnClickListener(v -> {
            if (!recording) {
                editPrompter();
            }
        });
        prompterScroll.addView(prompterText);
        prompter.addView(prompterScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 0, 0, 40));
        final LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(createControl(context, "−", v -> setSpeed(prompterSpeed - 1)), LayoutHelper.createLinear(36, 32));
        speedText = new TextView(context);
        speedText.setTextColor(0xccffffff);
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        speedText.setGravity(Gravity.CENTER);
        controls.addView(speedText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 4, 0, 4, 0));
        controls.addView(createControl(context, "+", v -> setSpeed(prompterSpeed + 1)), LayoutHelper.createLinear(36, 32));
        controls.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
        controls.addView(createControl(context, LocaleController.getString(R.string.Edit), v -> editPrompter()), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        controls.addView(createControl(context, "✕", v -> togglePrompter()), LayoutHelper.createLinear(36, 32, 6, 0, 0, 0));
        prompter.addView(controls, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.BOTTOM, 8, 0, 8, 4));
        prompter.setVisibility(View.GONE);
        addView(prompter, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 230, Gravity.TOP, 16, 156, 76, 0));
        setSpeed(prompterSpeed);
        prompterText.setText(prefs.getString("prompter_text", ""));

        updateMusic();
        rebuildEffects();
        // warm the sound list so the sheet opens with it
        UMessageMusic.load(UMessageMusic.CATEGORY_TREND, (tracks, error) -> {});
    }

    /** Original, then the camera effects; face lenses (CameraView.setLens) are off until a face engine is chosen. */
    private void rebuildEffects() {
        effectsRow.removeAllViews();
        effectItems.clear();
        addEffectItem(new EffectItem(getContext(), 0, null, null, null));
        for (int i = 1; i < EFFECTS.length; i++) {
            addEffectItem(new EffectItem(getContext(), EFFECTS[i], null, null, null));
        }
        updateEffects();
    }

    private void addEffectItem(EffectItem item) {
        effectItems.add(item);
        effectsRow.addView(item, LayoutHelper.createLinear(70, 92));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        rebuildEffects();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private View createSideButton(Context context, int icon, String label, OnClickListener listener) {
        final LinearLayout button = new LinearLayout(context);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER_HORIZONTAL);
        final ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setBackground(Theme.createCircleDrawable(dp(42), 0x40000000));
        button.addView(image, LayoutHelper.createLinear(42, 42));
        final TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(0xffffffff);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        button.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        button.setOnClickListener(listener);
        ScaleStateListAnimator.apply(button, 0.08f, 1.4f);
        return button;
    }

    private View createFormatButton(Context context) {
        final LinearLayout button = new LinearLayout(context);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER_HORIZONTAL);
        formatText = new TextView(context);
        formatText.setTextColor(0xffffffff);
        formatText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        formatText.setTypeface(AndroidUtilities.bold());
        formatText.setGravity(Gravity.CENTER);
        formatText.setLineSpacing(0, 0.85f);
        formatText.setBackground(Theme.createCircleDrawable(dp(42), 0x40000000));
        button.addView(formatText, LayoutHelper.createLinear(42, 42));
        final TextView label = new TextView(context);
        label.setText(LocaleController.getString(R.string.UMessageVideoFormat));
        label.setTextColor(0xffffffff);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setSingleLine(true);
        label.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        button.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        button.setOnClickListener(v -> toggleFormatPicker(!formatPickerOpen));
        ScaleStateListAnimator.apply(button, 0.08f, 1.4f);
        return button;
    }

    public int getFormatHeight() {
        return formatHeight;
    }

    public int getFormatFps() {
        return formatFps;
    }

    /** Called when the format changes: the camera is opened again in it. */
    public void setOnFormatChanged(Runnable onFormatChanged) {
        this.onFormatChanged = onFormatChanged;
    }

    private int formatIndex() {
        for (int i = 0; i < formats.size(); i++) {
            if (formats.get(i)[0] == formatHeight && formats.get(i)[1] == formatFps) {
                return i;
            }
        }
        return -1;
    }

    private void updateFormat() {
        if (!formats.isEmpty() && formatIndex() < 0) {
            // the saved format isn't available here: the best one is
            final int[] best = formats.get(formats.size() - 1);
            formatHeight = best[0];
            formatFps = best[1];
        }
        formatText.setText((formatHeight >= 2160 ? "4K" : "HD") + "\n" + formatFps);
    }

    private void toggleFormatPicker(boolean open) {
        if (open && (recording || formats.isEmpty())) {
            return;
        }
        formatPickerOpen = open;
        if (open) {
            rebuildFormatPicker();
        }
        formatPicker.setVisibility(View.VISIBLE);
        formatPicker.animate().cancel();
        formatPicker.setPivotX(formatPicker.getWidth() > 0 ? formatPicker.getWidth() : dp(240));
        formatPicker.setPivotY(dp(21));
        if (open) {
            formatPicker.setAlpha(0f);
            formatPicker.setScaleX(0.4f);
        }
        formatPicker.animate().alpha(open ? 1f : 0f).scaleX(open ? 1f : 0.4f).setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> formatPicker.setVisibility(formatPickerOpen ? View.VISIBLE : View.GONE)).start();
        formatText.setBackground(Theme.createCircleDrawable(dp(42), open ? 0xccfe2c55 : 0x40000000));
    }

    private void rebuildFormatPicker() {
        formatPicker.removeAllViews();
        for (int[] format : formats) {
            final TextView option = new TextView(getContext());
            final boolean selected = format[0] == formatHeight && format[1] == formatFps;
            option.setText((format[0] >= 2160 ? "4K" : "HD") + " " + format[1]);
            option.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            option.setTypeface(AndroidUtilities.bold());
            option.setGravity(Gravity.CENTER);
            option.setPadding(dp(11), 0, dp(11), 0);
            option.setTextColor(selected ? 0xff000000 : 0xffffffff);
            option.setBackground(selected ? Theme.createRoundRectDrawable(dp(16), 0xffffffff) : null);
            option.setOnClickListener(v -> selectFormat(format[0], format[1]));
            ScaleStateListAnimator.apply(option, 0.08f, 1.4f);
            formatPicker.addView(option, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 2, 0));
        }
    }

    private void selectFormat(int height, int fps) {
        if (recording || height == formatHeight && fps == formatFps) {
            return;
        }
        formatHeight = height;
        formatFps = fps;
        prefs().edit().putInt("format_height", formatHeight).putInt("format_fps", formatFps).apply();
        updateFormat();
        rebuildFormatPicker();
        showToast((formatHeight >= 2160 ? "4K" : "1080p") + " · " + formatFps + " fps");
        if (onFormatChanged != null) {
            onFormatChanged.run();
        }
    }

    private TextView createControl(Context context, String text, OnClickListener listener) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(0xffffffff);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, text.length() > 1 ? 13 : 17);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), 0x26ffffff, 0x40ffffff));
        view.setOnClickListener(listener);
        return view;
    }

    /* ---- camera ---- */

    public void setCameraView(CameraView cameraView) {
        this.cameraView = cameraView;
        if (cameraView != null) {
            cameraView.setEffect(effect);
            cameraView.setLens(lens);
            cameraView.setRetouch(retouch / 100f);
        }
    }

    /* ---- retouch ---- */

    private void toggleRetouchSlider() {
        final boolean show = retouchSlider.getVisibility() != View.VISIBLE;
        if (show && effectsTray.getVisibility() == View.VISIBLE) {
            toggleEffects();
        }
        showPanel(retouchSlider, show);
    }

    private void showPanel(View panel, boolean show) {
        panel.setVisibility(View.VISIBLE);
        panel.animate().cancel();
        panel.setAlpha(show ? 0f : 1f);
        panel.setTranslationY(show ? dp(24) : 0);
        panel.animate().alpha(show ? 1f : 0f).translationY(show ? 0 : dp(24)).setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> panel.setVisibility(show ? View.VISIBLE : View.GONE)).start();
    }

    private void setRetouch(int value, boolean save) {
        retouch = Math.max(0, Math.min(100, value));
        if (cameraView != null) {
            cameraView.setRetouch(retouch / 100f);
        }
        if (save) {
            prefs().edit().putInt("retouch", retouch).apply();
        }
        updateRetouch();
    }

    private void updateRetouch() {
        retouchLabel.setText(retouch == 0 ? LocaleController.getString(R.string.UMessageRetouch) : retouch + "%");
        final View icon = ((LinearLayout) retouchLabel.getParent()).getChildAt(0);
        icon.setBackground(Theme.createCircleDrawable(dp(42), retouch > 0 ? 0xccfe2c55 : 0x40000000));
        retouchSlider.invalidate();
    }

    /** TikTok-like slider: "Retouch" on the left, a track with the value in a bubble over the thumb. */
    private class RetouchSlider extends View {

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint label = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint value = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private boolean dragging;

        RetouchSlider(Context context) {
            super(context);
            track.setColor(0x4dffffff);
            fill.setColor(0xfffe2c55);
            thumb.setColor(0xffffffff);
            thumb.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
            label.setColor(0xffffffff);
            label.setTextSize(dp(14));
            label.setTypeface(AndroidUtilities.bold());
            label.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
            value.setColor(0xffffffff);
            value.setTextSize(dp(13));
            value.setTypeface(AndroidUtilities.bold());
            value.setTextAlign(Paint.Align.CENTER);
            value.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        private float trackLeft() {
            return dp(20) + label.measureText(LocaleController.getString(R.string.UMessageRetouch)) + dp(16);
        }

        private float trackRight() {
            return getWidth() - dp(28);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cy = getHeight() * 0.62f;
            canvas.drawText(LocaleController.getString(R.string.UMessageRetouch), dp(20), cy - (label.descent() + label.ascent()) / 2, label);
            final float l = trackLeft(), r = trackRight();
            final float x = l + (r - l) * retouch / 100f;
            rect.set(l, cy - dp(2), r, cy + dp(2));
            canvas.drawRoundRect(rect, dp(2), dp(2), track);
            rect.set(l, cy - dp(2), x, cy + dp(2));
            canvas.drawRoundRect(rect, dp(2), dp(2), fill);
            canvas.drawCircle(x, cy, dragging ? dp(11) : dp(9), thumb);
            canvas.drawText(String.valueOf(retouch), x, cy - dp(20), value);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            final float l = trackLeft(), r = trackRight();
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (event.getX() < l - dp(16)) {
                        return false;
                    }
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                case android.view.MotionEvent.ACTION_MOVE:
                    setRetouch(Math.round((event.getX() - l) / (r - l) * 100), false);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    setRetouch(retouch, true);
                    return true;
            }
            return true;
        }
    }

    /** The effects tray, for the recorder to place above its record button. */
    public View getEffectsTray() {
        return effectsTray;
    }

    /** The retouch slider, placed by the recorder where the effects tray goes. */
    public View getRetouchSlider() {
        return retouchSlider;
    }

    /** Hidden on pages other than the camera and in live mode. */
    public void setShown(boolean shown) {
        if (this.shown == shown) {
            return;
        }
        this.shown = shown;
        if (!shown) {
            cancelCountdown();
            stopRecording();
            effectsTray.animate().cancel();
            effectsTray.setVisibility(View.GONE);
            retouchSlider.setVisibility(View.GONE);
            formatPickerOpen = false;
            formatPicker.setVisibility(View.GONE);
            formatText.setBackground(Theme.createCircleDrawable(dp(42), 0x40000000));
        }
        setVisibility(View.VISIBLE);
        animate().cancel();
        animate().alpha(shown ? 1f : 0f).setDuration(200).withEndAction(() -> setVisibility(this.shown ? View.VISIBLE : View.GONE)).start();
    }

    public void onRecordStart() {
        if (formatPickerOpen) {
            toggleFormatPicker(false);
        }
        recording = true;
        updateRecordingState();
        if (trackFile != null) {
            try {
                releasePlayer();
                player = new org.telegram.ui.Components.VideoPlayer(false, false);
                // a delegate is required: without it the first state change threw and the sound never started
                player.setDelegate(new org.telegram.ui.Components.VideoPlayer.VideoPlayerDelegate() {
                    @Override
                    public void onStateChanged(boolean playWhenReady, int playbackState) {}

                    @Override
                    public void onError(org.telegram.ui.Components.VideoPlayer player, Exception e) {
                        FileLog.e(e);
                    }

                    @Override
                    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {}

                    @Override
                    public void onRenderedFirstFrame() {}
                });
                player.preparePlayer(android.net.Uri.fromFile(trackFile), "other");
                player.setVolume(1f);
                player.play();
            } catch (Exception e) {
                FileLog.e(e);
                releasePlayer();
            }
        }
        if (prompter.getVisibility() == View.VISIBLE && prompterText.length() > 0) {
            prompterScroll.scrollTo(0, 0);
            scrolling = true;
            scrollRemainder = 0;
            lastScrollFrameNanos = 0;
            Choreographer.getInstance().removeFrameCallback(scrollFrame);
            Choreographer.getInstance().postFrameCallback(scrollFrame);
        }
    }

    public void stopRecording() {
        recording = false;
        scrolling = false;
        releasePlayer();
        updateRecordingState();
    }

    /** A recording in parts stopped between two parts: the sound and the teleprompter wait for the next one. */
    public void onRecordPause() {
        if (player != null) {
            player.pause();
        }
        scrolling = false;
    }

    /** The last part was deleted: the sound goes back to where the kept parts end. */
    public void onRecordDeletePart(long keptDuration) {
        if (player != null) {
            player.seekTo(keptDuration);
        }
    }

    public void onRecordResume() {
        if (player != null) {
            player.play();
        }
        if (prompter.getVisibility() == View.VISIBLE && prompterText.length() > 0 && !scrolling) {
            scrolling = true;
            lastScrollFrameNanos = 0;
            Choreographer.getInstance().removeFrameCallback(scrollFrame);
            Choreographer.getInstance().postFrameCallback(scrollFrame);
        }
    }

    /* ---- countdown timer ---- */

    private void cycleTimer() {
        int i = 0;
        while (i < TIMERS.length && TIMERS[i] != timer) {
            i++;
        }
        timer = TIMERS[(i + 1) % TIMERS.length];
        prefs().edit().putInt("timer", timer).apply();
        updateTimer();
        showToast(LocaleController.getString(R.string.UMessageStoryTimer) + ": " + (timer == 0 ? LocaleController.getString(R.string.UMessageStoryTimerOff) : LocaleController.formatString(R.string.UMessageStoryTimerSec, timer)));
    }

    private void updateTimer() {
        timerLabel.setText(timer == 0 ? LocaleController.getString(R.string.UMessageStoryTimer) : LocaleController.formatString(R.string.UMessageStoryTimerSec, timer));
        timerIcon.setBackground(Theme.createCircleDrawable(dp(42), timer == 0 ? 0x40000000 : 0xccfe2c55));
    }

    /** Counts the timer down over the camera and then runs onDone; false when the timer is off. */
    public boolean startCountdown(Runnable onDone) {
        if (timer <= 0 || !shown) {
            return false;
        }
        cancelCountdown();
        countdownDone = onDone;
        countdownLeft = timer;
        sideColumn.animate().alpha(0f).setDuration(200).start();
        countdownTick();
        return true;
    }

    public boolean isCountingDown() {
        return countdownDone != null;
    }

    public void cancelCountdown() {
        if (countdownDone == null) {
            return;
        }
        countdownDone = null;
        AndroidUtilities.cancelRunOnUIThread(countdownRunnable);
        countdownText.animate().cancel();
        countdownText.setAlpha(0f);
        if (!recording) {
            sideColumn.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void countdownTick() {
        if (countdownDone == null) {
            return;
        }
        if (countdownLeft <= 0) {
            final Runnable done = countdownDone;
            countdownDone = null;
            countdownText.animate().cancel();
            countdownText.setAlpha(0f);
            done.run();
            return;
        }
        countdownText.setText(String.valueOf(countdownLeft));
        countdownText.animate().cancel();
        countdownText.setAlpha(0f);
        countdownText.setScaleX(1.6f);
        countdownText.setScaleY(1.6f);
        countdownText.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(250).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() ->
                countdownText.animate().alpha(0f).setStartDelay(450).setDuration(250).start()).start();
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {}
        countdownLeft--;
        AndroidUtilities.runOnUIThread(countdownRunnable, 1000);
    }

    /** Puts the chosen sound on a shot video: from its start, the camera sound muted like TikTok does. */
    public void applyTo(StoryEntry entry, long videoDuration) {
        if (entry == null || trackFile == null || track == null) {
            return;
        }
        entry.audioPath = trackFile.getAbsolutePath();
        entry.audioDocument = null;
        entry.audioTitle = track.title;
        entry.audioAuthor = track.artist;
        entry.audioDuration = UMessageMusic.readDuration(trackFile);
        entry.audioOffset = 0;
        entry.audioLeft = 0;
        entry.audioRight = entry.audioDuration <= 0 || !entry.isVideo ? 1 : Math.min(1f, videoDuration / (float) entry.audioDuration);
        entry.audioVolume = 1f;
        if (entry.isVideo) {
            entry.videoVolume = 0f;
        }
    }

    public boolean hasTrack() {
        return trackFile != null;
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.releasePlayer(true);
            } catch (Exception ignore) {
            }
            player = null;
        }
    }

    private void updateRecordingState() {
        sideColumn.animate().alpha(recording ? 0f : 1f).setDuration(200).start();
        sideColumn.setEnabled(!recording);
        effectsTray.animate().alpha(recording ? 0f : 1f).setDuration(200).start();
        retouchSlider.animate().alpha(recording ? 0f : 1f).setDuration(200).start();
        musicRemove.setVisibility(recording || trackFile == null ? View.GONE : View.VISIBLE);
        musicPill.animate().alpha(recording && trackFile == null ? 0f : 1f).setDuration(200).start();
        for (int i = 0; i < sideColumn.getChildCount(); i++) {
            sideColumn.getChildAt(i).setClickable(!recording);
        }
    }

    /* ---- music ---- */

    private void openMusic() {
        if (onOpenSheet != null) {
            onOpenSheet.run();
        }
        new UMessageMusicSheet(getContext(), null, this::setTrack).show();
    }

    private void setTrack(UMessageMusic.Track track, File file) {
        this.track = track;
        this.trackFile = file;
        updateMusic();
    }

    private void updateMusic() {
        if (track != null) {
            musicText.setText(track.title + " — " + track.artist);
        } else {
            musicText.setText(LocaleController.getString(R.string.UMessageMusicAdd));
        }
        musicRemove.setVisibility(track != null && !recording ? View.VISIBLE : View.GONE);
        musicPill.setPadding(dp(12), 0, track != null ? dp(4) : dp(12), 0);
    }

    /* ---- effects ---- */

    private void toggleEffects() {
        final boolean show = effectsTray.getVisibility() != View.VISIBLE;
        if (show && retouchSlider.getVisibility() == View.VISIBLE) {
            showPanel(retouchSlider, false);
        }
        effectsTray.setVisibility(View.VISIBLE);
        effectsTray.animate().cancel();
        effectsTray.setAlpha(show ? 0f : 1f);
        effectsTray.setTranslationY(show ? dp(24) : 0);
        effectsTray.animate().alpha(show ? 1f : 0f).translationY(show ? 0 : dp(24)).setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> effectsTray.setVisibility(show ? View.VISIBLE : View.GONE)).start();
    }

    private void selectEffect(int value, String lensId, String lensName) {
        effect = value;
        lens = lensId;
        if (cameraView != null) {
            cameraView.setEffect(value);
            cameraView.setLens(lensId);
        }
        updateEffects();
        showToast(lensId != null ? lensName : LocaleController.getString(EFFECT_NAMES[value]));
    }

    private void showToast(String text) {
        effectToast.setText(text);
        effectToast.animate().cancel();
        effectToast.setAlpha(0f);
        effectToast.setScaleX(0.8f);
        effectToast.setScaleY(0.8f);
        effectToast.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).withEndAction(() ->
                effectToast.animate().alpha(0f).setStartDelay(700).setDuration(300).start()).start();
    }

    private void updateEffects() {
        for (EffectItem item : effectItems) {
            item.setChecked(item.lens != null ? item.lens.equals(lens) : lens == null && item.effect == effect);
        }
    }

    private class EffectItem extends LinearLayout {

        final int effect;
        final String lens;
        private final EffectCircle circle;

        EffectItem(Context context, int effect, String lens, String lensName, Bitmap lensIcon) {
            super(context);
            this.effect = effect;
            this.lens = lens;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            circle = new EffectCircle(context, lens != null ? (lensIcon != null ? "" : "🎭") : EFFECT_EMOJI[effect],
                    lens != null ? new int[]{0xff434343, 0xff1c1c1e} : EFFECT_COLORS[effect], lensIcon);
            addView(circle, LayoutHelper.createLinear(62, 62, 0, 4, 0, 0));
            final TextView name = new TextView(context);
            name.setText(lens != null ? lensName : LocaleController.getString(EFFECT_NAMES[effect]));
            name.setTextColor(0xffffffff);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
            addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 2, 4, 2, 0));
            setOnClickListener(v -> {
                if (!recording) {
                    selectEffect(effect, lens, lensName);
                }
            });
            ScaleStateListAnimator.apply(this, 0.08f, 1.4f);
        }

        void setChecked(boolean checked) {
            circle.setChecked(checked);
        }
    }

    private static class EffectCircle extends View {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix iconMatrix = new Matrix();
        private final String emoji;
        private final int[] colors;
        private final Bitmap icon;
        private boolean checked;

        EffectCircle(Context context, String emoji, int[] colors, Bitmap icon) {
            super(context);
            this.emoji = emoji;
            this.colors = colors;
            this.icon = icon;
            if (icon != null) {
                iconPaint.setShader(new BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2.5f));
            ring.setColor(0xffffffff);
            text.setTextSize(dp(24));
            text.setTextAlign(Paint.Align.CENTER);
        }

        void setChecked(boolean checked) {
            this.checked = checked;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            fill.setShader(new LinearGradient(0, 0, w, h, colors[0], colors[1], Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float radius = Math.min(cx, cy) - dp(4);
            final float r = checked ? radius - dp(3) : radius;
            canvas.drawCircle(cx, cy, r, fill);
            if (icon != null) {
                final float scale = 2 * r / Math.min(icon.getWidth(), icon.getHeight());
                iconMatrix.setScale(scale, scale);
                iconMatrix.postTranslate(cx - icon.getWidth() * scale / 2, cy - icon.getHeight() * scale / 2);
                iconPaint.getShader().setLocalMatrix(iconMatrix);
                canvas.drawCircle(cx, cy, r, iconPaint);
            }
            if (checked) {
                canvas.drawCircle(cx, cy, radius + dp(1), ring);
            }
            canvas.drawText(emoji, cx, cy - (text.descent() + text.ascent()) / 2, text);
        }
    }

    /* ---- teleprompter ---- */

    private void togglePrompter() {
        if (prompter.getVisibility() == View.VISIBLE) {
            scrolling = false;
            prompter.animate().alpha(0f).setDuration(180).withEndAction(() -> prompter.setVisibility(View.GONE)).start();
            return;
        }
        if (prompterText.length() == 0) {
            editPrompter();
            return;
        }
        showPrompter();
    }

    private void showPrompter() {
        prompterScroll.scrollTo(0, 0);
        prompter.setVisibility(View.VISIBLE);
        prompter.setAlpha(0f);
        prompter.animate().alpha(1f).setDuration(180).start();
    }

    private void editPrompter() {
        final Context context = getContext();
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setText(prompterText.getText());
        field.setTextColor(0xffffffff);
        field.setHintTextColor(0x80ffffff);
        field.setHint(LocaleController.getString(R.string.UMessageTeleprompterHint));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(4);
        field.setMaxLines(10);
        field.setGravity(Gravity.TOP);
        field.setCursorColor(0xffffffff);
        field.setBackground(Theme.createRoundRectDrawable(dp(10), 0x1affffff));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        final FrameLayout container = new FrameLayout(context);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 22, 4, 22, 4));
        final AlertDialog dialog = new AlertDialog.Builder(context, new DarkThemeResourceProvider())
                .setTitle(LocaleController.getString(R.string.UMessageTeleprompter))
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                    final String value = field.getText().toString().trim();
                    prefs().edit().putString("prompter_text", value).apply();
                    prompterText.setText(value);
                    if (value.isEmpty()) {
                        prompter.setVisibility(View.GONE);
                    } else if (prompter.getVisibility() != View.VISIBLE) {
                        showPrompter();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.show();
        field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(field), 200);
    }

    private void setSpeed(float speed) {
        prompterSpeed = Math.max(1, Math.min(10, speed));
        prefs().edit().putFloat("prompter_speed", prompterSpeed).apply();
        speedText.setText(LocaleController.getString(R.string.UMessageTeleprompterSpeed) + " " + (int) prompterSpeed);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelCountdown();
        stopRecording();
        Choreographer.getInstance().removeFrameCallback(scrollFrame);
    }
}
