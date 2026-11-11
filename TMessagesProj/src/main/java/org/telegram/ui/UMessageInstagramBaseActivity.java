package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagramBusiness;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;

/** Shared parts of the Instagram screens: a paged server list with loading, empty and error states. */
abstract class UMessageInstagramBaseActivity extends BaseFragment {

    protected final ArrayList<JSONObject> rows = new ArrayList<>();
    protected String nextCursor;
    protected boolean loading;
    protected boolean loadedOnce;
    protected String loadError;
    private int generation;

    protected LinearLayout root;
    protected FrameLayout listFrame;
    protected RecyclerListView listView;
    protected LinearLayoutManager layoutManager;
    private TextView emptyView;
    private RadialProgressView progressView;

    protected UMessageInstagramBusiness api() {
        return UMessageInstagramBusiness.getInstance(currentAccount);
    }

    /** User-facing text for a server/network error code. */
    static String errorText(String error) {
        if (error == null) return getString(R.string.UnknownError);
        switch (error) {
            case UMessageInstagramBusiness.ERROR_NOT_CONFIGURED: return getString(R.string.UMessageInstagramNotConfigured);
            case "not_connected": return getString(R.string.UMessageInstagramNotConnected);
            case "token_expired":
            case "reconnect": return getString(R.string.UMessageInstagramReconnect);
            case UMessageInstagramBusiness.ERROR_WINDOW_CLOSED: return getString(R.string.UMessageInstagramWindowClosed);
            case UMessageInstagramBusiness.ERROR_NETWORK: return getString(R.string.WaitingForNetwork);
            case "rate_limited": return getString(R.string.UMessageInstagramRateLimited);
            case "publish_limit": return getString(R.string.UMessageInstagramPublishLimit);
            case "permission_denied": return getString(R.string.UMessageInstagramPermissionDenied);
            case "too_big": return getString(R.string.UMessageInstagramTooBig);
            case "bad_format":
            case "bad_mime":
            case "bad_media": return getString(R.string.UMessageInstagramBadFormat);
            case "timeout": return getString(R.string.UMessageInstagramPublishTimeout);
            case "cancelled": return getString(R.string.UMessageInstagramPublishCancelled);
            case "blocked": return getString(R.string.UMessageBlockedTitle);
            case "bad_token": return getString(R.string.UMessageUnameBadToken);
            default: return getString(R.string.UMessageInstagramGenericError);
        }
    }

    protected abstract CharSequence title();

    protected abstract void request(String cursor, UMessagePremiumController.Callback callback);

    protected abstract View createRow(Context context);

    protected abstract void bindRow(View view, JSONObject row, int position);

    protected abstract CharSequence emptyText();

    protected void onRowClick(JSONObject row, View view) {
    }

    /** A chat list: position 0 is the bottom. */
    protected boolean reversed() {
        return false;
    }

    /** A reload adds to what is shown instead of replacing it (older pages stay). */
    protected boolean mergeOnReload() {
        return false;
    }

    /** Turns one server item into the rows it shows. */
    protected void addRows(JSONObject item, ArrayList<JSONObject> out) {
        out.add(item);
    }

    protected void sortRows(ArrayList<JSONObject> rows) {
    }

    protected void onPageLoaded(JSONObject result) {
    }

    protected void onLoadFailed(String error) {
    }

    protected void addBottomViews(Context context, LinearLayout root) {
    }

