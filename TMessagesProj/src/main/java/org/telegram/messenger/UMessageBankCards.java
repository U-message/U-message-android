package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;

/**
 * U message: what a bank card number in a message is (payment system from its first digits, no network) and
 * which local payment apps can take it. The card holder's name is not available without a payment provider API.
 */
public class UMessageBankCards {

    /** Payment apps offered for a card: label and package. Shown only when installed (see the manifest's queries). */
    public static final String[][] APPS = {
        {"Payme", "uz.dida.payme"},
        {"Click", "air.com.ssdsoftwaresolutions.clickuz"},
    };

    /**
     * Copies a card number marked as sensitive, so the keyboard / clipboard preview do not show it
     * (ClipDescription.EXTRA_IS_SENSITIVE, honoured from Android 13).
     */
    public static void copySensitive(String text) {
        try {
            final android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            final android.content.ClipData clip = android.content.ClipData.newPlainText("card", text == null ? "" : text);
            final android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.addToClipboard(text);
        }
    }

    public static String digits(String card) {
        return card == null ? "" : card.replaceAll("\\D", "");
    }

    /** Payment system by IIN prefix, or null when unknown. */
    public static String system(String card) {
        final String d = digits(card);
        if (d.length() < 4) {
            return null;
        }
        final int p2 = Integer.parseInt(d.substring(0, 2));
        final int p4 = Integer.parseInt(d.substring(0, 4));
        if (d.startsWith("8600")) return "Uzcard";
        if (d.startsWith("9860")) return "Humo";
        if (d.startsWith("4")) return "Visa";
        if (p2 >= 51 && p2 <= 55 || p4 >= 2221 && p4 <= 2720) return "Mastercard";
        if (p4 >= 2200 && p4 <= 2204) return "Mir";
        if (d.startsWith("62")) return "UnionPay";
        if (p2 == 34 || p2 == 37) return "American Express";
        return null;
    }

    /** "8600 •••• •••• 1234" */
    public static String masked(String card) {
        final String d = digits(card);
        if (d.length() < 8) {
            return d;
        }
        return d.substring(0, 4) + " •••• •••• " + d.substring(d.length() - 4);
    }

    public static Intent launchIntent(Context context, String packageName) {
        try {
            return context.getPackageManager().getLaunchIntentForPackage(packageName);
        } catch (Exception e) {
            return null;
        }
    }
}
