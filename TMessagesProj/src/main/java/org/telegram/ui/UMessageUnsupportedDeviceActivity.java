package org.telegram.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.messenger.Utilities;

import java.util.Locale;

/**
 * Shown instead of the app when the admin blocked this phone model in the admin panel
 * ("Telefon modellari"). The verdict is cached, re-checked on every launch, and the screen
 * reopens the app by itself once the model is unblocked.
 */
public class UMessageUnsupportedDeviceActivity extends Activity {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("umessage_device", Context.MODE_PRIVATE);
    }

    /** "manufacturer model", lower case — the key the admin blocks by. */
    public static String deviceModel() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String full = model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US)) ? model : manufacturer + " " + model;
        return full.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }

    public static boolean isSupported() {
        return !prefs().getBoolean("blocked", false);
    }

    /** Stores the server's verdict; a newly blocked model closes the app right away. */
    public static void applyBlocked(boolean blocked) {
        if (blocked == !isSupported()) {
            return;
        }
        prefs().edit().putBoolean("blocked", blocked).apply();
        if (blocked) {
            AndroidUtilities.runOnUIThread(() -> {
                Intent intent = new Intent(ApplicationLoader.applicationContext, UMessageUnsupportedDeviceActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                ApplicationLoader.applicationContext.startActivity(intent);
            });
        }
    }

    /** Asks the server whether this model is blocked (on launch and while the blocked screen is shown). */
    public static void check(Runnable onUnblocked) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                boolean blocked = UMessagePremiumController.fetchDeviceBlocked(deviceModel());
                boolean wasBlocked = !isSupported();
                if (blocked) {
                    applyBlocked(true);
                } else if (wasBlocked) {
                    prefs().edit().putBoolean("blocked", false).apply();
                    if (onUnblocked != null) {
                        AndroidUtilities.runOnUIThread(onUnblocked);
                    }
                }
            } catch (Throwable ignore) {
                // offline: keep the cached verdict
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        check(() -> {
            startActivity(new Intent(this, LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
    }

    private int dp(float value) {
        return (int) Math.ceil(getResources().getDisplayMetrics().density * value);
    }

    /** The language chosen in the app (falls back to the device language). */
    private static String appLanguage() {
        try {
            org.telegram.messenger.LocaleController.LocaleInfo info = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.shortName != null && info.shortName.length() > 0) {
                String code = info.shortName.toLowerCase(Locale.ROOT);
                int sep = code.indexOf('_');
                if (sep < 0) sep = code.indexOf('-');
                return sep > 0 ? code.substring(0, sep) : code;
            }
        } catch (Throwable ignore) {
        }
        return Locale.getDefault().getLanguage();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int background = 0xFF08080C;
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);

        final String title, body, exit;
        switch (appLanguage()) {
            case "uz":
                title = "Bu telefon modeli bloklangan";
                body = "U message bu telefon modelida ishlamaydi.";
                exit = "Ilovadan chiqish";
                break;
            case "kaa":
                title = "Bul telefon modeli bloklanǵan";
                body = "U message bul telefon modelinde islemeydi.";
                exit = "Baǵdarlamadan shıǵıw";
                break;
            case "ru":
                title = "Эта модель телефона заблокирована";
                body = "U message не работает на этой модели телефона.";
                exit = "Закрыть приложение";
                break;
            default:
                title = "This phone model is blocked";
                body = "U message does not work on this phone model.";
                exit = "Close the app";
                break;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(background);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.umessage_seven_logo);
        logo.setColorFilter(Color.WHITE);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAdjustViewBounds(true);
        int logoWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f) - dp(64);
        root.addView(logo, new FrameLayout.LayoutParams(logoWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_UNBOUNDED_BOLD));
        titleView.setGravity(Gravity.CENTER);
        column.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView bodyView = new TextView(this);
        bodyView.setText(body);
        bodyView.setTextColor(0xFFB9BAC4);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bodyView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(12);
        column.addView(bodyView, bodyParams);

        TextView button = new TextView(this);
        button.setText(exit);
        button.setTextColor(background);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(Color.WHITE);
        buttonBackground.setCornerRadius(dp(16));
        button.setBackground(buttonBackground);
        button.setOnClickListener(v -> finishAndRemoveTask());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(40);
        column.addView(button, buttonParams);

        int columnWidth = Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(420));
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(columnWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        columnParams.bottomMargin = dp(96);
        root.addView(column, columnParams);

        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(root);
    }
}
