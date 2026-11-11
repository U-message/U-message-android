package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;

import java.util.ArrayList;
import java.util.List;

/**
 * iOS-style reveal of chat actions on a dialog row. The motion is a direct port of Telegram iOS
 * ItemListRevealMotion (springs and distances), computed in dp so the resistance curve matches.
 */
public class DialogSwipeReveal {

    public static class Action {
        public final int id;
        public final String title;
        public final int iconRes;
        public final int color;
        /** Removes the row: a commit slides the row fully off screen instead of closing it. */
        public final boolean fills;
        public final boolean destructive;

        public Action(int id, String title, int iconRes, int color, boolean fills, boolean destructive) {
            this.id = id;
            this.title = title;
            this.iconRes = iconRes;
            this.color = color;
            this.fills = fills;
            this.destructive = destructive;
        }
    }

    public interface Host {
        boolean canSwipe(DialogCell cell);
        /** Outermost action last for trailing, first for leading, as they appear on screen. */
        List<Action> getActions(DialogCell cell, boolean trailing);
        void onAction(long dialogId, DialogCell cell, Action action);
        boolean superDispatchTouchEvent(MotionEvent event);
    }

    private static final class Spring {
        double value, velocity, target, response = 0.5, damping = 1;

        Spring() {}

        Spring(double response, double damping) {
            this.response = response;
            this.damping = damping;
        }

        boolean atRest() {
            return Math.abs(value - target) < 0.001 && Math.abs(velocity) < 0.01;
        }

        void snap(double v) {
            value = v;
            target = v;
            velocity = 0;
        }

        void advance(double dt) {
            if (dt <= 0) {
                return;
            }
            if (atRest()) {
                snap(target);
                return;
            }
            final double w = 2 * Math.PI / Math.max(0.01, response);
            final double z = Math.max(0.01, damping);
            final double y = value - target, v = velocity;
            if (Math.abs(z - 1) < 0.00001) {
                final double e = Math.exp(-w * dt), k = v + w * y;
                value = target + (y + k * dt) * e;
                velocity = (v - w * k * dt) * e;
            } else {
                final double a = w * z, b = w * Math.sqrt(1 - z * z);
                final double e = Math.exp(-a * dt), c = Math.cos(b * dt), s = Math.sin(b * dt);
                value = target + e * (y * c + (v + a * y) / b * s);
                velocity = e * (v * c - (a * v + w * w * y) / b * s);
            }
        }
    }

    private static final double BUTTON = 50, SPACING = 10, EDGE = 10;
    private static final int EDGE_BAND_DP = 88;
    private static final double MAX_REVEAL_VELOCITY_DP = 1200;
    private static final int STATE_CLOSED = 0, STATE_DRAGGING = 1, STATE_SETTLING = 2, STATE_FILLING = 3;

    private final RecyclerView list;
    private final Host host;
    private final int touchSlop;

    private DialogCell cell;
    private long cellDialogId;
    private int direction; // -1: row moves left (trailing), 1: row moves right (leading)
    private final ArrayList<Action> actions = new ArrayList<>();
    private final ArrayList<Drawable> icons = new ArrayList<>();
    private final ArrayList<StaticLayout> titles = new ArrayList<>();

    private double widthDp;
    private int state = STATE_CLOSED;
    private boolean armed, continuingSpring;
    private double initial, confirmationAdjustment, gestureOriginAdjustment;
    private final Spring body = new Spring();
    private final Spring surface = new Spring(0.66, 0.95);
    private final Spring surfaceOpacity = new Spring(0.66, 0.95);
    private final Spring expansion = new Spring();
    private Spring[] scales = new Spring[0];
    private Spring[] opacities = new Spring[0];
    private double[] centers = new double[0];
    private double[] widths = new double[0];
    private double[] geomScale = new double[0];
    private double[] geomOpacity = new double[0];

