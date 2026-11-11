package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageOfficialChat;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/** Profile of the official U message account, laid out like Telegram's profile of a service account. */
public class UMessageOfficialProfileActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private ImageView soundIcon;
    private TextView soundLabel;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.umessageOfficialChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.umessageOfficialChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        updateSound();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(12), 0, dp(12), dp(24));
        scroll.addView(column);

        View avatar = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Drawable mark = context.getResources().getDrawable(R.drawable.umessage_mark).mutate();

            @Override
            protected void onDraw(Canvas canvas) {
                paint.setColor(0xFF000000);
                final float r = getWidth() / 2f;
                canvas.drawCircle(r, r, r, paint);
                final int mw = (int) (getWidth() * 0.52f), mh = (int) (mw * 425f / 740f);
                mark.setBounds((int) r - mw / 2, (int) r - mh / 2, (int) r + mw / 2, (int) r + mh / 2);
                mark.draw(canvas);
            }
        };
        column.addView(avatar, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        TextView name = new TextView(context);
        name.setText("U message");
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        name.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_UNBOUNDED_BOLD));
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        Drawable verified = new LayerDrawable(new Drawable[]{
            tinted(R.drawable.verified_area, getThemedColor(Theme.key_profile_verifiedBackground)),
            tinted(R.drawable.verified_check, getThemedColor(Theme.key_profile_verifiedCheck))
        });
        verified.setBounds(0, 0, dp(20), dp(20));
        name.setCompoundDrawables(null, null, verified, null);
        name.setCompoundDrawablePadding(dp(6));
        column.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.UMessageOfficialAccount));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 0, 28, 0, 0));
        LinearLayout chat = actionButton(context, R.drawable.outline_profile_message_24, getString(R.string.ProfileActionsMessage), null);
        chat.setOnClickListener(v -> openChat());
        actions.addView(chat, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 0, 0, 4, 0));
        LinearLayout sound = actionButton(context, R.drawable.outline_profile_unmute_24, "", this);
        sound.setOnClickListener(v -> {
            final UMessageOfficialChat c = UMessageOfficialChat.getInstance(currentAccount);
            c.setMuted(!c.isMuted());
        });
        actions.addView(sound, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 4, 0, 0, 0));
        updateSound();

        // username card where Telegram shows the phone number
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(18), dp(12), dp(18), dp(12));
        info.setBackground(card());
        info.setForeground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 18, 18));
        TextView uname = new TextView(context);
        uname.setText(UMessagePremiumController.formatUname(UMessageOfficialChat.UNAME));
        uname.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        uname.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        info.addView(uname);
        TextView unameLabel = new TextView(context);
        unameLabel.setText(getString(R.string.Username));
        unameLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        unameLabel.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        info.addView(unameLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        info.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(UMessagePremiumController.formatUname(UMessageOfficialChat.UNAME));
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.UMessageUsernameCopied)).show();
        });
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        return fragmentView = scroll;
    }

    private void openChat() {
        // came from the chat: just go back; otherwise open it
        if (getParentLayout() != null && getParentLayout().getFragmentStack().size() >= 2
                && getParentLayout().getFragmentStack().get(getParentLayout().getFragmentStack().size() - 2) instanceof UMessageOfficialChatActivity) {
            finishFragment();
        } else {
            presentFragment(new UMessageOfficialChatActivity());
        }
    }

    private void updateSound() {
        if (soundIcon == null) {
            return;
        }
        final boolean muted = UMessageOfficialChat.getInstance(currentAccount).isMuted();
        soundIcon.setImageResource(muted ? R.drawable.outline_profile_unmute_24 : R.drawable.outline_profile_mute_24);
        soundLabel.setText(getString(muted ? R.string.ProfileButtonUnmute : R.string.ProfileButtonMute));
    }

    private GradientDrawable card() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(dp(18));
        return bg;
    }

    private LinearLayout actionButton(Context context, int icon, String label, UMessageOfficialProfileActivity soundOwner) {
        LinearLayout b = new LinearLayout(context);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(card());
        b.setForeground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 18, 18));
        ImageView iv = new ImageView(context);
        iv.setImageResource(icon);
        iv.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        b.addView(iv, LayoutHelper.createLinear(24, 24));
        TextView t = new TextView(context);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        b.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        if (soundOwner != null) {
            soundIcon = iv;
            soundLabel = t;
        }
        return b;
    }

    private Drawable tinted(int res, int color) {
        Drawable d = getContext().getResources().getDrawable(res).mutate();
        d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        return d;
    }
}
