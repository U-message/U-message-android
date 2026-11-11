package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UMessageOfficialChat;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

/** The official in-app "U message" chat (umessage@u): admin notices and username offers. */
public class UMessageOfficialChatActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private Adapter adapter;
    private final ArrayList<String> busyCodes = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.umessageOfficialChanged);
        UMessageOfficialChat.getInstance(currentAccount).refresh(true);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.umessageOfficialChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        UMessageOfficialChat.getInstance(currentAccount).markRead();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            scrollToBottom();
        }
        if (isResumed()) {
            UMessageOfficialChat.getInstance(currentAccount).markRead();
        }
    }

    private boolean isResumed() {
        return fragmentView != null && !isPaused;
    }

    private SizeNotifierFrameLayout contentView;

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);
        // no flat action bar: floating header pills over the wallpaper, like Telegram's chat screen
        actionBar.setAddToContainer(false);
        actionBar.setCastShadows(false);
        actionBar.setBackground(null);

        contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected boolean isActionBarVisible() {
                return false;
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }
        };
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        listView = new RecyclerListView(context);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        listView.setPadding(0, AndroidUtilities.statusBarHeight + dp(68), 0, dp(8));
        listView.setClipToPadding(false);
        listView.setItemAnimator(null);
        listView.setAdapter(adapter = new Adapter());
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                contentView.invalidate();
                listView.invalidate();
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        contentView.addView(createHeader(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        scrollToBottom();
        return fragmentView = contentView;
    }

    /** Telegram-like chat header: round avatar, name with the verified badge, "official account" line. */
    private View pill(View view, int radiusDp) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Theme.multAlpha(getThemedColor(Theme.key_actionBarDefault), 0.96f));
        bg.setCornerRadius(dp(radiusDp));
        view.setBackground(bg);
        view.setElevation(dp(2));
        return view;
    }

    private View createHeader(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipToPadding(false);
        bar.setPadding(dp(6), AndroidUtilities.statusBarHeight + dp(6), dp(6), dp(6));

        android.widget.ImageView back = new android.widget.ImageView(context);
        back.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        BackDrawable backDrawable = new BackDrawable(false);
        backDrawable.setColor(getThemedColor(Theme.key_actionBarDefaultIcon));
        back.setImageDrawable(backDrawable);
        pill(back, 26);
        back.setForeground(Theme.createSelectorDrawable(getThemedColor(Theme.key_actionBarDefaultSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        bar.addView(back, LayoutHelper.createLinear(52, 52, 0, 0, 6, 0));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(3), 0, dp(16), 0);
        pill(header, 26);
        header.setForeground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_actionBarDefaultSelector), 26, 26));
        header.setOnClickListener(v -> presentFragment(new UMessageOfficialProfileActivity()));
        bar.addView(header, LayoutHelper.createLinear(0, 52, 1f));

        android.widget.ImageView more = new android.widget.ImageView(context);
        more.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        more.setImageResource(R.drawable.ic_ab_other);
        more.setColorFilter(new android.graphics.PorterDuffColorFilter(getThemedColor(Theme.key_actionBarDefaultIcon), android.graphics.PorterDuff.Mode.MULTIPLY));
        pill(more, 26);
        more.setForeground(Theme.createSelectorDrawable(getThemedColor(Theme.key_actionBarDefaultSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        more.setOnClickListener(v -> {
            final UMessageOfficialChat chat = UMessageOfficialChat.getInstance(currentAccount);
            final boolean muted = chat.isMuted();
            org.telegram.ui.Components.ItemOptions.makeOptions(this, more)
                .add(muted ? R.drawable.msg_unmute : R.drawable.msg_mute, getString(muted ? R.string.ProfileButtonUnmute : R.string.ProfileButtonMute), () -> chat.setMuted(!muted))
                .add(R.drawable.msg_openprofile, getString(R.string.OpenProfile), () -> presentFragment(new UMessageOfficialProfileActivity()))
                .show();
        });
        bar.addView(more, LayoutHelper.createLinear(52, 52, 6, 0, 0, 0));

        View avatar = new View(context) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.drawable.Drawable mark = context.getResources().getDrawable(R.drawable.umessage_mark).mutate();

            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                paint.setColor(0xFF000000);
                final float r = getWidth() / 2f;
                canvas.drawCircle(r, r, r, paint);
                final int mw = (int) (getWidth() * 0.52f), mh = (int) (mw * 425f / 740f);
                mark.setBounds((int) r - mw / 2, (int) r - mh / 2, (int) r + mw / 2, (int) r + mh / 2);
                mark.draw(canvas);
            }
        };
        header.addView(avatar, LayoutHelper.createLinear(46, 46, 0, 0, 10, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("U message");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine();
        title.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        android.graphics.drawable.Drawable verified = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
            tinted(R.drawable.verified_area, getThemedColor(Theme.key_profile_verifiedBackground)),
            tinted(R.drawable.verified_check, getThemedColor(Theme.key_profile_verifiedCheck))
        });
        verified.setBounds(0, 0, dp(18), dp(18));
        title.setCompoundDrawables(null, null, verified, null);
        title.setCompoundDrawablePadding(dp(4));
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.UMessageOfficialAccount));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setSingleLine();
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        header.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return bar;
    }

    private android.graphics.drawable.Drawable tinted(int res, int color) {
        android.graphics.drawable.Drawable d = getContext().getResources().getDrawable(res).mutate();
        d.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY));
        return d;
    }

    private void scrollToBottom() {
        if (listView != null && adapter.getItemCount() > 0) {
            listView.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void accept(UMessageOfficialChat.Message m) {
        if (busyCodes.contains(m.code)) {
            return;
        }
        busyCodes.add(m.code);
        adapter.notifyDataSetChanged();
        UMessagePremiumController.getInstance().unameAction(currentAccount, "claim", m.code, (result, error) -> {
            busyCodes.remove(m.code);
            if (error == null) {
                UMessageOfficialChat.getInstance(currentAccount).setOfferState(m.code, "accepted");
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.UMessageUnameAccepted, UMessagePremiumController.formatUname(m.name))).show();
            } else {
                if ("already_yours".equals(error)) {
                    UMessageOfficialChat.getInstance(currentAccount).setOfferState(m.code, "accepted");
                } else if ("bad_code".equals(error) || "used_code".equals(error)) {
                    UMessageOfficialChat.getInstance(currentAccount).setOfferState(m.code, "expired");
                }
                adapter.notifyDataSetChanged();
                BulletinFactory.of(this).createErrorBulletin(UMessageUsernameActivity.errorText(error)).show();
            }
        });
    }

    private void decline(UMessageOfficialChat.Message m) {
        showDialog(new AlertDialog.Builder(getContext(), getResourceProvider())
            .setTitle(getString(R.string.UMessageUnameDeclineTitle))
            .setMessage(LocaleController.formatString(R.string.UMessageUnameDeclineText, UMessagePremiumController.formatUname(m.name)))
            .setPositiveButton(getString(R.string.UMessageUnameDecline), (d, w) -> {
                busyCodes.add(m.code);
                adapter.notifyDataSetChanged();
                UMessagePremiumController.getInstance().unameAction(currentAccount, "decline", m.code, (result, error) -> {
                    busyCodes.remove(m.code);
                    if (error == null || "bad_code".equals(error)) {
                        UMessageOfficialChat.getInstance(currentAccount).setOfferState(m.code, error == null ? "declined" : "expired");
                    } else {
                        adapter.notifyDataSetChanged();
                        BulletinFactory.of(this).createErrorBulletin(UMessageUsernameActivity.errorText(error)).show();
                    }
                });
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .makeRed(AlertDialog.BUTTON_POSITIVE)
            .create());
    }

    private static final int TYPE_DATE = 0, TYPE_MESSAGE = 1;

    /** Rows: a date pill before each new day, then that day's messages. */
    private final ArrayList<Object> rows = new ArrayList<>();

    private void buildRows() {
        rows.clear();
        long lastDay = -1;
        for (UMessageOfficialChat.Message m : UMessageOfficialChat.getInstance(currentAccount).getMessages()) {
            final long day = dayOf(m.date);
            if (day != lastDay) {
                rows.add(m.date);
                lastDay = day;
            }
            rows.add(m);
        }
    }

    private static long dayOf(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            buildRows();
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Long ? TYPE_DATE : TYPE_MESSAGE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = viewType == TYPE_DATE ? new DateView(parent.getContext()) : new MessageView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final Object row = rows.get(position);
            if (holder.itemView instanceof DateView) {
                ((DateView) holder.itemView).set((Long) row);
            } else {
                final boolean last = position + 1 >= rows.size() || rows.get(position + 1) instanceof Long;
                ((MessageView) holder.itemView).set((UMessageOfficialChat.Message) row, last);
            }
        }
    }

    /** Centered day pill drawn with the chat service background, like Telegram's date headers. */
    private class DateView extends FrameLayout {
        private final TextView text;

        DateView(Context context) {
            super(context);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextColor(getThemedColor(Theme.key_chat_serviceText));
            text.setPadding(dp(9), dp(3), dp(9), dp(3));
            text.setBackground(Theme.createServiceDrawable(dp(12), text, contentView));
            addView(text, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 8, 0, 8));
        }

        void set(long date) {
            text.setText(LocaleController.formatDateChat(date / 1000));
        }
    }

    /** Reserves room at the end of the last text line for the time, as Telegram bubbles do. */
    private static final class TimeSpace extends android.text.style.ReplacementSpan {
        private final int width;

        TimeSpace(int width) {
            this.width = width;
        }

        @Override
        public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            return width;
        }

        @Override
        public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, android.graphics.Paint paint) {
        }
    }

    /** Incoming bubble with a tail on the last message of a day; offers add inline buttons below it. */
    private class BubbleLayout extends FrameLayout {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint shadow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF arc = new android.graphics.RectF();
        boolean tail;

        BubbleLayout(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int max = Math.min(MeasureSpec.getSize(widthMeasureSpec), dp(420));
            super.onMeasure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), heightMeasureSpec);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float t = dp(6), r = dp(SharedConfig.bubbleRadius), w = getWidth(), h = getHeight();
            final float sr = Math.min(r, dp(6));
            path.rewind();
            path.moveTo(t + r, 0);
            path.lineTo(w - r, 0);
            arc.set(w - 2 * r, 0, w, 2 * r);
            path.arcTo(arc, 270, 90);
            path.lineTo(w, h - r);
            arc.set(w - 2 * r, h - 2 * r, w, h);
            path.arcTo(arc, 0, 90);
            if (tail) {
                path.lineTo(dp(2), h);
                path.quadTo(t, h - dp(1), t, h - dp(8));
            } else {
                path.lineTo(t + sr, h);
                arc.set(t, h - 2 * sr, t + 2 * sr, h);
                path.arcTo(arc, 90, 90);
            }
            path.lineTo(t, r);
            arc.set(t, 0, t + 2 * r, 2 * r);
            path.arcTo(arc, 180, 90);
            path.close();
            shadow.setColor(getThemedColor(Theme.key_chat_inBubbleShadow));
            canvas.save();
            canvas.translate(0, dp(1));
            canvas.drawPath(path, shadow);
            canvas.restore();
            paint.setColor(getThemedColor(Theme.key_chat_inBubble));
            canvas.drawPath(path, paint);
        }
    }

    private class MessageView extends FrameLayout {
        private final BubbleLayout bubble;
        private final TextView titleView;
        private final TextView nameView;
        private final TextView textView;
        private final TextView stateView;
        private final TextView timeView;
        private final LinearLayout buttons;
        private final TextView declineButton;
        private final TextView acceptButton;
        private UMessageOfficialChat.Message message;

        MessageView(Context context) {
            super(context);
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            addView(column, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 6, 0, 52, 0));

            bubble = new BubbleLayout(context);
            column.addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(6 + 10), dp(7), dp(10), dp(7));
            bubble.addView(content, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            titleView = text(context, 15, getThemedColor(Theme.key_chat_inReplyNameText), true);
            content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));
            nameView = text(context, 21, getThemedColor(Theme.key_chat_messageTextIn), true);
            content.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
            textView = text(context, 16, getThemedColor(Theme.key_chat_messageTextIn), false);
            content.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            stateView = text(context, 14, getThemedColor(Theme.key_chat_inTimeText), false);
            content.addView(stateView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            timeView = text(context, 12, getThemedColor(Theme.key_chat_inTimeText), false);
            bubble.addView(timeView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 10, 6));

            buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            declineButton = button(context, getString(R.string.UMessageUnameDecline));
            acceptButton = button(context, getString(R.string.UMessageUnameAccept));
            buttons.addView(declineButton, LayoutHelper.createLinear(0, 40, 1f, 0, 0, 2, 0));
            buttons.addView(acceptButton, LayoutHelper.createLinear(0, 40, 1f, 2, 0, 0, 0));
            column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 4, 0, 0));
            acceptButton.setOnClickListener(v -> accept(message));
            declineButton.setOnClickListener(v -> decline(message));
        }

        private TextView text(Context context, int sp, int color, boolean bold) {
            TextView t = new TextView(context);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
            t.setTextColor(color);
            t.setLineSpacing(0, 1.05f);
            if (bold) t.setTypeface(AndroidUtilities.bold());
            return t;
        }

        /** Inline keyboard button: wallpaper-tinted service background, white bold label. */
        private TextView button(Context context, String label) {
            TextView b = text(context, 15, getThemedColor(Theme.key_chat_serviceText), true);
            b.setText(label);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine();
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setPadding(dp(8), 0, dp(8), 0);
            b.setBackground(Theme.createServiceDrawable(dp(Math.min(SharedConfig.bubbleRadius, 12)), b, contentView));
            return b;
        }

        void set(UMessageOfficialChat.Message m, boolean last) {
            message = m;
            bubble.tail = last;
            final boolean offer = m.isOffer();
            titleView.setVisibility(offer ? VISIBLE : GONE);
            nameView.setVisibility(offer ? VISIBLE : GONE);
            stateView.setVisibility(offer && !m.isPendingOffer() ? VISIBLE : GONE);
            String body;
            if (offer) {
                titleView.setText(getString(R.string.UMessageUnameOfferTitle));
                nameView.setText(UMessagePremiumController.formatUname(m.name));
                body = LocaleController.formatString(R.string.UMessageUnameOfferText, UMessagePremiumController.formatUname(m.name));
                if (!TextUtils.isEmpty(m.text)) body += "\n\n" + m.text;
                if ("accepted".equals(m.state)) {
                    stateView.setText(getString(R.string.UMessageUnameStateAccepted));
                } else if ("declined".equals(m.state)) {
                    stateView.setText(getString(R.string.UMessageUnameStateDeclined));
                } else {
                    stateView.setText(getString(R.string.UMessageUnameStateExpired));
                }
            } else {
                body = m.text;
            }
            final String time = LocaleController.getInstance().getFormatterDay().format(m.date);
            timeView.setText(time);
            final int space = (int) Math.ceil(timeView.getPaint().measureText(time)) + dp(8);
            final boolean stateLast = stateView.getVisibility() == VISIBLE;
            textView.setText(stateLast ? body : withTimeSpace(body, space));
            if (stateLast) {
                stateView.setText(withTimeSpace(stateView.getText().toString(), space));
            }

            final boolean showButtons = m.isPendingOffer();
            buttons.setVisibility(showButtons ? VISIBLE : GONE);
            final boolean busy = busyCodes.contains(m.code);
            acceptButton.setEnabled(!busy);
            declineButton.setEnabled(!busy);
            acceptButton.setAlpha(busy ? 0.5f : 1f);
            declineButton.setAlpha(busy ? 0.5f : 1f);
            setPadding(0, dp(1), 0, dp(last ? 6 : 1));
            setOnClickListener(null);
            setClickable(false);
            bubble.invalidate();
        }

        private CharSequence withTimeSpace(String text, int width) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text == null ? "" : text);
            sb.append(" ");
            sb.append("\u00A0", new TimeSpace(width), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }
    }
}