    private static final int TOUCH_NONE = 0, TOUCH_PENDING = 1, TOUCH_DRAG = 2, TOUCH_OPEN = 3, TOUCH_OTHER = 4;
    private int touchMode = TOUCH_NONE;
    private int pointerId;
    private float downX, downY, dragStartX, lastX;
    private DialogCell candidate;
    private int pressedButton = -1;
    private VelocityTracker velocityTracker;

    private boolean ticking;
    private long lastFrameNanos;
    private final Runnable frame = this::onFrame;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();

    public DialogSwipeReveal(RecyclerView list, Host host) {
        this.list = list;
        this.host = host;
        this.touchSlop = ViewConfiguration.get(list.getContext()).getScaledTouchSlop();
        textPaint.setTextSize(dp(11.5f));
    }

    private static double toDp(float px) {
        return px / AndroidUtilities.density;
    }

    private static float toPx(double dpValue) {
        return (float) (dpValue * AndroidUtilities.density);
    }

    // region motion

    private int count() {
        return actions.size();
    }

    private boolean isDestructive() {
        final int primary = direction < 0 ? count() - 1 : 0;
        return primary >= 0 && primary < count() && actions.get(primary).destructive;
    }

    private double openDistance() {
        return count() == 0 ? 0 : 2 * EDGE + count() * BUTTON + (count() - 1) * SPACING;
    }

    private double confirmationDistance() {
        final double open = openDistance();
        return isDestructive() ? Math.max(open, widthDp - 29) : Math.min(widthDp, Math.max(open + 20, widthDp * 0.525));
    }

    private double armedDistance() {
        final double confirmation = confirmationDistance();
        return isDestructive() ? confirmation : Math.max(confirmation, widthDp * 0.75);
    }

    private double edgeZone() {
        return widthDp >= 414 ? 90 : (widthDp == 375 ? 75 : 50);
    }

    private double edgeFraction(double velocity) {
        return Math.min(widthDp >= 414 ? 0.75 : 0.8, Math.max(widthDp >= 414 ? 0.5 : 0.66, Math.abs(velocity) / 2500));
    }

    private double resisted(double distance, boolean inverse) {
        final double exponent = inverse ? 1 / 0.7 : 0.7;
        if (distance < 0) {
            return -Math.pow(-distance, exponent);
        }
        final double confirmation = confirmationDistance();
        if (distance > confirmation) {
            return confirmation + Math.pow(distance - confirmation, exponent);
        }
        return distance;
    }

    /** Fills the geometry arrays; index zero is the primary (outermost) button. */
    private void calculateGeometry(double reveal, double expansionValue) {
        final double distance = Math.max(0, reveal);
        final double consolidation = isDestructive() ? 0 : Math.min(1, Math.max(0, expansionValue));
        final double open = openDistance();
        final double confirmation = confirmationDistance();
        for (int index = 0; index < count(); index++) {
            final double outside = EDGE + index * (BUTTON + SPACING);
            final double end = outside + BUTTON + EDGE;
            final double start = end - BUTTON + 2 * SPACING;
            double scale = Math.min(1, Math.max(0, (distance - start) / Math.max(1, end - start)));
            double opacity = scale;
            double width = BUTTON;
            double center = outside + width / 2;
            if (index == 0) {
                final double stretched = width + Math.max(0, distance - open);
                final double expanded = Math.max(width, distance - 2 * EDGE);
                width = stretched + (expanded - stretched) * consolidation;
                center = EDGE + width / 2;
            }
            if (distance > open + 0.001) {
                if (index == 0) {
                    scale = 1;
                    opacity = 1;
                } else {
                    center += distance - open;
                    scale = 1 - consolidation;
                    opacity = (1 - Math.min(1, distance / Math.max(1, confirmation)) * 0.7) * (1 - consolidation);
                }
            }
            centers[index] = center;
            widths[index] = width;
            geomScale[index] = scale;
            geomOpacity[index] = opacity < 0.01 ? 0 : opacity;
        }
    }

