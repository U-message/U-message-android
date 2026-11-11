package org.telegram.ui.Components;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import org.telegram.messenger.UMessageConfig;

/**
 * U message quick replies: "/shortcut" followed by a space is replaced with the saved template.
 * The shortcut must start the text or follow a whitespace, so links and paths are left alone.
 */
public class UMessageTemplatesWatcher implements TextWatcher {

    private final EditText editText;
    private boolean applying;
    private int changeEnd = -1;

    public UMessageTemplatesWatcher(EditText editText) {
        this.editText = editText;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // only react to typing a single space
        changeEnd = count == 1 && before == 0 && start < s.length() && s.charAt(start) == ' ' ? start + 1 : -1;
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (applying || changeEnd <= 1 || UMessageConfig.getTemplates().isEmpty()) {
            return;
        }
        final int end = changeEnd - 1; // position of the space
        int slash = end - 1;
        while (slash >= 0 && s.charAt(slash) != '/' && !Character.isWhitespace(s.charAt(slash))) {
            slash--;
        }
        if (slash < 0 || s.charAt(slash) != '/' || slash > 0 && !Character.isWhitespace(s.charAt(slash - 1))) {
            return;
        }
        final String shortcut = s.subSequence(slash + 1, end).toString();
        if (shortcut.isEmpty()) {
            return;
        }
        final UMessageConfig.Template template = UMessageConfig.findTemplate(shortcut);
        if (template == null) {
            return;
        }
        applying = true;
        try {
            s.replace(slash, end + 1, template.text);
            final int cursor = Math.min(s.length(), slash + template.text.length());
            editText.setSelection(cursor);
        } catch (Exception ignore) {
        } finally {
            applying = false;
        }
    }
}
