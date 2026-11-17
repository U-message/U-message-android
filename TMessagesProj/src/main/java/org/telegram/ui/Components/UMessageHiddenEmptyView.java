package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * U message: Hidden chats & accounts with nothing hidden yet. Says so, and a button under the text opens the
 * iOS style picker (UMessageHidePickerSheet) to choose chats to hide.
 */
public class UMessageHiddenEmptyView extends LinearLayout {

    public interface Delegate {
        void onHide(ArrayList<Long> dialogIds);
    }

    public UMessageHiddenEmptyView(Context context, int account, Delegate delegate) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setClickable(true);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.msg_archive_hide);
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        icon.setBackground(Theme.createRoundRectDrawable(dp(22), UMessageModernList.INDIGO));
        icon.setScaleX(1.3f);
        icon.setScaleY(1.3f);
        addView(icon, LayoutHelper.createLinear(64, 64));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.UMessageHiddenNothingYet));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 22, 0, 8));

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.UMessageHiddenPickHint));
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        hint.setGravity(Gravity.CENTER);
        addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 36, 0, 36, 0));

        TextView button = new TextView(context);
        button.setText(LocaleController.getString(R.string.UMessageHideChatsButton));
        button.setTextColor(0xffffffff);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(28), 0, dp(28), 0);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(24), UMessageModernList.BLUE, 0x33ffffff));
        button.setOnClickListener(v -> UMessageHidePickerSheet.show(context, account, delegate::onHide));
        addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, 0, 26, 0, 0));
    }

    /** Kept for the caller: the content is centred, the dock leaves it alone. */
    public void setBottomInset(int px) {
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), px);
    }

    public void reload() {
    }
}