    private void retargetButtons() {
        expansion.target = armed || state == STATE_FILLING ? 1 : 0;
        calculateGeometry(body.target, expansion.target);
        for (int i = 0; i < scales.length; i++) {
            scales[i].target = geomScale[i];
            opacities[i].target = geomOpacity[i];
            opacities[i].response = state == STATE_DRAGGING ? 0.25 : 0.5;
        }
    }

    private void begin() {
        final boolean settling = state == STATE_SETTLING && (Math.abs(body.value - body.target) > 0.5 || Math.abs(body.velocity) > 5);
        continuingSpring = settling;
        final double advance = state == STATE_FILLING ? 0 : armedDistance() - confirmationDistance();
        final double trackingPosition = body.value - advance * expansion.value;
        final double trackingTarget = body.target - advance * expansion.target;
        initial = resisted(trackingPosition, true);
        gestureOriginAdjustment = settling ? resisted(trackingTarget, true) - initial : 0;
        confirmationAdjustment = 0;
        armed = false;
        state = STATE_DRAGGING;
        surface.target = 1;
        surfaceOpacity.target = 1;
    }

    private void change(double translation, double velocity, double distanceFromCommitEdge) {
        double proposed = initial + translation + gestureOriginAdjustment + confirmationAdjustment;
        final double confirmation = confirmationDistance();
        boolean nowArmed = proposed >= confirmation;
        if (!nowArmed && proposed > confirmation * edgeFraction(velocity) && distanceFromCommitEdge < edgeZone()) {
            nowArmed = true;
            if (confirmationAdjustment == 0) {
                confirmationAdjustment = confirmation - proposed;
                proposed = confirmation;
            }
        }
        if (nowArmed != armed && cell != null) {
            try {
                cell.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
        }
        armed = nowArmed;
        if (armed) {
            continuingSpring = true;
        }
        final double target = resisted(proposed, false) + (armed ? armedDistance() - confirmation : 0);
        if (armed || continuingSpring) {
            body.target = target;
        } else {
            body.snap(target);
        }
        retargetButtons();
    }

    private void settle(double target, double velocity, boolean filling) {
        state = filling ? STATE_FILLING : STATE_SETTLING;
        armed = false;
        continuingSpring = false;
        body.target = target;
        final double remaining = target - body.value;
        body.velocity = Math.abs(remaining) > 0.000001 ? Math.min(100, Math.max(0, velocity / remaining)) * remaining : 0;
        surface.target = target > 0 ? 1 : 0;
        surfaceOpacity.target = surface.target;
        retargetButtons();
        startTicking();
    }

    private void advance(double dt) {
        if (state != STATE_DRAGGING || armed || continuingSpring) {
            body.advance(dt);
        }
        surface.advance(dt);
        surfaceOpacity.advance(dt);
        retargetButtons();
        expansion.advance(dt);
        for (int i = 0; i < scales.length; i++) {
            scales[i].advance(dt);
            opacities[i].advance(dt);
        }
    }

    private boolean atRest() {
        if (!body.atRest() || !surface.atRest() || !surfaceOpacity.atRest() || !expansion.atRest()) {
            return false;
        }
        for (int i = 0; i < scales.length; i++) {
            if (!scales[i].atRest() || !opacities[i].atRest()) {
                return false;
            }
        }
        return true;
    }

    private void startTicking() {
        if (!ticking) {
            ticking = true;
            lastFrameNanos = System.nanoTime();
            list.postOnAnimation(frame);
        }
    }

    private void onFrame() {
        ticking = false;
        if (cell == null) {
            return;
        }
        if (cell.getSwipeReveal() != this) {
            forget();
            return;
        }
        if (cell.getDialogId() != cellDialogId) {
            release();
            return;
        }
        final long now = System.nanoTime();
        final double dt = Math.min(0.05, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        advance(dt);
        cell.invalidate();
        if (atRest() && state != STATE_DRAGGING) {
            if (state == STATE_SETTLING && body.target <= 0) {
                release();
            }
            return;
        }
        startTicking();
    }

    private void release() {
        final DialogCell old = cell;
        forget();
        if (old != null && old.getSwipeReveal() == this) {
            old.setSwipeReveal(null);
            old.invalidate();
        }
    }

    /** Lets a row that is on its way closed finish by itself, so this one can serve another row. */
    private void handOff() {
        if (cell == null) {
            return;
        }
        final DialogSwipeReveal other = new DialogSwipeReveal(list, host);
        other.cell = cell;
        other.cellDialogId = cellDialogId;
        other.direction = direction;
        other.widthDp = widthDp;
        other.state = state;
        other.textPaint = textPaint;
        other.actions.addAll(actions);
        other.icons.addAll(icons);
        other.titles.addAll(titles);
        other.scales = scales;
        other.opacities = opacities;
        other.centers = centers;
        other.widths = widths;
        other.geomScale = geomScale;
        other.geomOpacity = geomOpacity;
        copySpring(body, other.body);
        copySpring(surface, other.surface);
        copySpring(surfaceOpacity, other.surfaceOpacity);
        copySpring(expansion, other.expansion);
        cell.setSwipeReveal(other);
        other.settle(0, 0, false);
        textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(dp(11.5f));
        forget();
    }

    private static void copySpring(Spring from, Spring to) {
        to.value = from.value;
        to.velocity = from.velocity;
        to.target = from.target;
    }

    private void forget() {
        cell = null;
        state = STATE_CLOSED;
        armed = false;
        continuingSpring = false;
        body.snap(0);
        surface.snap(0);
        surfaceOpacity.snap(0);
        expansion.snap(0);
        actions.clear();
        icons.clear();
        titles.clear();
        scales = new Spring[0];
        opacities = new Spring[0];
        pressedButton = -1;
    }

    private boolean setup(DialogCell target, boolean trailing) {
        final List<Action> list = host.getActions(target, trailing);
        if (list == null || list.isEmpty()) {
            return false;
        }
        if (cell != null && cell != target) {
            handOff();
        }
        cell = target;
        cellDialogId = target.getDialogId();
        direction = trailing ? -1 : 1;
        widthDp = toDp(target.getMeasuredWidth());
        actions.clear();
        // Geometry index zero is the outermost button.
        if (trailing) {
            for (int i = list.size() - 1; i >= 0; i--) {
                actions.add(list.get(i));
            }
        } else {
            actions.addAll(list);
        }
        final int n = actions.size();
        scales = new Spring[n];
        opacities = new Spring[n];
        centers = new double[n];
        widths = new double[n];
        geomScale = new double[n];
        geomOpacity = new double[n];
        icons.clear();
        titles.clear();
        textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        final int titleWidth = dp(60);
        for (int i = 0; i < n; i++) {
            scales[i] = new Spring(0.5, 0.7);
            opacities[i] = new Spring();
            final Action action = actions.get(i);
            final Drawable icon = ContextCompat.getDrawable(target.getContext(), action.iconRes).mutate();
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(0xffffffff, android.graphics.PorterDuff.Mode.SRC_IN));
            icons.add(icon);
            final CharSequence title = TextUtils.ellipsize(action.title, textPaint, titleWidth, TextUtils.TruncateAt.END);
            titles.add(new StaticLayout(title, textPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false));
        }
        target.setSwipeReveal(this);
        return true;
    }

    private void deliver(Action action, double velocity) {
        final DialogCell target = cell;
        final long dialogId = cellDialogId;
        if (action.fills) {
            settle(widthDp + 74, velocity, true);
            AndroidUtilities.runOnUIThread(() -> host.onAction(dialogId, target, action), 180);
            // The row normally goes away with the action; bring it back if it stays.
            AndroidUtilities.runOnUIThread(() -> {
                if (cell == target && state == STATE_FILLING && cellDialogId == dialogId) {
                    settle(0, 0, false);
                }
            }, 1400);
        } else {
            settle(0, velocity, false);
            host.onAction(dialogId, target, action);
        }
    }

    /** A recycled row can be rebound to another chat while revealed. */
    public boolean checkValid(DialogCell target) {
        if (cell != target || target.getDialogId() != cellDialogId) {
            if (cell == target) {
                release();
            }
            return false;
        }
        return true;
    }

    public void close() {
        if (cell != null && state != STATE_FILLING) {
            settle(0, 0, false);
        }
    }

    public void onCellDetached(DialogCell detached) {
        if (cell == detached) {
            touchMode = TOUCH_NONE;
            release();
        }
    }

    // endregion

    // region touch

    private DialogCell findCandidate(float x, float y) {
        final View child = list.findChildViewUnder(x, y);
        if (child instanceof DialogCell && host.canSwipe((DialogCell) child)) {
            return (DialogCell) child;
        }
        return null;
    }

    /** While a row is open every touch belongs to it, so paging must stay out. */
    public boolean hasOpenRow() {
        return cell != null && body.target > 0;
    }

    /**
     * Only a deliberate pull that starts near the wall the row moves away from reveals actions.
     * A fast flick, or a drag from the middle, stays with page switching.
     */
    private boolean isRevealGesture(float dx) {
        final float band = dp(EDGE_BAND_DP);
        final boolean nearWall = dx < 0 ? downX > list.getMeasuredWidth() - band : downX < band;
        if (!nearWall) {
            return false;
        }
        if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000);
            return Math.abs(toDp(velocityTracker.getXVelocity(pointerId))) < MAX_REVEAL_VELOCITY_DP;
        }
        return true;
    }

    private int buttonAt(float x, float y) {
        if (cell == null || y < cell.getTop() || y > cell.getBottom()) {
            return -1;
        }
        for (int i = 0; i < count(); i++) {
            final float half = toPx(widths[i] / 2 + SPACING / 2);
            final float center = direction < 0 ? cell.getMeasuredWidth() - toPx(centers[i]) : toPx(centers[i]);
            if (x >= center - half && x <= center + half) {
                return i;
            }
        }
        return -1;
    }

    /** @return true when the event was consumed and the list must not see it. */
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointerId = event.getPointerId(0);
            downX = event.getX();
            downY = event.getY();
            pressedButton = -1;
            if (velocityTracker != null) {
                velocityTracker.recycle();
            }
            velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(event);
            if (cell != null && state != STATE_FILLING) {
                if (downY >= cell.getTop() && downY <= cell.getBottom()) {
                    touchMode = TOUCH_OPEN;
                    pressedButton = buttonAt(downX, downY);
                    disallowParents();
                    if (pressedButton >= 0) {
                        cell.invalidate();
                    }
                    return true;
                }
                if (body.target > 0) {
                    // Another row was touched: close the open one. As on iOS this tap opens nothing,
                    // but a drag from here still swipes the touched row or scrolls the list.
                    close();
                    candidate = findCandidate(downX, downY);
                    touchMode = TOUCH_OTHER;
                    disallowParents();
                    return true;
                }
                handOff();
            }
            candidate = state == STATE_FILLING ? null : findCandidate(downX, downY);
            touchMode = candidate != null ? TOUCH_PENDING : TOUCH_NONE;
            return false;
        }
        if (touchMode == TOUCH_NONE) {
            return false;
        }
        final int index = event.findPointerIndex(pointerId);
        if (index < 0) {
            return touchMode >= TOUCH_DRAG;
        }
        final float x = event.getX(index), y = event.getY(index);
        if (velocityTracker != null) {
            velocityTracker.addMovement(event);
        }
        final float dx = x - downX, dy = y - downY;
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (touchMode == TOUCH_PENDING) {
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                        touchMode = TOUCH_NONE;
                        return false;
                    }
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        if (!isRevealGesture(dx) || list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || !host.canSwipe(candidate) || !setup(candidate, dx < 0)) {
                            touchMode = TOUCH_NONE;
                            return false;
                        }
                        cancelList(event);
                        disallowParents();
                        startDrag(x, y);
                        return true;
                    }
                    return false;
                } else if (touchMode == TOUCH_OTHER) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) >= Math.abs(dy)) {
                        if (candidate != null && isRevealGesture(dx) && host.canSwipe(candidate) && setup(candidate, dx < 0)) {
                            startDrag(x, y);
                        } else {
                            touchMode = TOUCH_NONE;
                        }
                    } else if (Math.abs(dy) > touchSlop) {
                        touchMode = TOUCH_NONE;
                        final MotionEvent down = MotionEvent.obtain(event);
                        down.setAction(MotionEvent.ACTION_DOWN);
                        host.superDispatchTouchEvent(down);
                        down.recycle();
                    }
                    return true;
                } else if (touchMode == TOUCH_OPEN) {
                    if (pressedButton >= 0 && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        pressedButton = -1;
                        cell.invalidate();
                    }
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) >= Math.abs(dy)) {
                        startDrag(x, y);
                    } else if (Math.abs(dy) > touchSlop) {
                        // Scrolling closes the row and carries on as a normal list scroll.
                        close();
                        touchMode = TOUCH_NONE;
                        final MotionEvent down = MotionEvent.obtain(event);
                        down.setAction(MotionEvent.ACTION_DOWN);
                        host.superDispatchTouchEvent(down);
                        down.recycle();
                    }
                    return true;
                } else {
                    updateDrag(x, y);
                    return true;
                }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return finishTouch(action == MotionEvent.ACTION_CANCEL);
            default:
                return touchMode >= TOUCH_DRAG;
        }
    }

    private void cancelList(MotionEvent event) {
        final MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        host.superDispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private void disallowParents() {
        final ViewGroup parent = (ViewGroup) list.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private double currentVelocity() {
        if (velocityTracker == null) {
            return 0;
        }
        velocityTracker.computeCurrentVelocity(1000);
        return toDp(velocityTracker.getXVelocity(pointerId)) * direction;
    }

    private void startDrag(float x, float y) {
        touchMode = TOUCH_DRAG;
        dragStartX = x;
        begin();
        updateDrag(x, y);
        startTicking();
    }

    private void updateDrag(float x, float y) {
        lastX = x;
        final double distance = direction < 0 ? toDp(x) : widthDp - toDp(x);
        change(toDp(x - dragStartX) * direction, currentVelocity(), distance);
        startTicking();
        if (cell != null) {
            cell.invalidate();
        }
    }

    private boolean finishTouch(boolean cancelled) {
        final int mode = touchMode;
        touchMode = TOUCH_NONE;
        final double velocity = currentVelocity();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        if (mode == TOUCH_DRAG && cell != null) {
            final boolean commit = !cancelled && armed;
            final double translation = toDp(lastX - dragStartX) * direction;
            final boolean open = commit || (Math.abs(velocity) > 50 ? velocity > 50 : initial + translation + gestureOriginAdjustment > openDistance() / 2);
            if (commit) {
                settle(openDistance(), velocity, false);
                deliver(actions.get(0), velocity);
            } else {
                settle(open ? openDistance() : 0, velocity, false);
            }
            return true;
        }
        if (mode == TOUCH_OTHER) {
            return true;
        }
        if (mode == TOUCH_OPEN) {
            final int pressed = pressedButton;
            pressedButton = -1;
            if (!cancelled && pressed >= 0 && pressed < count()) {
                deliver(actions.get(pressed), 0);
            } else {
                close();
            }
            return true;
        }
        return false;
    }

    // endregion

    // region drawing

    public float getOffset() {
        return direction * toPx(body.value);
    }

    /** Draws the buttons and the row's rounded surface, then leaves the canvas set up for the row. */
    public void beforeCellDraw(Canvas canvas, DialogCell target) {
        final int w = target.getMeasuredWidth(), h = target.getMeasuredHeight();
        final float offset = getOffset();
        drawButtons(canvas, w, h, offset);

        final float radius = Math.min(dp(26), h / 2f) * (float) Math.min(1, Math.max(0, surface.value));
        rect.set(offset, 0, w + offset, h);
        canvas.save();
        clipPath.rewind();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        final float surfaceAlpha = (float) Math.min(1, Math.max(0, surfaceOpacity.value));
        if (surfaceAlpha > 0) {
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            canvas.drawColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.07f * surfaceAlpha));
        }
        canvas.translate(offset, 0);
    }

    public void afterCellDraw(Canvas canvas) {
        canvas.restore();
    }

    private void drawButtons(Canvas canvas, int w, int h, float offset) {
        final float revealed = Math.abs(offset);
        if (revealed <= 0 || count() == 0) {
            return;
        }
        calculateGeometry(body.value, expansion.value);
        final int contentHeight = dp(50 + 4) + (titles.isEmpty() ? 0 : titles.get(0).getHeight());
        final float shapeTop = (h - contentHeight) / 2f;
        final float shapeHeight = dp(50);
        final float titleTop = shapeTop + shapeHeight + dp(4);

        canvas.save();
        if (direction < 0) {
            canvas.clipRect(w - revealed, 0, w, h);
        } else {
            canvas.clipRect(0, 0, revealed, h);
        }
        // Inner buttons first so the stretching primary covers them.
        for (int i = count() - 1; i >= 0; i--) {
            final float scale = (float) Math.max(0, scales[i].value);
            final float alpha = (float) Math.min(1, Math.max(0, opacities[i].value));
            if (scale <= 0.001f || alpha <= 0) {
                continue;
            }
            final float center = toPx(centers[i]);
            final float cx = direction < 0 ? w - center : center;
            final float halfWidth = toPx(widths[i]) / 2f;
            final float cy = shapeTop + shapeHeight / 2f;

            canvas.save();
            canvas.scale(scale, scale, cx, h / 2f);
            final int a = (int) (255 * alpha);

            rect.set(cx - halfWidth, shapeTop, cx + halfWidth, shapeTop + shapeHeight);
            fillPaint.setColor(actions.get(i).color);
            fillPaint.setAlpha(a);
            canvas.drawRoundRect(rect, shapeHeight / 2f, shapeHeight / 2f, fillPaint);
            if (i == pressedButton) {
                fillPaint.setColor(0x24000000);
                canvas.drawRoundRect(rect, shapeHeight / 2f, shapeHeight / 2f, fillPaint);
            }

            // When the primary stretches, its content moves next to the row, as on iOS.
            float contentX = cx;
            if (i == 0) {
                final float inner = direction < 0 ? cx - halfWidth + dp(25) : cx + halfWidth - dp(25);
                contentX = cx + (inner - cx) * (float) Math.min(1, Math.max(0, expansion.value));
            }
            final Drawable icon = icons.get(i);
            final int iconSize = dp(26);
            final int iconLeft = (int) (contentX - iconSize / 2f), iconTop = (int) (cy - iconSize / 2f);
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            icon.setAlpha(a);
            icon.draw(canvas);

            final StaticLayout title = titles.get(i);
            canvas.save();
            canvas.translate(contentX - title.getWidth() / 2f, titleTop);
            textPaint.setAlpha(a);
            title.draw(canvas);
            canvas.restore();

            canvas.restore();
        }
        canvas.restore();
    }

    // endregion
}
