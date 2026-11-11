package org.telegram.ui.ActionBar;

import android.graphics.Color;
import android.util.SparseIntArray;

import androidx.core.graphics.ColorUtils;

/**
 * U message look: the Telegram blue accent is replaced by ink (black on light themes,
 * white/graphite on dark ones). Only blue hues are touched, so semantic colors
 * (red, green, avatar and name colors) stay as they are.
 */
public class UMessageMonochrome {

    private static final int INK_LIGHT = 0xFF111111;
    private static final int INK_DARK = 0xFFEDEDED;
    private static final int FILL_DARK = 0xFF3A3A3A;
    private static final int CHECK_DARK = 0xFF5C5C5C;

    private static final float[] hsl = new float[3];
    private static int[] rawDefaults;

    public static void apply(int[] colors, boolean dark) {
        if (rawDefaults == null) {
            rawDefaults = colors.clone();
        }
        for (int key = 0; key < colors.length; key++) {
            if (colors[key] != 0) {
                colors[key] = apply(key, colors[key], dark);
            }
        }
    }

    public static void apply(SparseIntArray colors, boolean dark) {
        for (int i = 0; i < colors.size(); i++) {
            colors.setValueAt(i, apply(colors.keyAt(i), colors.valueAt(i), dark));
        }
    }

    /**
     * Keys a theme does not define fall back to the defaults, which are monochromed for light
     * themes (near-black ink). On dark themes that makes text invisible, so the missing accent
     * keys get their dark-theme ink explicitly.
     */
    public static void fillMissing(SparseIntArray colors, boolean dark) {
        // without its own value the online dot falls back to the (ink) blue text color, white/black instead of blue
        if (colors.indexOfKey(Theme.key_chats_onlineCircle) < 0) {
            colors.put(Theme.key_chats_onlineCircle, dark ? 0xFF0A84FF : 0xFF007AFF);
        }
        if (!dark || rawDefaults == null) {
            return;
        }
        SparseIntArray fallbackKeys = Theme.getFallbackKeys();
        for (int key = 0; key < rawDefaults.length; key++) {
            int raw = rawDefaults[key];
            // keys with a fallback resolve to the theme's own color, leave them alone
            if (raw == 0 || colors.indexOfKey(key) >= 0 || fallbackKeys.indexOfKey(key) >= 0) {
                continue;
            }
            String name = ThemeColors.getStringName(key);
            if (name != null && isOnPrimaryButton(name)) {
                colors.put(key, apply(key, raw, true)); // the button itself turns white, so its text turns black
                continue;
            }
            // only the accent itself (became ink); greys keep their light-theme lightness either way
            if ((apply(key, raw, false) & 0xFFFFFF) == (INK_LIGHT & 0xFFFFFF)) {
                colors.put(key, apply(key, raw, true));
            }
        }
    }

    /**
     * Emoji statuses, premium stars and verified badges keep their original (blue) color. Links in messages
     * also stay blue (iOS look): white ink made them indistinguishable from plain text.
     */
    private static boolean keepsOwnColor(String name) {
        String n = name.toLowerCase();
        return n.contains("verified") || n.contains("premium") || n.contains("star")
                || n.startsWith("chat_messagelink") || n.contains("linkselectbackground")
                || n.contains("reactionbutton") // chosen reaction pill stays the theme's blue, not graphite
                || name.equals("chats_unreadCounter") // unmuted chats' unread badge stays blue like Telegram (muted stays grey)
                || n.contains("emoji") && !n.startsWith("chat_emoji"); // the emoji panel itself stays monochrome
    }

    /** Text colors whose key names look like fills (dialog/picker text buttons). */
    private static boolean isTextButton(String name) {
        return name.equals("dialogButton") || name.equals("picker_enabledButton")
                || name.toLowerCase().contains("radio"); // radio rings are drawn without anything on top
    }

    /**
     * On dark themes the accent becomes a graphite fill when a white icon/text is drawn on top of it
     * (loaders, badges, check boxes, floating buttons), and near-white when it is the foreground itself.
     */
    private static boolean isDarkFill(String name) {
        if (isTextButton(name)) {
            return false;
        }
        String n = name.toLowerCase();
        if (name.endsWith("Background") || name.contains("Loader") || n.contains("badge")
                || n.contains("checkbox") && !name.endsWith("Check")) {
            return true;
        }
        boolean foreground = name.contains("Text") || name.contains("Icon") || name.contains("Link")
                || name.contains("Title") || name.contains("Header");
        return !foreground && (name.contains("Background") || name.contains("Button") || name.contains("Counter") || name.contains("Bubble"));
    }

    /** Filled primary buttons and the floating action button: black on light themes, white on dark ones. */
    private static boolean isPrimaryButton(String name) {
        return name.equals("featuredStickers_addButton") || name.equals("featuredStickers_addButtonPressed")
                || name.equals("chats_actionBackground") || name.equals("chats_actionPressedBackground");
    }

    /** Text, icons and loaders drawn on top of a primary button: they flip to black on dark themes. */
    private static boolean isOnPrimaryButton(String name) {
        return name.equals("featuredStickers_buttonText") || name.equals("featuredStickers_buttonProgress")
                || name.equals("chats_actionIcon");
    }

    public static int apply(int key, int color, boolean dark) {
        String name = ThemeColors.getStringName(key);
        if (name == null || name.startsWith("avatar_") || name.contains("wallpaper") || keepsOwnColor(name)) {
            return color;
        }
        if (name.equals("chats_onlineCircle")) {
            return dark ? 0xFF0A84FF : 0xFF007AFF; // online dot is always iOS blue
        }
        if (dark && isOnPrimaryButton(name)) {
            return ColorUtils.setAlphaComponent(INK_LIGHT, Color.alpha(color));
        }
        ColorUtils.colorToHSL(color, hsl);
        float hue = hsl[0], saturation = hsl[1], lightness = hsl[2];
        if (hue < 180 || hue > 250 || saturation < 0.08f) {
            return color;
        }
        int alpha = Color.alpha(color);
        boolean tint = saturation < 0.35f || (dark ? lightness < 0.3f : lightness >= 0.8f);
        if (tint) {
            // backgrounds, selections and bluish greys: keep the lightness, drop the hue
            hsl[1] = 0;
            return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), alpha);
        }
        // the accent itself
        int ink;
        if (!dark) {
            ink = INK_LIGHT;
        } else {
            String n = name.toLowerCase();
            if (isPrimaryButton(name)) {
                ink = name.contains("Pressed") ? 0xFFCFCFCF : INK_DARK; // white button, black text (iOS look)
            } else if (isDarkFill(name) && (n.contains("checkbox") || n.contains("roundcheck"))) {
                ink = CHECK_DARK; // a check box must stand out from the dark list behind it
            } else {
                ink = isDarkFill(name) ? FILL_DARK : INK_DARK;
            }
        }
        return ColorUtils.setAlphaComponent(ink, alpha);
    }
}
