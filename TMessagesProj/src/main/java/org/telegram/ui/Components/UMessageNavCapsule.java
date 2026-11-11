package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yandex.mapkit.directions.driving.Action;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * Floating blue capsule shown over every screen while in-app navigation runs in the background:
 * next maneuver, distance, street, current speed and the speed limit. It can expand with big
 * thumbs up / down when a road report needs an answer. Tapping the capsule reopens navigation.
 */
public class UMessageNavCapsule extends FrameLayout {

    public interface Delegate {
        void onOpen();

        void onAnswer(int vote);
    }

    private static UMessageNavCapsule instance;

    private final LinearLayout column;
    private final UMessageRouteUi.ManeuverView arrow;
    private final TextView distance;
    private final TextView street;
    private final TextView speed;
    private final GradientDrawable speedBg;
    private final FrameLayout limit;
    private final TextView limitText;
    private final LinearLayout promptRow;
    private final ImageView promptIcon;
    private final TextView promptText;
    private Delegate delegate;

    public static UMessageNavCapsule show(Activity activity, Delegate delegate) {
        if (activity == null) return null;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        if (instance == null || instance.getContext() != activity) {
            hide();
            instance = new UMessageNavCapsule(activity);
        }
        instance.delegate = delegate;
        if (instance.getParent() == null) {
            FrameLayout.LayoutParams lp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 10, 0, 10, 0);
            lp.topMargin = AndroidUtilities.statusBarHeight + dp(4);
            content.addView(instance, lp);
            instance.setAlpha(0f);
            instance.setTranslationY(-dp(24));
            instance.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        }
        return instance;
    }

    public static UMessageNavCapsule get() {
        return instance != null && instance.getParent() != null ? instance : null;
    }

    public static void hide() {
        if (instance == null) return;
        UMessageNavCapsule c = instance;
        instance = null;
        if (c.getParent() instanceof ViewGroup) {
            c.animate().alpha(0f).translationY(-dp(16)).setDuration(160).withEndAction(() -> {
                if (c.getParent() instanceof ViewGroup) ((ViewGroup) c.getParent()).removeView(c);
            }).start();
        }
    }

    private UMessageNavCapsule(Activity context) {
        super(context);
        setElevation(dp(30));

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff2b6cff, 0xff1f55e0});
        bg.setCornerRadius(dp(26));
        column.setBackground(bg);
        column.setElevation(dp(10));
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> {
            if (delegate != null) delegate.onOpen();
        });
        column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        arrow = new UMessageRouteUi.ManeuverView(context);
        row.addView(arrow, LayoutHelper.createLinear(30, 30, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        distance = UMessageRouteUi.text(context, 18, 0xffffffff, true);
        distance.setIncludeFontPadding(false);
        texts.addView(distance);
        street = UMessageRouteUi.text(context, 13, 0xd9ffffff, false);
        street.setIncludeFontPadding(false);
        texts.addView(street, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        speed = new TextView(context);
        speed.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        speed.setTypeface(AndroidUtilities.bold());
        speed.setTextColor(0xffffffff);
        speed.setGravity(Gravity.CENTER);
        speedBg = new GradientDrawable();
        speedBg.setShape(GradientDrawable.OVAL);
        speedBg.setColor(0xff141518);
        speed.setBackground(speedBg);
        row.addView(speed, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        limit = new FrameLayout(context);
        GradientDrawable lb = new GradientDrawable();
        lb.setShape(GradientDrawable.OVAL);
        lb.setColor(0xffffffff);
        lb.setStroke(dp(3.5f), 0xffe8312f);
        limit.setBackground(lb);
        limitText = new TextView(context);
        limitText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        limitText.setTypeface(AndroidUtilities.bold());
        limitText.setTextColor(0xff111111);
        limitText.setGravity(Gravity.CENTER);
        limit.addView(limitText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        row.addView(limit, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        // expandable "still there?" row with big thumbs
        promptRow = new LinearLayout(context);
        promptRow.setOrientation(LinearLayout.VERTICAL);
        promptRow.setVisibility(View.GONE);
        column.addView(promptRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        LinearLayout q = new LinearLayout(context);
        q.setOrientation(LinearLayout.HORIZONTAL);
        q.setGravity(Gravity.CENTER_VERTICAL);
        promptRow.addView(q, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        promptIcon = new ImageView(context);
        promptIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        q.addView(promptIcon, LayoutHelper.createLinear(56, 40, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        promptText = UMessageRouteUi.text(context, 16, 0xffffffff, true);
        promptText.setSingleLine(false);
        promptText.setMaxLines(2);
        q.addView(promptText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        promptRow.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 0, 0, 4, 0));
        buttons.addView(thumb(context, "👍", R.string.UMessageReportYes, UMessageRouteUi.GREEN, 1), LayoutHelper.createLinear(0, 56, 1f, 0, 0, 4, 0));
        buttons.addView(thumb(context, "👎", R.string.UMessageReportNo, 0xffe8262b, -1), LayoutHelper.createLinear(0, 56, 1f, 4, 0, 0, 0));
    }

    private TextView thumb(android.content.Context context, String emoji, int label, int color, int vote) {
        TextView b = new TextView(context);
        b.setText(emoji + "  " + LocaleController.getString(label));
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextColor(0xffffffff);
        b.setGravity(Gravity.CENTER);
        b.setBackground(UMessageRouteUi.rounded(color, dp(18)));
        b.setOnClickListener(v -> {
            showPrompt(null, null);
            if (delegate != null) delegate.onAnswer(vote);
        });
        UMessageRouteUi.pressScale(b);
        return b;
    }

    public void update(Action action, String dist, String streetName, int speedKmh, int limitKmh) {
        arrow.setAction(action);
        distance.setText(dist);
        street.setText(streetName);
        street.setVisibility(TextUtils.isEmpty(streetName) ? View.GONE : View.VISIBLE);
        speed.setText(String.valueOf(speedKmh));
        boolean over = limitKmh > 0 && speedKmh > limitKmh + 3;
        speedBg.setColor(over ? 0xffe8262b : 0xff141518);
        limit.setVisibility(limitKmh > 0 ? View.VISIBLE : View.GONE);
        limitText.setText(String.valueOf(limitKmh));
    }

    /** question == null hides the prompt row. */
    public void showPrompt(String question, android.graphics.Bitmap icon) {
        if (question == null) {
            promptRow.setVisibility(View.GONE);
            return;
        }
        promptText.setText(question);
        promptIcon.setImageBitmap(icon);
        promptRow.setVisibility(View.VISIBLE);
        promptRow.setAlpha(0f);
        promptRow.animate().alpha(1f).setDuration(180).start();
    }
}
