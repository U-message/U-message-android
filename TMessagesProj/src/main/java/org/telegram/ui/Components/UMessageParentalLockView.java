package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageParental;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.Locale;

/** U message parental control: covers the whole app once the daily limit is used up or during night rest. */
public class UMessageParentalLockView extends FrameLayout {

    private final TextView emojiView, titleView, textView;
    private final ButtonWithCounterView askButton;
    private int reason = -1;

    public UMessageParentalLockView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        emojiView = new TextView(context);
        emojiView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 64);
        emojiView.setGravity(Gravity.CENTER);
        layout.addView(emojiView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_UNBOUNDED_BOLD));
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setGravity(Gravity.CENTER);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 28));

        askButton = new ButtonWithCounterView(context, null);
        askButton.setOnClickListener(v -> {
            UMessageParental.askMoreTime();
            updateAskButton();
        });
        layout.addView(askButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 10));

        ButtonWithCounterView pinButton = new ButtonWithCounterView(context, false, null);
        pinButton.setText(LocaleController.getString(R.string.UMessageParentalUnlockPin), false);
        pinButton.setOnClickListener(v -> askPin(context, LocaleController.getString(R.string.UMessageParentalPinEnter), pin -> {
            long locked = org.telegram.messenger.UMessageConfig.getPinLockRemainingMs(org.telegram.messenger.UMessageConfig.PIN_LOCK_PARENTAL);
            if (locked <= 0 && UMessageParental.checkPin(pin)) {
                org.telegram.messenger.UMessageConfig.resetPinLock(org.telegram.messenger.UMessageConfig.PIN_LOCK_PARENTAL);
                UMessageParental.grantExtra(30);
            } else {
                if (locked <= 0) {
                    locked = org.telegram.messenger.UMessageConfig.onPinLockFailed(org.telegram.messenger.UMessageConfig.PIN_LOCK_PARENTAL);
                }
                Toast.makeText(context, locked > 0 ? org.telegram.messenger.UMessageConfig.formatPinLock(locked) : LocaleController.getString(R.string.UMessageParentalPinWrong), Toast.LENGTH_SHORT).show();
            }
        }));
        layout.addView(pinButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
    }

    public void setReason(int reason) {
        updateAskButton();
        if (this.reason == reason) return;
        this.reason = reason;
        UMessageParental.Rules r = UMessageParental.effectiveRules();
        if (reason == UMessageParental.LOCK_NIGHT) {
            emojiView.setText("🌙");
            titleView.setText(LocaleController.getString(R.string.UMessageParentalNightTitle));
            textView.setText(LocaleController.formatString(R.string.UMessageParentalNightText,
                    r != null ? time(r.nightFrom) : "", r != null ? time(r.nightTo) : ""));
        } else {
            emojiView.setText("⏰");
            titleView.setText(LocaleController.getString(R.string.UMessageParentalLimitTitle));
            textView.setText(LocaleController.formatString(R.string.UMessageParentalLimitText, UMessageParental.formatMinutes(r != null ? r.limit : 0)));
        }
    }

    private void updateAskButton() {
        boolean asked = UMessageParental.isMoreTimeAsked();
        askButton.setText(LocaleController.getString(asked ? R.string.UMessageParentalAsked : R.string.UMessageParentalAskMore), false);
        askButton.setEnabled(!asked);
        askButton.setAlpha(asked ? 0.6f : 1f);
    }

    public static String time(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** A small dialog for the 4 digit parent PIN. */
    public static void askPin(Context context, CharSequence title, org.telegram.messenger.Utilities.Callback<String> done) {
        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        field.setGravity(Gravity.CENTER);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        FrameLayout box = new FrameLayout(context);
        box.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 8, 24, 0));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
                    String pin = field.getText().toString();
                    if (pin.length() == 4) done.run(pin);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.show();
        field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(field), 200);
    }

    /** Shows, refreshes or removes the lock over {@code root} as the rules say. */
    public static UMessageParentalLockView update(ViewGroup root, UMessageParentalLockView view) {
        int reason = UMessageParental.getLockReason();
        if (reason == UMessageParental.LOCK_NONE) {
            if (view != null && view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
            return view;
        }
        if (view == null) view = new UMessageParentalLockView(root.getContext());
        if (view.getParent() == null) {
            root.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            AndroidUtilities.hideKeyboard(root);
        } else {
            view.bringToFront();
        }
        view.setReason(reason);
        return view;
    }
}
