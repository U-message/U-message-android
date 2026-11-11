package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;

/**
 * U message: Instagram-like row of round effect thumbnails under the video message camera.
 * The item under the fixed center ring is the selected color filter or Beauty smoothing.
 */
@SuppressLint("ViewConstructor")
public class RoundEffectsCarousel extends RecyclerView {

    public interface Delegate {
        void onPresetSelected(int preset);
    }

    public static int getPresetCount() {
        return RoundVideoEffects.getPresetCount();
    }

    public static String getName(int preset) {
        return RoundVideoEffects.getPresetName(preset);
    }

    private final Delegate delegate;
    private final LinearLayoutManager layoutManager;
    private final android.util.SparseArray<ColorMatrixColorFilter> colorFilters = new android.util.SparseArray<>();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int itemWidth = dp(68);
    private Bitmap source;
    private int selected = -1;
    private boolean userScrolling;
    // U message: the Snap lens list arrives from the network; a selected lens gets its id once it is known
    private final Runnable lensesChanged = this::onLensesChanged;
    private String selectedLens;

    public RoundEffectsCarousel(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3));
        ringPaint.setColor(0xffffffff);
        ringPaint.setShadowLayer(dp(2), 0, 0, 0x40000000);

        source = createPlaceholder();

        setClipToPadding(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setHorizontalScrollBarEnabled(false);
        setItemAnimator(null);
        setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        setAdapter(new Adapter<ViewHolder>() {
            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                ThumbView view = new ThumbView(parent.getContext());
                view.setLayoutParams(new LayoutParams(itemWidth, ViewGroup.LayoutParams.MATCH_PARENT));
                return new ViewHolder(view) {};
            }

            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                final int preset = RoundVideoEffects.presetAt(position);
                ((ThumbView) holder.itemView).preset = preset;
                holder.itemView.setContentDescription(getName(preset));
                holder.itemView.invalidate();
            }

            @Override
            public int getItemCount() {
                return getPresetCount();
            }
        });
        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == SCROLL_STATE_DRAGGING) {
                    userScrolling = true;
                } else if (newState == SCROLL_STATE_IDLE) {
                    snapToCenter();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateChildren();
            }
        });
    }

    /** Settings can use the current theme accent; the camera keeps its white selection ring. */
    public void setSelectionColor(int color) {
        ringPaint.setColor(color);
        ringPaint.clearShadowLayer();
        invalidate();
    }

    /** Selects a preset without notifying the delegate. */
    public void setSelected(int preset) {
        selected = preset;
        selectedLens = RoundVideoEffects.getLensIdOf(preset);
        userScrolling = false;
        stopScroll();
        layoutManager.scrollToPositionWithOffset(RoundVideoEffects.positionOf(preset), 0);
        post(this::updateChildren);
    }

    private void onLensesChanged() {
        final Adapter<?> adapter = getAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (selected >= 0) {
            // the same lens may sit at another position now
            final int moved = selectedLens != null ? RoundVideoEffects.getPresetOfLens(selectedLens) : -1;
            if (moved >= 0) {
                selected = moved;
            }
            layoutManager.scrollToPositionWithOffset(RoundVideoEffects.positionOf(selected), 0);
            post(this::updateChildren);
            if (RoundVideoEffects.isSnapPreset(selected) && delegate != null) {
                delegate.onPresetSelected(selected);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SnapCameraKit.addListener(lensesChanged);
        // the list may have arrived while this view was off screen, under another page
        final Adapter<?> adapter = getAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        SnapCameraKit.removeListener(lensesChanged);
    }

    private ColorMatrixColorFilter getColorFilter(int preset) {
        if (RoundVideoEffects.isSnapPreset(preset)) {
            return null;
        }
        final int index = colorFilters.indexOfKey(preset);
        if (index >= 0) {
            return colorFilters.valueAt(index);
        }
        final float[] m = getThumbMatrix(preset);
        final ColorMatrixColorFilter filter = m != null ? new ColorMatrixColorFilter(m) : null;
        colorFilters.put(preset, filter);
        return filter;
    }

    /** Frame of the camera without effects, used for all thumbnails. */
    public void setSource(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        source = bitmap;
        for (int i = 0; i < getChildCount(); i++) {
            ((ThumbView) getChildAt(i)).updateShader();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        final int side = (w - itemWidth) / 2;
        setPadding(side, 0, side, 0);
        if (selected >= 0) {
            post(() -> {
                layoutManager.scrollToPositionWithOffset(RoundVideoEffects.positionOf(selected), 0);
                post(this::updateChildren);
            });
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && getAlpha() < 0.5f) {
            return false;
        }
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, dp(33), ringPaint);
    }

    private View findCenterChild() {
        final float center = getWidth() / 2f;
        View best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float d = Math.abs((child.getLeft() + child.getRight()) / 2f - center);
            if (d < bestDistance) {
                bestDistance = d;
                best = child;
            }
        }
        return best;
    }

    private void snapToCenter() {
        View child = findCenterChild();
        if (child == null) {
            return;
        }
        final int dx = (child.getLeft() + child.getRight()) / 2 - getWidth() / 2;
        if (dx != 0) {
            smoothScrollBy(dx, 0);
        } else {
            userScrolling = false;
        }
    }

    private void updateChildren() {
        final float center = getWidth() / 2f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float d = Math.min(1f, Math.abs((child.getLeft() + child.getRight()) / 2f - center) / itemWidth);
            float scale = 1f - 0.18f * d;
            child.setScaleX(scale);
            child.setScaleY(scale);
        }
        if (!userScrolling) {
            return;
        }
        View child = findCenterChild();
        if (child == null) {
            return;
        }
        final int position = getChildAdapterPosition(child);
        final int preset = position != NO_POSITION ? RoundVideoEffects.presetAt(position) : NO_POSITION;
        if (preset != NO_POSITION && preset != selected) {
            selected = preset;
            selectedLens = RoundVideoEffects.getLensIdOf(preset);
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
            if (delegate != null) {
                delegate.onPresetSelected(preset);
            }
        }
    }

    private static Bitmap createPlaceholder() {
        final int size = dp(56);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, 0, size, size, new int[]{0xff7fb2e6, 0xffe8b89a, 0xff6f8f5a}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, size, size, paint);
        return bitmap;
    }

    /** Thumbnail approximation of a preset: color filters are affine, so they map to a ColorMatrix exactly. */
    private static float[] getThumbMatrix(int preset) {
        if (preset == RoundVideoEffects.PRESET_ORIGINAL) {
            return null;
        }
        final RoundVideoEffects.Preset look = RoundVideoEffects.getPreset(preset);
        return affineToColorMatrix(c -> {
            final float[] out = applyFilter(look.filter, c);
            // A thumbnail cannot reproduce the bilateral blur, so Beauty gets only its subtle exposure lift here.
            final float lift = look.beauty / 100f * 0.045f;
            out[0] = out[0] * (1f - lift) + lift;
            out[1] = out[1] * (1f - lift) + lift;
            out[2] = out[2] * (1f - lift) + lift;
            return out;
        });
    }

    private interface Affine {
        float[] apply(float[] c);
    }

    private static float[] affineToColorMatrix(Affine f) {
        final float[] zero = f.apply(new float[]{0, 0, 0});
        final float[][] columns = {
                f.apply(new float[]{1, 0, 0}),
                f.apply(new float[]{0, 1, 0}),
                f.apply(new float[]{0, 0, 1})
        };
        final float[] m = new float[20];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                m[row * 5 + col] = columns[col][row] - zero[row];
            }
            m[row * 5 + 4] = zero[row] * 255f;
        }
        m[18] = 1f;
        return m;
    }

    // mirrors applyFilter() of RoundVideoEffects.GLSL without the final clamp
    private static float[] applyFilter(int type, float[] in) {
        float r = in[0], g = in[1], b = in[2];
        final float l = r * 0.299f + g * 0.587f + b * 0.114f;
        switch (type) {
            case 1: { // Clarendon
                r = (r - 0.5f) * 1.2f + 0.5f; g = (g - 0.5f) * 1.2f + 0.5f; b = (b - 0.5f) * 1.2f + 0.5f;
                r = l + (r - l) * 1.35f; g = l + (g - l) * 1.35f; b = l + (b - l) * 1.35f;
                r += -0.02f * (1 - l); g += 0.01f * (1 - l); b += 0.05f * (1 - l);
                break;
            }
            case 2: // Juno
                r = (l + (r - l) * 1.3f) * 1.08f; g = (l + (g - l) * 1.3f) * 1.02f; b = (l + (b - l) * 1.3f) * 0.9f;
                break;
            case 3: // Lark
                r = (l + (r * 1.08f + 0.03f - l) * 0.9f) * 0.98f;
                g = l + (g * 1.08f + 0.03f - l) * 0.9f;
                b = (l + (b * 1.08f + 0.03f - l) * 0.9f) * 1.04f;
                break;
            case 4: // Gingham
                r = (l + (r - l) * 0.75f) * 0.85f + 0.12f;
                g = (l + (g - l) * 0.75f) * 0.85f + 0.1f;
                b = (l + (b - l) * 0.75f) * 0.85f + 0.1f;
                break;
            case 5: // Valencia
                r = (r * 1.08f + 0.06f - 0.5f) * 1.05f + 0.5f;
                g = (g * 0.99f + 0.03f - 0.5f) * 1.05f + 0.5f;
                b = (b * 0.86f - 0.5f) * 1.05f + 0.5f;
                break;
            case 6: // Moon
                r = g = b = (l - 0.5f) * 1.3f + 0.55f;
                break;
            case 7: // Nashville
                r = (r + 0.1f - 0.5f) * 1.1f + 0.5f;
                g = (g * 0.95f + 0.04f - 0.5f) * 1.1f + 0.5f;
                b = (b * 0.85f + 0.08f - 0.5f) * 1.1f + 0.5f;
                break;
            case 8: { // Vintage
                final float sr = r * 0.393f + g * 0.769f + b * 0.189f;
                final float sg = r * 0.349f + g * 0.686f + b * 0.168f;
                final float sb = r * 0.272f + g * 0.534f + b * 0.131f;
                r += (sr - r) * 0.85f; g += (sg - g) * 0.85f; b += (sb - b) * 0.85f;
                break;
            }
        }
        return new float[]{r, g, b};
    }

    private class ThumbView extends View {

        int preset;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private Bitmap shaderBitmap;

        ThumbView(Context context) {
            super(context);
            setOnClickListener(v -> {
                final int dx = (getLeft() + getRight()) / 2 - RoundEffectsCarousel.this.getWidth() / 2;
                if (dx != 0) {
                    userScrolling = true;
                    smoothScrollBy(dx, 0);
                }
            });
        }

        void updateShader() {
            shaderBitmap = null;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // a Snap lens shows its own icon once downloaded, the camera frame until then
            final Bitmap icon = RoundVideoEffects.isSnapPreset(preset)
                    ? SnapCameraKit.getLensIcon(RoundVideoEffects.getSnapLensIndex(preset)) : null;
            final Bitmap bitmap = icon != null ? icon : source;
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            final float radius = dp(28);
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (shaderBitmap != bitmap) {
                shaderBitmap = bitmap;
                paint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            final float scale = radius * 2 / Math.min(bitmap.getWidth(), bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - bitmap.getWidth() * scale / 2f, cy - bitmap.getHeight() * scale / 2f);
            paint.getShader().setLocalMatrix(matrix);
            paint.setColorFilter(getColorFilter(preset));
            canvas.drawCircle(cx, cy, radius, paint);
        }
    }
}
