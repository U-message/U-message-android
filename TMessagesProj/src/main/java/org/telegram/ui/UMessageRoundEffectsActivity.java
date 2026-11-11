package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RoundEffectsCarousel;
import org.telegram.ui.Components.RoundEffectsPreviewView;
import org.telegram.ui.Components.RoundVideoEffects;
import org.telegram.ui.Components.SnapCameraKit;
import org.telegram.ui.Components.LiquidGlassSlider;


/**
 * U message: color filters and Beauty (skin smoothing with a light blush) with one overall strength control and a
 * live round preview.
 */
public class UMessageRoundEffectsActivity extends BaseFragment {

    private static final int REQUEST_CAMERA = 3771;

    private FrameLayout previewContainer;
    private RoundEffectsPreviewView previewView;
    private TextView errorView;
    private RoundEffectsCarousel carousel;
    private TextView presetNameView;
    private TextView intensityValueView;
    private boolean permissionRequested;

    private int preset = RoundVideoEffects.getConfiguredPreset();
    private int intensity = RoundVideoEffects.getConfiguredIntensity();
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageRoundEffects));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        actionBar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setCastShadows(false);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(20), dp(16), dp(28));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // round live preview
        final int size = Math.min(Math.min(AndroidUtilities.displaySize.x - dp(96), dp(280)), (int) (AndroidUtilities.displaySize.y * 0.3f));
        previewContainer = new FrameLayout(context);
        previewContainer.setBackgroundColor(0xff000000);
        previewContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        previewContainer.setClipToOutline(true);
        errorView = new TextView(context);
        errorView.setText(LocaleController.getString(R.string.UMessageCameraUnavailable));
        errorView.setTextColor(0xffffffff);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        previewContainer.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 24, 0, 24, 0));
        // Inset, theme-aware groups match the app while keeping the camera preview circular.
        LinearLayout stage = new LinearLayout(context);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setBackground(roundedBackground(24, getThemedColor(Theme.key_windowBackgroundWhite)));
        stage.setPadding(0, dp(24), 0, dp(12));
        FrameLayout previewFrame = new FrameLayout(context);
        GradientDrawable previewBorder = roundedBackground(size, getThemedColor(Theme.key_windowBackgroundWhite));
        previewBorder.setStroke(dp(2), ColorUtils.setAlphaComponent(getThemedColor(Theme.key_featuredStickers_addButton), 0x50));
        previewFrame.setBackground(previewBorder);
        previewFrame.addView(previewContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 6, 6, 6, 6));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(size + dp(12), size + dp(12));
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        stage.addView(previewFrame, previewParams);
        presetNameView = new TextView(context);
        presetNameView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        presetNameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        presetNameView.setTypeface(AndroidUtilities.bold());
        presetNameView.setGravity(Gravity.CENTER);
        stage.addView(presetNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 4));
        carousel = new RoundEffectsCarousel(context, selectedPreset -> {
            preset = selectedPreset;
            RoundVideoEffects.saveConfiguredLook(preset, intensity);
            updateSelection();
        });
        carousel.setSelectionColor(getThemedColor(Theme.key_featuredStickers_addButton));
        carousel.setSelected(preset);
        stage.addView(carousel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 76));
        content.addView(stage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // pickers
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(roundedBackground(20, getThemedColor(Theme.key_windowBackgroundWhite)));
        section.setPadding(0, 0, 0, dp(12));

        intensityValueView = addSlider(context, section, R.string.UMessageRoundEffectIntensity, intensity, (value, stop) -> {
            intensity = value;
            if (stop) {
                RoundVideoEffects.saveConfiguredLook(preset, intensity);
            }
            updateSelection();
        });
        content.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.UMessageRoundEffectsInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        info.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        info.setLineSpacing(dp(3), 1f);
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 0));

        updateSelection();
        fragmentView = scrollView;
        return fragmentView;
    }

    // Keep the carousel thumbnails live while the user compares looks.
    private final Runnable captureThumbRunnable = new Runnable() {
        @Override
        public void run() {
            if (previewView == null) {
                return;
            }
            if (previewView.isAvailable()) {
                try {
                    android.graphics.Bitmap bitmap = previewView.getBitmap(dp(56), dp(56));
                    if (bitmap != null && bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2) != 0) {
                        carousel.setSource(bitmap);
                    }
                } catch (Exception ignore) {}
            }
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    private interface SliderListener {
        void onChanged(int percent, boolean stop);
    }

    /** Header with the current percent on the right and a 0..100 slider below; returns the percent view. */
    private TextView addSlider(Context context, LinearLayout parent, int titleRes, int value, SliderListener listener) {
        FrameLayout headerLayout = new FrameLayout(context);
        TextView header = new TextView(context);
        header.setText(LocaleController.getString(titleRes));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        header.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        headerLayout.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP, LocaleController.isRTL ? 88 : 20, 0, LocaleController.isRTL ? 20 : 88, 0));
        TextView valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setGravity(Gravity.CENTER);
        valueView.setBackground(roundedBackground(10, ColorUtils.setAlphaComponent(getThemedColor(Theme.key_featuredStickers_addButton), 0x16)));
        valueView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        valueView.setTypeface(AndroidUtilities.bold());
        headerLayout.addView(valueView, LayoutHelper.createFrame(64, 32, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 16, 0, 16, 0));
        parent.addView(headerLayout);

        LiquidGlassSlider seekBar = new LiquidGlassSlider(context);
        seekBar.setColors(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader),
                ColorUtils.setAlphaComponent(getThemedColor(Theme.key_windowBackgroundWhiteGrayText), 0x40));
        seekBar.setProgress(value / 100f);
        seekBar.setContentDescription(LocaleController.getString(titleRes) + ", " + value + "%");
        seekBar.setDelegate((progress, stop) -> {
            int percent = Math.round(progress * 100);
            listener.onChanged(percent, stop);
            seekBar.setContentDescription(LocaleController.getString(titleRes) + ", " + percent + "%");
        });
        parent.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 8, 2, 8, 2));
        FrameLayout endpoints = new FrameLayout(context);
        for (int i = 0; i < 2; i++) {
            TextView label = new TextView(context);
            label.setText(i == 0 ? "0%" : "100%");
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            endpoints.addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    (i == 0 ^ LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 30, 0, 30, 0));
        }
        parent.addView(endpoints, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24));
        return valueView;
    }

    private GradientDrawable roundedBackground(int radius, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private String formatPercent(int percent) {
        return percent + "%";
    }

    private void updateSelection() {
        intensityValueView.setText(formatPercent(intensity));
        presetNameView.setText(RoundEffectsCarousel.getName(preset));
        if (previewView != null) {
            final RoundVideoEffects.Look look = RoundVideoEffects.createLook(preset, intensity);
            previewView.setEffects(look.filter, look.filterIntensity, look.beauty, look.foundation,
                    look.blush, look.eyes, look.eyeTone, look.lipstick);
            previewView.setLens(look.lens, look.intensity, look.covered);
        }
    }

    private void attachPreview() {
        final Activity activity = getParentActivity();
        if (previewView != null || previewContainer == null || activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true;
                activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            } else {
                errorView.setVisibility(View.VISIBLE);
            }
            return;
        }
        errorView.setVisibility(View.GONE);
        SnapCameraKit.refresh();
        previewView = new RoundEffectsPreviewView(activity);
        final RoundVideoEffects.Look look = RoundVideoEffects.createLook(preset, intensity);
        previewView.setEffects(look.filter, look.filterIntensity, look.beauty, look.foundation,
                look.blush, look.eyes, look.eyeTone, look.lipstick);
        previewView.setLens(look.lens, look.intensity, look.covered);
        previewView.setOnErrorListener(() -> {
            detachPreview();
            errorView.setVisibility(View.VISIBLE);
        });
        previewContainer.addView(previewView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        AndroidUtilities.cancelRunOnUIThread(captureThumbRunnable);
        AndroidUtilities.runOnUIThread(captureThumbRunnable, 800);
    }

    // removing the view destroys its surface, which closes the camera
    private void detachPreview() {
        AndroidUtilities.cancelRunOnUIThread(captureThumbRunnable);
        if (previewView != null) {
            previewContainer.removeView(previewView);
            previewView = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        attachPreview();
    }

    @Override
    public void onPause() {
        super.onPause();
        detachPreview();
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            attachPreview();
        } else if (errorView != null) {
            errorView.setVisibility(View.VISIBLE);
        }
    }
}
