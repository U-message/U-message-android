package org.telegram.messenger;

import android.text.TextUtils;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;

/**
 * U message: on-device translation with ML Kit, so whole-chat translation works without Premium.
 * The language model for a pair is downloaded once (~30 MB), then translation runs offline.
 */
public class UMessageTranslator {

    private static final HashMap<String, Translator> translators = new HashMap<>();

    /** Premium users keep Telegram's server translation; everyone else translates on the device. */
    public static boolean shouldUse(int account) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isEnabled(org.telegram.messenger.UMessageRemoteFlags.OFFLINE_TRANSLATE)) return false;
        // the real Telegram flag: the local "Telegram Premium" option does not unlock server translation
        final org.telegram.tgnet.TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        return self == null || !self.premium;
    }

    public static boolean isSupported(String language) {
        return TranslateLanguage.fromLanguageTag(normalize(language)) != null;
    }

    /** Translates {@code text} into {@code toLanguage}; {@code done} gets the result (or null) on the UI thread. */
    public static void translate(String text, String toLanguage, Utilities.Callback<String> done) {
        if (TextUtils.isEmpty(text) || done == null) {
            AndroidUtilities.runOnUIThread(() -> { if (done != null) done.run(null); });
            return;
        }
        final String to = TranslateLanguage.fromLanguageTag(normalize(toLanguage));
        if (to == null) {
            AndroidUtilities.runOnUIThread(() -> done.run(null));
            return;
        }
        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener(detected -> {
                final String from = "und".equals(detected) ? null : TranslateLanguage.fromLanguageTag(normalize(detected));
                if (from == null) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                } else if (from.equals(to)) {
                    AndroidUtilities.runOnUIThread(() -> done.run(text));
                } else {
                    translate(text, from, to, done);
                }
            })
            .addOnFailureListener(e -> AndroidUtilities.runOnUIThread(() -> done.run(null)));
    }

    private static void translate(String text, String from, String to, Utilities.Callback<String> done) {
        final Translator translator = getTranslator(from, to);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(unused -> translator.translate(text)
                .addOnSuccessListener(result -> AndroidUtilities.runOnUIThread(() -> done.run(result)))
                .addOnFailureListener(e -> {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                }))
            .addOnFailureListener(e -> {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> done.run(null));
            });
    }

    private static synchronized Translator getTranslator(String from, String to) {
        final String key = from + ">" + to;
        Translator translator = translators.get(key);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(from)
                .setTargetLanguage(to)
                .build());
            translators.put(key, translator);
        }
        return translator;
    }

    /** Telegram language codes may carry a region or script ("pt-br", "zh_hans"): ML Kit wants the base tag. */
    private static String normalize(String language) {
        if (language == null) return "";
        String lang = language.replace('_', '-').toLowerCase();
        final int dash = lang.indexOf('-');
        return dash > 0 ? lang.substring(0, dash) : lang;
    }
}
