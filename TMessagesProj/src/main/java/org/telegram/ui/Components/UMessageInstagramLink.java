package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagram;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** The Instagram row of a profile: its text, and the editor of your own link. */
public final class UMessageInstagramLink {

    private UMessageInstagramLink() {
    }

    /** "@name" followed by the verified mark for a link confirmed through Instagram login. */
    public static CharSequence rowText(Context context, UMessageInstagram.Link link, Theme.ResourcesProvider resourcesProvider) {
        final SpannableStringBuilder text = new SpannableStringBuilder("@").append(link.username);
        if (link.verified) {
            final Drawable area = ContextCompat.getDrawable(context, R.drawable.verified_area).mutate();
            final Drawable check = ContextCompat.getDrawable(context, R.drawable.verified_check).mutate();
            area.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedBackground, resourcesProvider), PorterDuff.Mode.SRC_IN));
            check.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedCheck, resourcesProvider), PorterDuff.Mode.SRC_IN));
            final CombinedDrawable drawable = new CombinedDrawable(area, check);
            drawable.setCustomSize(dp(16), dp(16));
            drawable.setIconSize(dp(16), dp(16));
            final ColoredImageSpan span = new ColoredImageSpan(drawable, ColoredImageSpan.ALIGN_CENTER);
            span.recolorDrawable = false;
            final int start = text.length();
            text.append(" v");
            text.setSpan(span, start + 1, start + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    public static void showEditor(BaseFragment fragment) {
        final Context context = fragment == null ? null : fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final UMessageInstagram.Link current = UMessageInstagram.getLink(UserConfig.getInstance(account).getClientUserId());

        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        edit.setHintText(getString(R.string.UMessageInstagramUsernameHint));
        edit.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, rp));
        edit.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        edit.setCursorSize(dp(20));
        edit.setCursorWidth(1.5f);
        edit.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, rp), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, rp), Theme.getColor(Theme.key_text_RedRegular, rp));
        edit.setBackground(null);
        edit.setSingleLine(true);
        edit.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // a pasted profile link is accepted too, so this is longer than a username
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        if (current != null) {
            edit.setText(current.username);
            edit.setSelection(edit.length());
        }

        final FrameLayout frame = new FrameLayout(context);
        frame.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP, 24, 8, 24, 4));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, rp);
        builder.setTitle("Instagram");
        builder.setMessage(getString(R.string.UMessageInstagramLinkHint));
        builder.setView(frame);
        builder.setPositiveButton(getString(R.string.Save), null);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (current != null) {
            builder.setNeutralButton(getString(R.string.Remove), null);
        }
        final AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);

        final View save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        final View remove = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        final UMessageInstagram.Callback[] done = new UMessageInstagram.Callback[1];
        final int[] messageRes = new int[1];
        done[0] = (result, error) -> {
            if (save != null) save.setEnabled(true);
            if (remove != null) remove.setEnabled(true);
            if (error == null) {
                dialog.dismiss();
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, getString(messageRes[0])).show();
            } else if (error.contains("invalid")) {
                AndroidUtilities.shakeView(edit);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, getString(R.string.UMessageInstagramInvalid)).show();
            } else {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, getString(R.string.UnknownError)).show();
            }
        };
        if (save != null) {
            save.setOnClickListener(v -> {
                final String username = UMessageInstagram.normalizeUsername(edit.getText().toString());
                if (username == null) {
                    AndroidUtilities.shakeView(edit);
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, getString(R.string.UMessageInstagramInvalid)).show();
                    return;
                }
                save.setEnabled(false);
                messageRes[0] = R.string.UMessageInstagramSaved;
                UMessageInstagram.setLink(account, username, done[0]);
            });
        }
        if (remove != null) {
            remove.setOnClickListener(v -> {
                remove.setEnabled(false);
                messageRes[0] = R.string.UMessageInstagramRemoved;
                UMessageInstagram.clearLink(account, done[0]);
            });
        }
        edit.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(edit), 150);
    }
}
