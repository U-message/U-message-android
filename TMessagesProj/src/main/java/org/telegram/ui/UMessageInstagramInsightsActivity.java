package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagramBusiness;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** Instagram account numbers, a reach chart and the way to per-post statistics. */
public class UMessageInstagramInsightsActivity extends BaseFragment {

    private static final String[] PERIODS = {"day", "week", "days_28"};
    private static final int POST_STATS = 1;

    private UniversalRecyclerView listView;
    private int period = 1;
    private boolean loading;
    private String error;
    private JSONObject data;
    private int generation;
    private LinearLayout tiles;
    private ReachChart chart;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.UMessageInstagramInsights));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        tiles = new LinearLayout(context);
        tiles.setOrientation(LinearLayout.VERTICAL);
        tiles.setPadding(dp(16), dp(4), dp(16), dp(12));
        tiles.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        chart = new ReachChart(context);
        chart.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        final FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        load();
        return fragmentView = content;
    }

    private void load() {
        loading = true;
        error = null;
        final int current = ++generation;
        UMessageInstagramBusiness.getInstance(currentAccount).insights(PERIODS[period], (r, e) -> {
            if (current != generation || fragmentView == null) {
                return;
            }
            loading = false;
            error = e;
            data = e == null ? r : null;
            if (data != null) {
                updateTiles();
                chart.setSeries(data.optJSONArray("series"));
            }
            listView.adapter.update(false);
        });
        listView.adapter.update(false);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asSlideView(new String[]{
            getString(R.string.UMessageInstagramPeriodDay),
            getString(R.string.UMessageInstagramPeriodWeek),
            getString(R.string.UMessageInstagramPeriodMonth)
        }, period, chosen -> {
            if (chosen != period) {
                period = chosen;
                load();
            }
        }));
        if (data != null) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(getString(R.string.UMessageInstagramOverview)));
            items.add(UItem.asCustom(tiles));
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(getString(R.string.UMessageInstagramReach)));
            items.add(UItem.asCustom(chart));
            items.add(UItem.asShadow(chart.isEmpty() ? getString(R.string.UMessageInstagramNoData) : getString(R.string.UMessageInstagramChartHint)));
        } else {
            items.add(UItem.asShadow(loading ? getString(R.string.Loading) : UMessageInstagramBaseActivity.errorText(error)));
        }
        items.add(UItem.asButton(POST_STATS, R.drawable.msg_stats, getString(R.string.UMessageInstagramPostStats)));
        items.add(UItem.asShadow(getString(R.string.UMessageInstagramInsightsInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == POST_STATS) {
            presentFragment(new UMessageInstagramMediaActivity(UMessageInstagramMediaActivity.MODE_STATS));
        }
    }

    private void updateTiles() {
        final Context context = tiles.getContext();
        tiles.removeAllViews();
        final String[] labels = {
            getString(R.string.UMessageInstagramFollowers), getString(R.string.UMessageInstagramReach),
            getString(R.string.UMessageInstagramViews), getString(R.string.UMessageInstagramInteractions),
            getString(R.string.UMessageInstagramProfileViews)
        };
        final String[] keys = {"followers", "reach", "views", "interactions", "profileViews"};
        LinearLayout line = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(context);
                line.setOrientation(LinearLayout.HORIZONTAL);
                tiles.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            }
            final LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            final TextView value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            value.setTypeface(AndroidUtilities.bold());
            value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            value.setText(data.isNull(keys[i]) ? "—" : LocaleController.formatNumber(data.optLong(keys[i]), ' '));
            tile.addView(value);
            final TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            label.setText(labels[i]);
            tile.addView(label);
            line.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        if (keys.length % 2 == 1) {
            line.addView(new View(context), LayoutHelper.createLinear(0, 0, 1f));
        }
    }

    /** One series of daily reach as thin bars; a touch shows that day's exact value. */
    private final class ReachChart extends View {
        private static final int HEIGHT_DP = 190;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint();
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long[] times = new long[0];
        private long[] values = new long[0];
        private long max;
        private int selected = -1;

        ReachChart(Context context) {
            super(context);
            label.setTextSize(dp(12));
            setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(HEIGHT_DP)));
            setMinimumHeight(dp(HEIGHT_DP));
        }

        boolean isEmpty() {
            return values.length == 0;
        }

        void setSeries(JSONArray series) {
            final int count = series == null ? 0 : series.length();
            times = new long[count];
            values = new long[count];
            max = 0;
            selected = -1;
            for (int i = 0; i < count; i++) {
                final JSONObject point = series.optJSONObject(i);
                if (point != null) {
                    times[i] = UMessageInstagramBusiness.timeSeconds(point, "t");
                    values[i] = Math.max(0, point.optLong("reach"));
                    max = Math.max(max, values[i]);
                }
            }
            setContentDescription(getString(R.string.UMessageInstagramReach));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(HEIGHT_DP));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (values.length == 0) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                final float width = getWidth() - dp(32);
                final int index = (int) ((event.getX() - dp(16)) / width * values.length);
                selected = Math.max(0, Math.min(values.length - 1, index));
                invalidate();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int ink = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
            final int barColor = getThemedColor(Theme.key_featuredStickers_addButton);
            final float left = dp(16), right = getWidth() - dp(16);
            final float top = dp(30), bottom = getHeight() - dp(26);

            grid.setColor(getThemedColor(Theme.key_divider));
            grid.setStrokeWidth(Math.max(1, dp(0.5f)));
            canvas.drawLine(left, bottom, right, bottom, grid);
            if (values.length == 0) {
                return;
            }

            final float slot = (right - left) / values.length;
            final float gap = Math.min(dp(2), slot * 0.3f);
            final float barWidth = Math.max(dp(1), Math.min(slot - gap, dp(18)));
            for (int i = 0; i < values.length; i++) {
                final float h = max == 0 ? 0 : (bottom - top) * values[i] / max;
                final float x = left + slot * i + (slot - barWidth) / 2;
                bar.setColor(barColor);
                bar.setAlpha(selected == -1 || selected == i ? 255 : 110);
                final float r = Math.min(dp(4), barWidth / 2);
                rect.set(x, bottom - Math.max(h, r * 2), x + barWidth, bottom);
                if (values[i] > 0) {
                    // rounded at the data end, square on the baseline
                    canvas.drawRoundRect(rect, r, r, bar);
                    canvas.drawRect(x, bottom - Math.min(r, rect.height() / 2), x + barWidth, bottom, bar);
                }
            }

            label.setColor(ink);
            label.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(date(times[0]), left, getHeight() - dp(8), label);
            if (values.length > 1) {
                label.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(date(times[values.length - 1]), right, getHeight() - dp(8), label);
            }
            label.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            label.setTextAlign(Paint.Align.LEFT);
            final int shown = selected == -1 ? maxIndex() : selected;
            final String head = LocaleController.formatNumber(values[shown], ' ') + " · " + date(times[shown]);
            canvas.drawText(head, left, dp(16), label);
        }

        private int maxIndex() {
            int index = 0;
            for (int i = 1; i < values.length; i++) {
                if (values[i] > values[index]) {
                    index = i;
                }
            }
            return index;
        }

        private String date(long seconds) {
            return seconds == 0 ? "" : LocaleController.getInstance().getFormatterDayMonth().format(seconds * 1000);
        }
    }
}
