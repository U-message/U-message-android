package org.telegram.ui.Stories.recorder;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageMusic;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.DarkThemeResourceProvider;

import java.io.File;
import java.util.ArrayList;

/**
 * U message: TikTok-like sound picker for stories. Trending lists and search; tapping a song plays its
 * hook (the trending part) right away, "Use" downloads that part for the story.
 */
public class UMessageMusicSheet extends BottomSheet {

    public interface OnSelected {
        void onSelected(UMessageMusic.Track track, File file);
    }

    private static final int ACCENT = 0xfffe2c55;
    private static final int[] CATEGORY_NAMES = {
            R.string.UMessageMusicTrend, R.string.UMessageMusicTikTok, R.string.UMessageMusicRussian, R.string.UMessageMusicWorld
    };

    private final OnSelected onSelected;
    private final RecyclerListView listView;
    private final Adapter adapter = new Adapter();
    private final TextView emptyView;
    private final RadialProgressView loadingView;
    private final ArrayList<TextView> chips = new ArrayList<>();
    private final ArrayList<UMessageMusic.Track> tracks = new ArrayList<>();
    private int category = UMessageMusic.CATEGORY_TREND;
    private String query = "";
    private int requestId;
    private Runnable searchRunnable;

    private MediaPlayer player;
    private UMessageMusic.Track playingTrack;
    private boolean preparing;
    private UMessageMusic.Track downloadingTrack;

