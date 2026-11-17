package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * U message: picks the chats to hide, iOS style. A sheet rises to 80% of the screen on a spring; a liquid glass X on
 * the left closes it, a liquid glass Done on the right (always there, enabled once something is ticked) hides them.
 */
public class UMessageHidePickerSheet extends Dialog {

    public interface Delegate {
        void onHide(ArrayList<Long> dialogIds);
    }

    private final int account;
    private final Delegate delegate;
    private final boolean dark;
    private final ArrayList<Long> ids = new ArrayList<>();
    private final HashSet<Long> picked = new HashSet<>();
    private final FrameLayout root;
    private final View dim;
    private final LinearLayout panel;
    private final GlassButton done;
    private SpringAnimation rise;
    private boolean closing;

    public static void show(Context context, int account, Delegate delegate) {
        new UMessageHidePickerSheet(context, account, delegate).show();
    }

    private UMessageHidePickerSheet(Context context, int account, Delegate delegate) {
        super(context, R.style.TransparentDialog);
        this.account = account;
        this.delegate = delegate;
        this.dark = Theme.isCurrentThemeDark();
        final int bg = dark ? 0xff1c1c1e : 0xfff2f2f7, text = dark ? 0xffffffff : 0xff000000;

        root = new FrameLayout(context);
        dim = new View(context);
        dim.setBackgroundColor(0x66000000);
        dim.setAlpha(0f);
        dim.setOnClickListener(v -> close());
        root.addView(dim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(bg);
        panelBg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
        panel.setBackground(panelBg);
        panel.setClickable(true);

        // grabber, then the header: glass X on the left, title, glass Done on the right
        View grabber = new View(context);
        grabber.setBackground(Theme.createRoundRectDrawable(dp(2.5f), Theme.multAlpha(text, 0.22f)));
        panel.addView(grabber, LayoutHelper.createLinear(36, 5, Gravity.CENTER_HORIZONTAL, 0, 7, 0, 0));
        FrameLayout header = new FrameLayout(context);
        GlassButton close = new GlassButton(context, null, R.drawable.ic_layer_close);
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setOnClickListener(v -> close());
        header.addView(close, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.UMessageHiddenPickNone));
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 72, 0, 100, 0));
        done = new GlassButton(context, LocaleController.getString(R.string.Done), 0);
        done.setOnClickListener(v -> {
            if (picked.isEmpty()) return;
            final ArrayList<Long> list = new ArrayList<>(picked);
            close();
            if (delegate != null) delegate.onHide(list);
        });
        header.addView(done, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        panel.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 0, 2, 0, 4));

        // the regular chats, on an inset card
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(24) + AndroidUtilities.navigationBarHeight);
        final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(new Row(parent.getContext()));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((Row) holder.itemView).bind(ids.get(position), position == 0, position == ids.size() - 1);
            }

            @Override
            public int getItemCount() {
                return ids.size();
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((view, position) -> {
            final long id = ids.get(position);
            if (!picked.remove(id)) picked.add(id);
            ((Row) view).check.setChecked(picked.contains(id), true);
            done.setActive(!picked.isEmpty());
        });
        panel.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        final int height = (int) (AndroidUtilities.displaySize.y * 0.8f) + AndroidUtilities.navigationBarHeight;
        root.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        panel.getLayoutParams().height = height;

        final HashSet<Long> hidden = UMessageConfig.getHiddenChats(account);
        for (TLRPC.Dialog d : MessagesController.getInstance(account).getDialogs(0)) {
            if (d == null || hidden.contains(d.id) || DialogObject.isEncryptedDialog(d.id) || d instanceof TLRPC.TL_dialogFolder) continue;
            ids.add(d.id);
        }
        done.setActive(false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setWindowAnimations(R.style.DialogNoAnimation);
        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.FILL;
        params.dimAmount = 0;
        params.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        window.setAttributes(params);
    }

    @Override
    public void show() {
        super.show();
        // rises on a soft spring, like an iOS sheet: unhurried, no bounce past the top
        panel.setTranslationY(AndroidUtilities.displaySize.y);
        dim.animate().alpha(1f).setDuration(380).start();
        panel.post(() -> {
            rise = new SpringAnimation(panel, DynamicAnimation.TRANSLATION_Y, 0);
            rise.getSpring().setStiffness(260f).setDampingRatio(0.86f);
            rise.start();
        });
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void close() {
        if (closing) return;
        closing = true;
        if (rise != null) rise.cancel();
        dim.animate().alpha(0f).setDuration(300).start();
        SpringAnimation fall = new SpringAnimation(panel, DynamicAnimation.TRANSLATION_Y, panel.getHeight() + dp(40));
        fall.getSpring().setStiffness(320f).setDampingRatio(1f);
        fall.addEndListener((a, canceled, value, velocity) -> {
            try {
                dismiss();
            } catch (Exception ignore) {
            }
        });
        fall.start();
    }

    /**
     * iOS 26 style liquid glass control: a translucent capsule with a bright rim and a soft top sheen that stretches
     * wider and squashes a little under the finger, then springs back.
     */
    private class GlassButton extends FrameLayout {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final TextView label;
        private final ImageView icon;
        private boolean active = true, tinted;
        private final SpringAnimation sx, sy;

        GlassButton(Context context, CharSequence text, int iconRes) {
            super(context);
            setWillNotDraw(false);
            tinted = text != null;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(dp(1));
            if (text != null) {
                label = new TextView(context);
                label.setText(text);
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                label.setTypeface(AndroidUtilities.bold());
                label.setGravity(Gravity.CENTER);
                label.setPadding(dp(18), 0, dp(18), 0);
                addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
                icon = null;
            } else {
                icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.CENTER);
                icon.setImageResource(iconRes);
                icon.setColorFilter(new PorterDuffColorFilter(dark ? 0xffffffff : 0xff1c1c1e, PorterDuff.Mode.SRC_IN));
                addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                label = null;
            }
            sx = new SpringAnimation(this, DynamicAnimation.SCALE_X, 1f);
            sy = new SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f);
            sx.getSpring().setStiffness(420f).setDampingRatio(0.55f);
            sy.getSpring().setStiffness(420f).setDampingRatio(0.55f);
            applyColors();
        }

        void setActive(boolean on) {
            active = on;
            setEnabled(on || !tinted);
            applyColors();
        }

        private void applyColors() {
            final int glass = dark ? 0x26ffffff : 0x14000000;
            if (tinted && active) {
                fill.setColor(UMessageModernList.BLUE);
                rim.setColor(0x40ffffff);
                if (label != null) label.setTextColor(0xffffffff);
            } else {
                fill.setColor(glass);
                rim.setColor(dark ? 0x33ffffff : 0x1a000000);
                if (label != null) label.setTextColor(dark ? 0x66ffffff : 0x55000000);
            }
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx.animateToFinalPosition(1.12f);
                    sy.animateToFinalPosition(0.93f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sx.animateToFinalPosition(1f);
                    sy.animateToFinalPosition(1f);
                    break;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            sheen.setShader(new LinearGradient(0, 0, 0, h, new int[]{dark ? 0x30ffffff : 0x66ffffff, 0x00ffffff, 0x00ffffff}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            setPivotX(w / 2f);
            setPivotY(h / 2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float r = getHeight() / 2f, inset = dp(0.5f);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRoundRect(rect, r, r, fill);
            canvas.drawRoundRect(rect, r, r, sheen);
            canvas.drawRoundRect(rect, r, r, rim);
        }
    }

    private class Row extends FrameLayout {
        final BackupImageView avatar;
        final TextView name;
        final CheckBox2 check;
        final View divider;
        final AvatarDrawable placeholder = new AvatarDrawable();
        final int card;

        Row(Context context) {
            super(context);
            card = dark ? 0xff2c2c2e : 0xffffffff;
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(21));
            addView(avatar, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.CENTER_VERTICAL, 30, 0, 0, 0));
            name = new TextView(context);
            name.setTextColor(dark ? 0xffffffff : 0xff000000);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 86, 0, 66, 0));
            check = new CheckBox2(context, 22, null);
            check.setColor(Theme.key_windowBackgroundWhiteBlackText, Theme.key_checkboxDisabled, Theme.key_windowBackgroundWhite);
            check.setDrawUnchecked(true);
            check.setDrawBackgroundAsArc(10);
            addView(check, LayoutHelper.createFrame(24, 24, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 32, 0));
            divider = new View(context);
            divider.setBackgroundColor(dark ? 0x22ffffff : 0x1a000000);
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.BOTTOM, 86, 0, 16, 0));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        }

        void bind(long dialogId, boolean first, boolean last) {
            final MessagesController mc = MessagesController.getInstance(account);
            TLObject peer = dialogId > 0 ? mc.getUser(dialogId) : mc.getChat(-dialogId);
            if (peer instanceof TLRPC.User) {
                placeholder.setInfo(account, (TLRPC.User) peer);
            } else if (peer instanceof TLRPC.Chat) {
                placeholder.setInfo(account, (TLRPC.Chat) peer);
            }
            avatar.setForUserOrChat(peer, placeholder);
            name.setText(DialogObject.getName(account, dialogId));
            check.setChecked(picked.contains(dialogId), false);
            divider.setVisibility(last ? GONE : VISIBLE);
            // one inset card: rounded at its top and bottom rows only
            final float r = dp(14);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(card);
            bg.setCornerRadii(new float[]{first ? r : 0, first ? r : 0, first ? r : 0, first ? r : 0, last ? r : 0, last ? r : 0, last ? r : 0, last ? r : 0});
            android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(bg, dp(16), 0, dp(16), 0);
            setBackground(inset);
        }
    }
}
