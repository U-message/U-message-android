package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet;

/**
 * U message: tapping the small logo next to a mutual contact's status opens this, Telegram's "Premium user"
 * sheet with its own content: the profile's badge flies into a thick 3D logo that keeps playing (Premium's
 * idle spins, always the whole logo), and the text explains why it is shown.
 */
public class UMessageMutualContactSheet extends PremiumPreviewBottomSheet {

    public UMessageMutualContactSheet(BaseFragment fragment, int currentAccount, TLRPC.User user, Theme.ResourcesProvider resourcesProvider) {
        super(fragment, currentAccount, user, resourcesProvider);
    }

    /** Opens the sheet; the profile's badge flies into it the way Telegram's Premium star does. */
    public static void show(BaseFragment fragment, int currentAccount, TLRPC.User user, SimpleTextView from, Theme.ResourcesProvider resourcesProvider) {
        UMessageMutualContactSheet sheet = new UMessageMutualContactSheet(fragment, currentAccount, user, resourcesProvider);
        // the sheet adds startEnterFromX1/Y1 to the badge's position inside the view, so pass the view's untransformed origin on screen
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        float[] origin = new float[]{0, 0};
        from.getMatrix().mapPoints(origin);
        sheet.startEnterFromX = from.rightDrawableX;
        sheet.startEnterFromY = from.rightDrawableY;
        sheet.startEnterFromScale = from.getScaleX();
        sheet.startEnterFromX1 = loc[0] - origin[0];
        sheet.startEnterFromY1 = loc[1] - origin[1];
        sheet.startEnterFromView = from;
        if (from.getRightDrawable() instanceof Badge) {
            sheet.badge = (Badge) from.getRightDrawable();
        }
        fragment.showDialog(sheet);
    }

    private Badge badge;

    // the badge came down into the sheet: it stays off the profile until the sheet closes
    @Override
    protected boolean restoreStartEnterDrawable() {
        return false;
    }

    @Override
    public void dismiss() {
        super.dismiss();
        if (badge != null && startEnterFromView != null) {
            final Badge b = badge;
            final android.view.View view = startEnterFromView;
            badge = null;
            android.animation.ValueAnimator back = android.animation.ValueAnimator.ofInt(0, 255);
            back.addUpdateListener(a -> {
                b.setAlpha((Integer) a.getAnimatedValue());
                view.invalidate();
            });
            back.setStartDelay(200);
            back.setDuration(250);
            back.start();
        }
    }

    @Override
    protected int getIconType() {
        return Icon3D.TYPE_UMESSAGE;
    }

    @Override
    protected int getIconColorKey1() {
        return Theme.key_windowBackgroundWhiteBlackText;
    }

    @Override
    protected int getIconColorKey2() {
        // one color: black on a light sheet, white on a dark one
        return Theme.key_windowBackgroundWhiteBlackText;
    }

    @Override
    protected void updateRows() {
        premiumFeatures.clear();
        paddingRow = rowCount++;
        featuresStartRow = featuresEndRow = rowCount;
        sectionRow = rowCount++;
        buttonRow = -1;
    }

    @Override
    protected boolean needDefaultPremiumBtn() {
        return false;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.UMessageMutualWhy);
    }

    @Override
    public void setTitle(boolean animated) {
        if (titleView == null || subtitleView == null) {
            return;
        }
        String name = ContactsController.formatName(user.first_name, user.last_name);
        titleView[0].setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.UMessageMutualTitle, name)));
        subtitleView.setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.UMessageMutualText, name)));
    }

    /** The small logo drawn after the status line; it takes the line's own color, so it stays quiet. */
    public static class Badge extends Drawable {

        private final Drawable mark;
        private final SimpleTextView textView;
        private int lastColor;

        public Badge(SimpleTextView textView) {
            this.textView = textView;
            mark = ContextCompat.getDrawable(textView.getContext(), R.drawable.umessage_mark).mutate();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int color = textView.getTextColor();
            if (color != lastColor) {
                lastColor = color;
                mark.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
            mark.setBounds(getBounds());
            mark.draw(canvas);
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(17);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(10);
        }

        @Override
        public void setAlpha(int alpha) {
            mark.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }
    }
}