    public UMessageMusicSheet(Context context, Runnable onOpenFiles, OnSelected onSelected) {
        super(context, true, new DarkThemeResourceProvider());
        this.onSelected = onSelected;
        fixNavigationBar(0xff1c1c1e);
        setBackgroundColor(0xff1c1c1e);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        final FrameLayout header = new FrameLayout(context);
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.UMessageMusicTitle));
        title.setTextColor(0xffffffff);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        if (onOpenFiles != null) {
            final TextView files = new TextView(context);
            files.setText(LocaleController.getString(R.string.UMessageMusicFiles));
            files.setTextColor(0xb3ffffff);
            files.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            files.setGravity(Gravity.CENTER);
            files.setPadding(dp(12), 0, dp(12), 0);
            files.setBackground(Theme.createSelectorDrawable(0x20ffffff, Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            files.setOnClickListener(v -> {
                dismiss();
                onOpenFiles.run();
            });
            header.addView(files, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        }
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        final FrameLayout searchBox = new FrameLayout(context);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(12), 0x1fffffff));
        final ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(0x80ffffff, PorterDuff.Mode.SRC_IN));
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchBox.addView(searchIcon, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        final EditTextBoldCursor searchField = new EditTextBoldCursor(context);
        searchField.setBackground(null);
        searchField.setHint(LocaleController.getString(R.string.UMessageMusicSearch));
        searchField.setHintTextColor(0x80ffffff);
        searchField.setTextColor(0xffffffff);
        searchField.setCursorColor(ACCENT);
        searchField.setCursorWidth(1.5f);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(0, 0, dp(12), 0);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                if (searchRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                }
                AndroidUtilities.runOnUIThread(searchRunnable = UMessageMusicSheet.this::reload, query.isEmpty() ? 0 : 450);
                updateChips();
            }
        });
        searchBox.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 40, 0, 0, 0));
        root.addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 16, 0, 16, 0));

        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(12), 0, dp(12), 0);
        for (int i = 0; i < UMessageMusic.CATEGORY_COUNT; i++) {
            final int index = i;
            final TextView chip = new TextView(context);
            chip.setText((i == 0 ? "🔥 " : "") + LocaleController.getString(CATEGORY_NAMES[i]));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> {
                if (!query.isEmpty()) {
                    searchField.setText("");
                    AndroidUtilities.hideKeyboard(searchField);
                }
                if (category != index || !query.isEmpty()) {
                    category = index;
                    updateChips();
                    reload();
                }
            });
            ScaleStateListAnimator.apply(chip, 0.06f, 1.4f);
            chips.add(chip);
            chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 4, 0, 4, 0));
        }
        chipsScroll.addView(chipsRow);
        root.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32, 0, 12, 0, 6));
        updateChips();

        final FrameLayout listContainer = new FrameLayout(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        listView.setPadding(0, dp(4), 0, dp(12));
        listView.setClipToPadding(false);
        listView.setOnItemClickListener((view, position) -> togglePlay(tracks.get(position)));
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView = new TextView(context);
        emptyView.setTextColor(0x80ffffff);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        emptyView.setOnClickListener(v -> reload());
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 24, 0, 24, 0));
        loadingView = new RadialProgressView(context);
        loadingView.setProgressColor(ACCENT);
        listContainer.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.72f / AndroidUtilities.density)));

        setCustomView(root);
        reload();
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return !listView.canScrollVertically(-1);
    }

    private void updateChips() {
        for (int i = 0; i < chips.size(); i++) {
            final boolean selected = query.isEmpty() && i == category;
            final TextView chip = chips.get(i);
            chip.setTextColor(selected ? 0xff000000 : 0xccffffff);
            chip.setBackground(Theme.createRoundRectDrawable(dp(16), selected ? 0xffffffff : 0x1fffffff));
        }
    }

    private void reload() {
        final int id = ++requestId;
        tracks.clear();
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(View.GONE);
        loadingView.setVisibility(View.VISIBLE);
        final UMessageMusic.Callback callback = (result, error) -> {
            if (id != requestId) {
                return;
            }
            loadingView.setVisibility(View.GONE);
            tracks.clear();
            tracks.addAll(result);
            adapter.notifyDataSetChanged();
            listView.scrollToPosition(0);
            if (tracks.isEmpty()) {
                emptyView.setText(LocaleController.getString(error ? R.string.UMessageMusicError : R.string.UMessageMusicEmpty));
                emptyView.setVisibility(View.VISIBLE);
            }
        };
        if (query.isEmpty()) {
            UMessageMusic.load(category, callback);
        } else {
            UMessageMusic.search(query, callback);
        }
    }

    private void togglePlay(UMessageMusic.Track track) {
        if (track == playingTrack) {
            stopPlayer();
            adapter.notifyDataSetChanged();
            return;
        }
        stopPlayer();
        playingTrack = track;
        preparing = true;
        adapter.notifyDataSetChanged();
        UMessageMusic.queue.postRunnable(() -> {
            final String url = UMessageMusic.resolvePreview(track);
            AndroidUtilities.runOnUIThread(() -> {
                if (playingTrack != track) {
                    return;
                }
                if (TextUtils.isEmpty(url)) {
                    stopPlayer();
                    adapter.notifyDataSetChanged();
                    AndroidUtilities.shakeViewSpring(listView, 3);
                    org.telegram.ui.Components.BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageMusicUnavailable)).show();
                    return;
                }
                try {
                    final MediaPlayer mediaPlayer = new MediaPlayer();
                    player = mediaPlayer;
                    mediaPlayer.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build());
                    mediaPlayer.setDataSource(url);
                    mediaPlayer.setLooping(true);
                    mediaPlayer.setOnPreparedListener(mp -> {
                        if (player != mp) {
                            return;
                        }
                        preparing = false;
                        mp.start();
                        adapter.notifyDataSetChanged();
                    });
                    mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                        if (player == mp) {
                            stopPlayer();
                            adapter.notifyDataSetChanged();
                        }
                        return true;
                    });
                    mediaPlayer.prepareAsync();
                } catch (Exception e) {
                    FileLog.e(e);
                    stopPlayer();
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private void stopPlayer() {
        playingTrack = null;
        preparing = false;
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignore) {
            }
            player = null;
        }
    }

    private void use(UMessageMusic.Track track) {
        if (downloadingTrack != null) {
            return;
        }
        downloadingTrack = track;
        adapter.notifyDataSetChanged();
        UMessageMusic.downloadQueue.postRunnable(() -> {
            File file = null;
            try {
                file = UMessageMusic.download(track);
            } catch (Exception e) {
                FileLog.e(e);
            }
            final File result = file;
            AndroidUtilities.runOnUIThread(() -> {
                downloadingTrack = null;
                if (result == null) {
                    adapter.notifyDataSetChanged();
                    org.telegram.ui.Components.BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageMusicError)).show();
                    return;
                }
                stopPlayer();
                dismiss();
                onSelected.onSelected(track, result);
            });
        });
    }

    @Override
    public void dismissInternal() {
        stopPlayer();
        super.dismissInternal();
    }

    /** The hook as an audio message, the shape the story editor takes picked audio in. */
    public static MessageObject toMessageObject(int account, UMessageMusic.Track track, File file) {
        final TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = "";
        message.attachPath = file.getAbsolutePath();
        message.peer_id = new TLRPC.TL_peerUser();
        message.from_id = new TLRPC.TL_peerUser();
        message.date = (int) (System.currentTimeMillis() / 1000);
        final TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        final TLRPC.TL_document document = new TLRPC.TL_document();
        document.mime_type = file.getName().endsWith(".m4a") ? "audio/mp4" : "audio/mpeg";
        final TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.title = track.title;
        audio.performer = track.artist;
        audio.duration = UMessageMusic.readDuration(file) / 1000.0;
        document.attributes.add(audio);
        media.document = document;
        message.media = media;
        return new MessageObject(account, message, false, false);
    }

    private static String formatDuration(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new TrackCell(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((TrackCell) holder.itemView).set(tracks.get(position), position);
        }

        @Override
        public int getItemCount() {
            return tracks.size();
        }
    }

    private class TrackCell extends FrameLayout {

        private final BackupImageView coverView;
        private final PlayIndicator playIndicator;
        private final TextView rankView, titleView, subtitleView, useButton;
        private final RadialProgressView useProgress;
        private UMessageMusic.Track track;

        TrackCell(Context context) {
            super(context);
            setBackground(Theme.createSelectorDrawable(0x14ffffff, Theme.RIPPLE_MASK_ALL));

            rankView = new TextView(context);
            rankView.setTextColor(0x80ffffff);
            rankView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            rankView.setTypeface(AndroidUtilities.bold());
            rankView.setGravity(Gravity.CENTER);
            addView(rankView, LayoutHelper.createFrame(28, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 6, 0, 0, 0));

            coverView = new BackupImageView(context);
            coverView.setRoundRadius(dp(8));
            coverView.setBackground(Theme.createRoundRectDrawable(dp(8), 0x1fffffff));
            addView(coverView, LayoutHelper.createFrame(56, 56, Gravity.LEFT | Gravity.CENTER_VERTICAL, 36, 0, 0, 0));
            playIndicator = new PlayIndicator(context);
            addView(playIndicator, LayoutHelper.createFrame(56, 56, Gravity.LEFT | Gravity.CENTER_VERTICAL, 36, 0, 0, 0));

            titleView = new TextView(context);
            titleView.setTextColor(0xffffffff);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 104, 14, 96, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextColor(0x99ffffff);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 104, 38, 96, 0));

            useButton = new TextView(context);
            useButton.setText(LocaleController.getString(R.string.UMessageMusicUse));
            useButton.setTextColor(0xffffffff);
            useButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            useButton.setTypeface(AndroidUtilities.bold());
            useButton.setGravity(Gravity.CENTER);
            useButton.setPadding(dp(14), 0, dp(14), 0);
            useButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), ACCENT, 0xffd81f45));
            useButton.setOnClickListener(v -> {
                if (track != null) {
                    use(track);
                }
            });
            ScaleStateListAnimator.apply(useButton, 0.06f, 1.4f);
            addView(useButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 34, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            useProgress = new RadialProgressView(context);
            useProgress.setSize(dp(18));
            useProgress.setProgressColor(0xffffffff);
            addView(useProgress, LayoutHelper.createFrame(34, 34, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 30, 0));
        }

        void set(UMessageMusic.Track track, int position) {
            this.track = track;
            rankView.setText(query.isEmpty() ? String.valueOf(position + 1) : "");
            rankView.setTextColor(query.isEmpty() && position < 3 ? ACCENT : 0x80ffffff);
            titleView.setText(track.title);
            subtitleView.setText(track.artist + (track.duration > 0 ? " · " + formatDuration(track.duration) : ""));
            final android.graphics.Bitmap cached = UMessageMusic.getCachedCover(track.cover);
            coverView.setImageBitmap(cached);
            if (cached == null) {
                UMessageMusic.loadCover(track.cover, bitmap -> {
                    if (this.track == track) {
                        coverView.setImageBitmap(bitmap);
                    }
                });
            }
            final boolean playing = track == playingTrack;
            final boolean downloading = track == downloadingTrack;
            playIndicator.set(playing, playing && preparing);
            titleView.setTextColor(playing ? ACCENT : 0xffffffff);
            useButton.setVisibility(playing || downloading ? View.VISIBLE : View.GONE);
            useButton.setText(downloading ? "" : LocaleController.getString(R.string.UMessageMusicUse));
            useProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
            if (playing) {
                subtitleView.setText("🔥 " + LocaleController.getString(R.string.UMessageMusicHook) + " · " + track.artist);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(72), MeasureSpec.EXACTLY));
        }
    }

    /** Play glyph over the cover; animated equalizer while the hook plays. */
    private static class PlayIndicator extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final android.graphics.Path path = new android.graphics.Path();
        private boolean playing, loading;

        PlayIndicator(Context context) {
            super(context);
            paint.setColor(0xffffffff);
            dim.setColor(0x66000000);
        }

        void set(boolean playing, boolean loading) {
            this.playing = playing;
            this.loading = loading;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, dp(8), dp(8), dim);
            if (playing) {
                final float time = System.currentTimeMillis() / 1000f;
                for (int i = 0; i < 4; i++) {
                    final float x = cx + (i - 1.5f) * dp(7);
                    final float h = loading ? dp(3) : dp(3) + dp(9) * (float) (0.5 + 0.5 * Math.sin(time * 8 + i * 1.7));
                    rect.set(x - dp(2), cy - h, x + dp(2), cy + h);
                    canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                }
                invalidate();
            } else {
                path.reset();
                path.moveTo(cx - dp(6), cy - dp(8));
                path.lineTo(cx + dp(9), cy);
                path.lineTo(cx - dp(6), cy + dp(8));
                path.close();
                canvas.drawPath(path, paint);
            }
        }
    }
}