    protected void addTopViews(Context context, LinearLayout root) {
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        addTopViews(context, root);

        listFrame = new FrameLayout(context);
        root.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        layoutManager = new LinearLayoutManager(context, RecyclerView.VERTICAL, reversed());
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                final View view = createRow(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                bindRow(holder.itemView, rows.get(position), position);
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                onRowClick(rows.get(position), view);
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (nextCursor != null && !loading && layoutManager.findLastVisibleItemPosition() >= rows.size() - 5) {
                    load(nextCursor);
                }
            }
        });
        listFrame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        progressView = new RadialProgressView(context, getResourceProvider());
        progressView.setVisibility(View.GONE);
        listFrame.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        addBottomViews(context, root);
        fragmentView = root;
        updateState();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    protected void reload() {
        load(null);
    }

    private void load(String cursor) {
        if (loading && cursor != null) {
            return;
        }
        loading = true;
        final int current = ++generation;
        updateState();
        request(cursor, (r, error) -> {
            if (current != generation || fragmentView == null) {
                return;
            }
            loading = false;
            if (error != null || r == null) {
                loadError = error;
                onLoadFailed(error);
                updateState();
                return;
            }
            loadError = null;
            loadedOnce = true;
            final ArrayList<JSONObject> page = new ArrayList<>();
            final JSONArray items = r.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject item = items.optJSONObject(i);
                    if (item != null) {
                        addRows(item, page);
                    }
                }
            }
            final boolean keepNext = cursor == null && mergeOnReload() && !rows.isEmpty();
            if (cursor == null && !mergeOnReload()) {
                rows.clear();
            }
            final HashSet<String> ids = new HashSet<>();
            for (JSONObject row : rows) {
                ids.add(row.optString("id"));
            }
            for (JSONObject row : page) {
                if (ids.add(row.optString("id"))) {
                    rows.add(row);
                }
            }
            sortRows(rows);
            if (!keepNext) {
                nextCursor = emptyToNull(r.optString("next", null));
                if (r.isNull("next")) {
                    nextCursor = null;
                }
            }
            onPageLoaded(r);
            final RecyclerView.Adapter<?> adapter = listView.getAdapter();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateState();
        });
    }

    private static String emptyToNull(String s) {
        return TextUtils.isEmpty(s) ? null : s;
    }

    private void updateState() {
        if (emptyView == null) {
            return;
        }
        final boolean showEmpty = !loading && rows.isEmpty();
        progressView.setVisibility(loading && rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (showEmpty) {
            emptyView.setText(loadError != null ? errorText(loadError) : emptyText());
        }
    }

    // ---------- Small views ----------

    protected TextView text(Context context, int sizeDp, int colorKey) {
        final TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(getThemedColor(colorKey));
        return t;
    }

    /** A remote picture (http) in a rounded view; an empty url leaves a neutral placeholder. */
    protected void setRemoteImage(BackupImageView view, String url, int placeholderSizeDp) {
        final GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        placeholder.setCornerRadius(AndroidUtilities.dp(placeholderSizeDp));
        if (TextUtils.isEmpty(url)) {
            view.setImageDrawable(placeholder);
        } else {
            view.setImage(url, null, placeholder);
        }
    }

    protected static String formatTime(JSONObject row) {
        final long time = UMessageInstagramBusiness.timeSeconds(row, "time");
        return time == 0 ? "" : LocaleController.stringForMessageListDate(time);
    }

    /** One-line composer used by the direct thread and the comment reply. */
    protected final class Composer extends LinearLayout {
        public final EditTextBoldCursor editText;
        public final ImageView sendButton;

        public Composer(Context context, int hintRes, int maxLength, Runnable onSend) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

            editText = new EditTextBoldCursor(context);
            editText.setBackground(null);
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            editText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
            editText.setHint(getString(hintRes));
            editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            editText.setMaxLines(4);
            editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLength)});
            editText.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10));
            addView(editText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            sendButton = new ImageView(context);
            sendButton.setScaleType(ImageView.ScaleType.CENTER);
            sendButton.setImageResource(R.drawable.ic_send);
            sendButton.setColorFilter(getThemedColor(Theme.key_featuredStickers_addButton));
            sendButton.setContentDescription(getString(R.string.Send));
            sendButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
            sendButton.setOnClickListener(v -> onSend.run());
            addView(sendButton, LayoutHelper.createLinear(52, 48));
        }

        public String textTrimmed() {
            return editText.getText() == null ? "" : editText.getText().toString().trim();
        }
    }
}
