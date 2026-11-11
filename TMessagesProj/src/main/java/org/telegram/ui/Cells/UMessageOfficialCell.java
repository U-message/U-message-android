package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageOfficialChat;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.ui.ActionBar.Theme;

/** Chat-list row of the official in-app "U message" chat, drawn like a {@link DialogCell}. */
public class UMessageOfficialCell extends View implements NotificationCenter.NotificationCenterDelegate {

    private final int account;
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mark;
    private final Drawable verifiedArea;
    private final Drawable verifiedCheck;
    private final RectF rect = new RectF();

    private StaticLayout textLayout;
    private String time;
    private String count;

    public UMessageOfficialCell(Context context, int account) {
        super(context);
        this.account = account;
        mark = context.getResources().getDrawable(R.drawable.umessage_mark).mutate();
        verifiedArea = context.getResources().getDrawable(R.drawable.verified_area).mutate();
        verifiedCheck = context.getResources().getDrawable(R.drawable.verified_check).mutate();
        namePaint.setTypeface(AndroidUtilities.bold());
        namePaint.setTextSize(dp(16));
        textPaint.setTextSize(dp(15));
        timePaint.setTextSize(dp(13));
        countPaint.setTypeface(AndroidUtilities.bold());
        countPaint.setTextSize(dp(13));
        countPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.umessageOfficialChanged);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.umessageOfficialChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        update();
    }

    public void update() {
        textLayout = null;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(72) + 1);
        buildText();
    }

    private void buildText() {
        final UMessageOfficialChat chat = UMessageOfficialChat.getInstance(account);
        final UMessageOfficialChat.Message last = chat.getLast();
        CharSequence preview = "";
        time = "";
        if (last != null) {
            preview = last.isOffer()
                ? LocaleController.formatString(R.string.UMessageOfficialOfferPreview, UMessagePremiumController.formatUname(last.name))
                : last.text.replace('\n', ' ');
            time = LocaleController.stringForMessageListDate(last.date / 1000);
        }
        final int unread = chat.getUnreadCount();
        count = unread > 0 ? String.valueOf(unread) : null;
        int width = getMeasuredWidth() - dp(72 + 16) - (count != null ? dp(32) : dp(24));
        if (width < 0) width = 0;
        textLayout = new StaticLayout(TextUtils.ellipsize(preview, textPaint, width, TextUtils.TruncateAt.END), textPaint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (textLayout == null) {
            buildText();
        }
        final boolean rtl = LocaleController.isRTL;
        final int w = getWidth();
        final float cx = rtl ? w - dp(10 + 28) : dp(10 + 28), cy = dp(36);

        avatarPaint.setColor(0xFF000000);
        canvas.drawCircle(cx, cy, dp(28), avatarPaint);
        final int mw = dp(30), mh = (int) (mw * 425f / 740f);
        mark.setBounds((int) cx - mw / 2, (int) cy - mh / 2, (int) cx + mw / 2, (int) cy + mh / 2);
        mark.draw(canvas);

        namePaint.setColor(Theme.getColor(Theme.key_chats_name));
        textPaint.setColor(Theme.getColor(Theme.key_chats_message));
        timePaint.setColor(Theme.getColor(Theme.key_chats_date));

        final String name = "U message";
        final float nameW = namePaint.measureText(name);
        final float nameX = rtl ? w - dp(72) - nameW : dp(72);
        canvas.drawText(name, nameX, dp(31), namePaint);

        final int vs = dp(16);
        final int vx = (int) (rtl ? nameX - dp(4) - vs : nameX + nameW + dp(4));
        final int vy = dp(17);
        verifiedArea.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedBackground), PorterDuff.Mode.MULTIPLY));
        verifiedCheck.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedCheck), PorterDuff.Mode.MULTIPLY));
        verifiedArea.setBounds(vx, vy, vx + vs, vy + vs);
        verifiedCheck.setBounds(vx, vy, vx + vs, vy + vs);
        verifiedArea.draw(canvas);
        verifiedCheck.draw(canvas);

        if (time != null) {
            final float tw = timePaint.measureText(time);
            canvas.drawText(time, rtl ? dp(15) : w - dp(15) - tw, dp(30), timePaint);
        }

        canvas.save();
        canvas.translate(rtl ? w - dp(72) - textLayout.getWidth() : dp(72), dp(39));
        textLayout.draw(canvas);
        canvas.restore();

        if (count != null) {
            final float tw = Math.max(dp(23), countPaint.measureText(count) + dp(14));
            final float right = rtl ? dp(14) + tw : w - dp(14);
            rect.set(right - tw, dp(39), right, dp(39) + dp(23));
            badgePaint.setColor(Theme.getColor(UMessageOfficialChat.getInstance(account).isMuted() ? Theme.key_chats_unreadCounterMuted : Theme.key_chats_unreadCounter));
            canvas.drawRoundRect(rect, dp(11.5f), dp(11.5f), badgePaint);
            countPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
            canvas.drawText(count, rect.centerX(), rect.bottom - dp(6.5f), countPaint);
        }

        canvas.drawLine(rtl ? 0 : dp(72), getHeight() - 1, rtl ? w - dp(72) : w, getHeight() - 1, Theme.dividerPaint);
    }
}
