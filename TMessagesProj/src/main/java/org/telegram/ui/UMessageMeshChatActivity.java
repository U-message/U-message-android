package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UMessageMesh;
import org.telegram.messenger.UMessageMeshCrypto;
import org.telegram.messenger.UMessageMeshStore;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UMessageMeshCells;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

/** One offline conversation: bubbles with delivery status, text / photo / hold-to-record voice, safety number. */
public class UMessageMeshChatActivity extends BaseFragment {

    private static final int MENU_SAFETY = 1;
    private static final int MENU_DELETE = 2;
    private static final int REQUEST_PHOTO = 7483;
    private static final int REQUEST_MIC = 7482;
    private static final long GROUP_MS = 5 * 60_000L;

    private final long peerUid;
    private ArrayList<UMessageMeshStore.Message> messages = new ArrayList<>();
    private UMessageMeshStore.PeerKey key;

    private RecyclerView listView;
    private Adapter adapter;
    private EditTextBoldCursor input;
    private ImageView actionButton;
    private TextView recordingView;
    private LinearLayout banner;
    private View bannerShadow, bannerLine;
    private TextView bannerTitle, bannerText, bannerButton;
    private SimpleTextView titleView, subtitleView;
    private BackupImageView avatarView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private MessageDrawable inDrawable, outDrawable;
    private int actionButtonState = -1;

    private MediaRecorder recorder;
    private File recordFile;
    private long recordStart;
    private MediaPlayer player;
    private long playingRow = -1;

    private final LruCache<Long, Bitmap> bitmaps = new LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Runnable listener = this::reload;

    public UMessageMeshChatActivity(Bundle args) {
        super(args);
        peerUid = args == null ? 0 : args.getLong("uid");
    }

    @Override
    public boolean onFragmentCreate() {
        if (peerUid == 0) {
            return false;
        }
        UMessageMesh.addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        UMessageMesh.removeListener(listener);
        stopRecording(false);
        stopPlayback();
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        UMessageMesh.cancelNotification(peerUid);
        reload();
    }

    private long me() {
        return UserConfig.getInstance(currentAccount).getClientUserId();
    }

    @Override
    public View createView(Context context) {
        Theme.createChatResources(context, false);
        inDrawable = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, false, getResourceProvider());
        outDrawable = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, false, getResourceProvider());

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SAFETY) {
                    showSafetyNumber();
                } else if (id == MENU_DELETE) {
                    confirmDelete();
                }
            }
        });
        ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_SAFETY, R.drawable.msg_secret, LocaleController.getString(R.string.UMessageMeshSafetyNumber));
        other.addSubItem(MENU_DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.UMessageMeshDeleteChat));
        actionBar.addView(createAvatarContainer(context), 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 52, 0, 52, 0));

        SizeNotifierFrameLayout contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        root.addView(createBanner(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        bannerShadow = new View(context);
        bannerShadow.setBackgroundResource(R.drawable.header_shadow);
        bannerShadow.setVisibility(View.GONE);
        root.addView(bannerShadow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3));

        listView = new RecyclerView(context);
        LinearLayoutManager lm = new LinearLayoutManager(context);
        lm.setStackFromEnd(true);
        listView.setLayoutManager(lm);
        listView.setItemAnimator(null);
        listView.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(4));
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new Adapter());
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // gradient bubbles depend on their position on screen, like in ChatActivity
                if (outDrawable.hasGradient()) {
                    for (int i = 0; i < recyclerView.getChildCount(); i++) {
                        RecyclerView.ViewHolder h = recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
                        if (h instanceof Holder) {
                            ((Holder) h).bubble.invalidate();
                        }
                    }
                }
            }
        });
        root.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        root.addView(createInputPanel(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fragmentView = contentView;
        updateHeader();
        return fragmentView;
    }

    private View createAvatarContainer(Context context) {
        FrameLayout container = new FrameLayout(context);
        container.setPadding(0, actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0, 0, 0);
        container.setOnClickListener(v -> showSafetyNumber());

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(21));
        container.addView(avatarView, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        TLRPC.User user = getMessagesController().getUser(peerUid);
        if (user == null) {
            TLRPC.TL_user stub = new TLRPC.TL_user();
            stub.id = peerUid;
            stub.first_name = UMessageMesh.getName(currentAccount, peerUid);
            user = stub;
        }
        avatarDrawable.setInfo(currentAccount, user);
        avatarView.setForUserOrChat(user, avatarDrawable);

        titleView = new SimpleTextView(context);
        titleView.setTextSize(18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        titleView.setGravity(Gravity.LEFT);
        titleView.setEllipsizeByGradient(true);
        titleView.setText(UMessageMesh.getName(currentAccount, peerUid));
        container.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 58, -10, 8, 0));

        subtitleView = new SimpleTextView(context);
        subtitleView.setTextSize(14);
        subtitleView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        subtitleView.setGravity(Gravity.LEFT);
        subtitleView.setText(LocaleController.getString(R.string.UMessageMeshOffline));
        container.addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 58, 13, 8, 0));
        return container;
    }

    /** Key warnings in the style of Telegram's pinned / top panel. */
    private View createBanner(Context context) {
        banner = new LinearLayout(context);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackgroundColor(getThemedColor(Theme.key_chat_topPanelBackground));
        banner.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> showSafetyNumber());

        bannerLine = new View(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        bannerTitle = new TextView(context);
        bannerTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bannerTitle.setTypeface(AndroidUtilities.bold());
        bannerTitle.setSingleLine(true);
        bannerTitle.setEllipsize(TextUtils.TruncateAt.END);
        bannerTitle.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        column.addView(bannerTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        bannerText = new TextView(context);
        bannerText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        bannerText.setTextColor(getThemedColor(Theme.key_chat_topPanelMessage));
        bannerText.setMaxLines(3);
        bannerText.setEllipsize(TextUtils.TruncateAt.END);
        bannerText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        column.addView(bannerText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        bannerButton = new TextView(context);
        bannerButton.setText(LocaleController.getString(R.string.UMessageMeshKeyAccept));
        bannerButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bannerButton.setTypeface(AndroidUtilities.bold());
        bannerButton.setGravity(Gravity.CENTER);
        bannerButton.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        bannerButton.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_featuredStickers_addButton), 14));
        bannerButton.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        bannerButton.setOnClickListener(v -> UMessageMesh.acceptPendingKey(currentAccount, peerUid));

        if (LocaleController.isRTL) {
            banner.addView(bannerButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
            banner.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            banner.addView(bannerLine, LayoutHelper.createLinear(2, LayoutHelper.MATCH_PARENT, 10, 0, 0, 0));
        } else {
            banner.addView(bannerLine, LayoutHelper.createLinear(2, LayoutHelper.MATCH_PARENT, 0, 0, 10, 0));
            banner.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            banner.addView(bannerButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        }
        return banner;
    }

    private View createInputPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.BOTTOM);
        panel.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(4), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
        int iconColor = getThemedColor(Theme.key_chat_messagePanelIcons);

        LinearLayout field = new LinearLayout(context);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.BOTTOM);
        field.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(23), getThemedColor(Theme.key_chat_messagePanelBackground)));

        FrameLayout middle = new FrameLayout(context);
        input = new EditTextBoldCursor(context);
        input.setHint(LocaleController.getString(R.string.TypeMessage));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(getThemedColor(Theme.key_chat_messagePanelText));
        input.setHintTextColor(getThemedColor(Theme.key_chat_messagePanelHint));
        input.setCursorColor(getThemedColor(Theme.key_chat_messagePanelText));
        input.setCursorSize(AndroidUtilities.dp(20));
        input.setCursorWidth(1.5f);
        input.setBackground(null);
        input.setMaxLines(5);
        input.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(UMessageMesh.MAX_TEXT)});
        input.setPadding(0, AndroidUtilities.dp(11), 0, AndroidUtilities.dp(12));
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateActionButton();
            }
        });
        middle.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        recordingView = new TextView(context);
        recordingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        recordingView.setTextColor(getThemedColor(Theme.key_text_RedBold));
        recordingView.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        recordingView.setVisibility(View.GONE);
        middle.addView(recordingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ImageView attach = new ImageView(context);
        attach.setScaleType(ImageView.ScaleType.CENTER);
        attach.setImageResource(R.drawable.input_attach);
        attach.setColorFilter(iconColor);
        attach.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        attach.setContentDescription(LocaleController.getString(R.string.UMessageMeshPhoto));
        attach.setOnClickListener(v -> pickPhoto());

        if (LocaleController.isRTL) {
            field.addView(attach, LayoutHelper.createLinear(48, 46, Gravity.BOTTOM, 2, 0, 0, 0));
            field.addView(middle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        } else {
            field.addView(middle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
            field.addView(attach, LayoutHelper.createLinear(48, 46, Gravity.BOTTOM, 0, 0, 2, 0));
        }

        actionButton = new ImageView(context);
        actionButton.setScaleType(ImageView.ScaleType.CENTER);
        actionButton.setOnTouchListener((v, event) -> {
            if (input.length() > 0) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    sendText();
                }
                return true;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startRecording();
                    return true;
                case MotionEvent.ACTION_UP:
                    stopRecording(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopRecording(false);
                    return true;
            }
            return true;
        });
        // the send / mic button sits at the outer edge, like Telegram
        if (LocaleController.isRTL) {
            panel.addView(actionButton, LayoutHelper.createLinear(46, 46, Gravity.BOTTOM, 0, 0, 6, 0));
            panel.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM));
        } else {
            panel.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM));
            panel.addView(actionButton, LayoutHelper.createLinear(46, 46, Gravity.BOTTOM, 6, 0, 0, 0));
        }
        updateActionButton();
        return panel;
    }

    /** 0 = mic, 1 = send, 2 = recording. */
    private void updateActionButton() {
        if (actionButton == null) {
            return;
        }
        boolean hasText = input != null && input.length() > 0;
        int state = recorder != null ? 2 : hasText ? 1 : 0;
        actionButton.setImageResource(state == 1 ? R.drawable.ic_send : R.drawable.input_mic);
        if (state == 0) {
            actionButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(46), getThemedColor(Theme.key_chat_messagePanelBackground)));
            actionButton.setColorFilter(getThemedColor(Theme.key_chat_messagePanelIcons));
        } else {
            actionButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(46), getThemedColor(state == 2 ? Theme.key_chat_messagePanelVoiceBackground : Theme.key_chat_messagePanelSend)));
            actionButton.setColorFilter(0xFFFFFFFF);
        }
        actionButton.setContentDescription(LocaleController.getString(hasText ? R.string.Send : R.string.UMessageMeshHold));
        if (actionButtonState != -1 && actionButtonState != state) {
            float from = state == 2 ? 1.15f : 0.7f;
            actionButton.setScaleX(from);
            actionButton.setScaleY(from);
            actionButton.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        }
        actionButtonState = state;
    }

    /* Data */

    private void reload() {
        final long me = me();
        Utilities.globalQueue.postRunnable(() -> {
            UMessageMeshStore store = UMessageMeshStore.get();
            store.markRead(me, peerUid);
            ArrayList<UMessageMeshStore.Message> list = store.getMessages(me, peerUid, 300);
            UMessageMeshStore.PeerKey k = store.getKey(me, peerUid);
            AndroidUtilities.runOnUIThread(() -> {
                boolean atEnd = messages.size() != list.size();
                messages = list;
                key = k;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                    if (atEnd && !list.isEmpty()) {
                        listView.scrollToPosition(list.size() - 1);
                    }
                }
                updateHeader();
            });
        });
    }

    private void updateHeader() {
        if (banner == null) {
            return;
        }
        String name = UMessageMesh.getName(currentAccount, peerUid);
        if (key != null && key.hasPending()) {
            banner.setVisibility(View.VISIBLE);
            bannerText.setText(LocaleController.formatString(R.string.UMessageMeshKeyChanged, name));
            bannerButton.setVisibility(View.VISIBLE);
        } else if (key != null && !key.trusted()) {
            banner.setVisibility(View.VISIBLE);
            bannerText.setText(LocaleController.getString(R.string.UMessageMeshKeyUnknown));
            bannerButton.setVisibility(View.GONE);
        } else {
            banner.setVisibility(View.GONE);
        }
        boolean danger = key != null && key.hasPending();
        int lineColor = getThemedColor(danger ? Theme.key_text_RedBold : Theme.key_chat_topPanelLine);
        bannerLine.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(1), lineColor));
        bannerTitle.setText(LocaleController.getString(R.string.UMessageMeshSafetyNumber));
        bannerTitle.setTextColor(danger ? lineColor : getThemedColor(Theme.key_chat_topPanelTitle));
        bannerShadow.setVisibility(banner.getVisibility());
        if (titleView != null) {
            titleView.setText(name);
        }
        String subtitle = LocaleController.getString(R.string.UMessageMeshOffline);
        if (key != null && key.verified && !key.hasPending()) {
            subtitle += " · " + LocaleController.getString(R.string.UMessageMeshVerified);
        } else if (key != null && (!key.trusted() || key.hasPending())) {
            subtitle += " · " + LocaleController.getString(R.string.UMessageMeshUnverified);
        }
        if (subtitleView != null) {
            subtitleView.setText(subtitle);
        }
    }

    /* Sending */

    private void sendText() {
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        input.setText("");
        send(UMessageMeshStore.KIND_TEXT, text, null, 0);
    }

    private void send(int kind, String text, byte[] media, int duration) {
        if (key != null && !key.trusted() && !UMessageMesh.isUntrustedSendConfirmed(me(), peerUid) && getParentActivity() != null) {
            // nobody vouched for this key (heard over the mesh, not compared in person): ask before writing to it
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            b.setTitle(LocaleController.getString(R.string.UMessageMeshSendUntrustedTitle));
            b.setMessage(LocaleController.getString(R.string.UMessageMeshSendUntrustedInfo));
            b.setPositiveButton(LocaleController.getString(R.string.UMessageMeshSendAnyway), (d, w) -> {
                UMessageMesh.confirmUntrustedSend(currentAccount, peerUid);
                send(kind, text, media, duration);
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b.create());
            return;
        }
        UMessageMesh.send(currentAccount, peerUid, kind, text, media, duration, ok -> {
            if (!ok) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageMeshSendFailed)).show();
            }
        });
    }

    private void pickPhoto() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PHOTO);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PHOTO || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        Utilities.globalQueue.postRunnable(() -> {
            byte[] jpeg = compressPhoto(uri);
            AndroidUtilities.runOnUIThread(() -> {
                if (jpeg == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageMeshTooBig)).show();
                } else {
                    send(UMessageMeshStore.KIND_PHOTO, "", jpeg, 0);
                }
            });
        });
    }

    /** Down-scales and re-encodes until the JPEG fits the 150 KB cap (metadata such as location is dropped). */
    private static byte[] compressPhoto(Uri uri) {
        Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1280) {
                sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(is, null, d);
            }
            if (bitmap == null) {
                return null;
            }
            int side = 1280;
            for (int attempt = 0; attempt < 6; attempt++) {
                float scale = Math.min(1f, side / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
                Bitmap scaled = scale < 1f ? Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true) : bitmap;
                for (int q = 80; q >= 40; q -= 10) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, q, bo);
                    if (bo.size() <= UMessageMesh.MAX_PHOTO) {
                        return bo.toByteArray();
                    }
                }
                side = side * 3 / 4;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /* Voice */

    private final Runnable recordTimer = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) {
                return;
            }
            long sec = (SystemClock.elapsedRealtime() - recordStart) / 1000;
            recordingView.setText(LocaleController.formatString(R.string.UMessageMeshRecording, AndroidUtilities.formatShortDuration((int) sec)));
            AndroidUtilities.runOnUIThread(this, 250);
        }
    };

    private void startRecording() {
        Activity activity = getParentActivity();
        if (activity == null || recorder != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        try {
            stopPlayback();
            recordFile = new File(activity.getCacheDir(), "ummesh_rec.m4a");
            MediaRecorder r = new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(16000);
            r.setAudioChannels(1);
            r.setAudioEncodingBitRate(20000); // ~150 KB per minute, under the 200 KB cap
            r.setMaxDuration(UMessageMesh.MAX_VOICE_SEC * 1000);
            r.setOutputFile(recordFile.getAbsolutePath());
            r.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    AndroidUtilities.runOnUIThread(() -> stopRecording(true));
                }
            });
            r.prepare();
            r.start();
            recorder = r;
            recordStart = SystemClock.elapsedRealtime();
            input.setVisibility(View.INVISIBLE);
            recordingView.setVisibility(View.VISIBLE);
            updateActionButton();
            recordTimer.run();
        } catch (Throwable e) {
            FileLog.e(e);
            recorder = null;
        }
    }

    private void stopRecording(boolean send) {
        MediaRecorder r = recorder;
        if (r == null) {
            return;
        }
        recorder = null;
        AndroidUtilities.cancelRunOnUIThread(recordTimer);
        long durationMs = SystemClock.elapsedRealtime() - recordStart;
        boolean ok = true;
        try {
            r.stop();
        } catch (Throwable e) {
            ok = false; // too short: no data
        }
        try {
            r.release();
        } catch (Throwable ignore) {
        }
        if (input != null) {
            input.setVisibility(View.VISIBLE);
            recordingView.setVisibility(View.GONE);
            updateActionButton();
        }
        if (!send || !ok || durationMs < 1000 || recordFile == null) {
            return;
        }
        final File file = recordFile;
        final int seconds = (int) Math.min(UMessageMesh.MAX_VOICE_SEC, Math.max(1, Math.round(durationMs / 1000f)));
        Utilities.globalQueue.postRunnable(() -> {
            byte[] data = readFile(file, UMessageMesh.MAX_VOICE);
            AndroidUtilities.runOnUIThread(() -> {
                if (data == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageMeshTooBig)).show();
                } else {
                    send(UMessageMeshStore.KIND_VOICE, "", data, seconds);
                }
            });
        });
    }

    private static byte[] readFile(File file, int max) {
        try (FileInputStream is = new FileInputStream(file)) {
            if (file.length() > max || file.length() == 0) {
                return null;
            }
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = is.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return off == data.length ? data : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private void togglePlayback(UMessageMeshStore.Message m) {
        if (playingRow == m.rowId) {
            stopPlayback();
            adapter.notifyDataSetChanged();
            return;
        }
        stopPlayback();
        if (m.media == null || getParentActivity() == null) {
            return;
        }
        try {
            File file = new File(getParentActivity().getCacheDir(), "ummesh_play.m4a");
            try (FileOutputStream os = new FileOutputStream(file)) {
                os.write(m.media);
            }
            MediaPlayer p = new MediaPlayer();
            p.setDataSource(file.getAbsolutePath());
            p.setOnCompletionListener(mp -> {
                stopPlayback();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
            p.prepare();
            p.start();
            player = p;
            playingRow = m.rowId;
        } catch (Throwable e) {
            FileLog.e(e);
            stopPlayback();
        }
        adapter.notifyDataSetChanged();
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignore) {
            }
            player.release();
            player = null;
        }
        playingRow = -1;
    }

    @Override
    public void onPause() {
        super.onPause();
        stopRecording(false);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_MIC && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageMeshMic)).show();
        }
    }

    /* Menu */

    private void showSafetyNumber() {
        final long me = me();
        final String name = UMessageMesh.getName(currentAccount, peerUid);
        Utilities.globalQueue.postRunnable(() -> {
            String number = null, mine = null, theirs = null;
            UMessageMeshStore.PeerKey k = UMessageMeshStore.get().getKey(me, peerUid);
            try {
                UMessageMeshCrypto.Identity id = UMessageMeshCrypto.identity(me);
                mine = UMessageMeshCrypto.fingerprint(id.ed, id.x);
                if (k != null) {
                    number = UMessageMeshCrypto.safetyNumber(me, id.ed, id.x, peerUid, k.ed, k.x);
                    theirs = UMessageMeshCrypto.fingerprint(k.ed, k.x);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String fNumber = number, fMine = mine, fTheirs = theirs;
            AndroidUtilities.runOnUIThread(() -> {
                if (getParentActivity() == null || fNumber == null) {
                    return;
                }
                SpannableStringBuilder sb = new SpannableStringBuilder();
                int start = sb.length();
                sb.append(fNumber);
                sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.35f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n\n").append(LocaleController.formatString(R.string.UMessageMeshSafetyInfo, name));
                sb.append("\n\n").append(LocaleController.formatString(R.string.UMessageMeshYourKey, fMine));
                sb.append("\n").append(LocaleController.formatString(R.string.UMessageMeshTheirKey, name, fTheirs));
                boolean verified = k.verified;
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                b.setTitle(LocaleController.getString(R.string.UMessageMeshSafetyNumber));
                b.setMessage(sb);
                b.setPositiveButton(LocaleController.getString(verified ? R.string.UMessageMeshUnmarkVerified : R.string.UMessageMeshMarkVerified),
                        (dialog, which) -> UMessageMesh.markVerified(currentAccount, peerUid, !verified));
                b.setNegativeButton(LocaleController.getString(R.string.Close), null);
                showDialog(b.create());
            });
        });
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        final long me = me();
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.UMessageMeshDeleteChat));
        b.setMessage(LocaleController.getString(R.string.UMessageMeshDeleteConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> Utilities.globalQueue.postRunnable(() -> {
            UMessageMeshStore.get().deleteConversation(me, peerUid);
            AndroidUtilities.runOnUIThread(this::finishFragment);
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = b.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    /* List */

    private static boolean sameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    /** Consecutive messages from one side within a few minutes share a group: no tail, tighter spacing (Telegram). */
    private static boolean grouped(UMessageMeshStore.Message a, UMessageMeshStore.Message b) {
        return a != null && b != null && a.out == b.out && Math.abs(b.ts - a.ts) < GROUP_MS && sameDay(a.ts, b.ts);
    }

    /** Bubble container: draws Telegram's MessageDrawable and places time + ticks at the bottom end like ChatMessageCell. */
    private final class BubbleLayout extends ViewGroup {
        final TextView chip;
        final BackupImageView photo;
        final LinearLayout voice;
        final TextView text;
        final UMessageMeshCells.MetaView meta;
        boolean out, topNear, bottomNear, metaInline, photoOnly;
        View row;

        BubbleLayout(Context context) {
            super(context);
            setWillNotDraw(false);
            chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(2), AndroidUtilities.dp(7), AndroidUtilities.dp(2));
            addView(chip);
            photo = new BackupImageView(context);
            addView(photo);
            voice = new LinearLayout(context);
            voice.setOrientation(LinearLayout.HORIZONTAL);
            voice.setGravity(Gravity.CENTER_VERTICAL);
            addView(voice);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
            text.setTextIsSelectable(true);
            text.setLinkTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
            addView(text);
            meta = new UMessageMeshCells.MetaView(context);
            addView(meta);
        }

        private int padStart() {
            return AndroidUtilities.dp(photoOnly ? (out ? 3 : 9) : (out ? 11 : 17));
        }

        private int padEnd() {
            return AndroidUtilities.dp(photoOnly ? (out ? 9 : 3) : (out ? 17 : 11));
        }

        private int padTop() {
            return AndroidUtilities.dp(photoOnly ? 3 : 7);
        }

        private int padBottom() {
            return AndroidUtilities.dp(photoOnly ? 3 : 7);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxW = Math.min(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(420));
            int inner = Math.max(AndroidUtilities.dp(60), maxW - padStart() - padEnd());
            int spec = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST);
            int unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int w = 0, h = 0;
            View last = null;
            for (View v : new View[]{chip, photo, voice, text}) {
                if (v.getVisibility() == GONE) continue;
                if (v == photo) {
                    LayoutParams lp = photo.getLayoutParams();
                    int pw = Math.min(lp.width, inner);
                    int ph = lp.width == 0 ? 0 : lp.height * pw / lp.width;
                    photo.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY));
                } else {
                    v.measure(spec, unspec);
                }
                if (last != null) h += AndroidUtilities.dp(last == chip ? 4 : 6);
                w = Math.max(w, v.getMeasuredWidth());
                h += v.getMeasuredHeight();
                last = v;
            }
            meta.measure(unspec, unspec);
            int mw = meta.getMeasuredWidth(), mh = meta.getMeasuredHeight();
            metaInline = false;
            if (photoOnly) {
                // time sits on the photo
            } else if (last == text && text.getLayout() != null) {
                Layout l = text.getLayout();
                int line = l.getLineCount() - 1;
                float lastW = l.getLineWidth(line);
                if (l.getParagraphDirection(line) == Layout.DIR_LEFT_TO_RIGHT && lastW + AndroidUtilities.dp(8) + mw <= inner) {
                    metaInline = true;
                    w = Math.max(w, (int) Math.ceil(lastW) + AndroidUtilities.dp(8) + mw);
                } else {
                    w = Math.max(w, mw);
                    h += mh;
                }
            } else {
                w = Math.max(w, mw);
                h += mh + AndroidUtilities.dp(2);
            }
            setMeasuredDimension(w + padStart() + padEnd(), h + padTop() + padBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int x = padStart();
            int y = padTop();
            View last = null;
            for (View v : new View[]{chip, photo, voice, text}) {
                if (v.getVisibility() == GONE) continue;
                if (last != null) y += AndroidUtilities.dp(last == chip ? 4 : 6);
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                y += v.getMeasuredHeight();
                last = v;
            }
            int mw = meta.getMeasuredWidth(), mh = meta.getMeasuredHeight();
            int w = r - l, h = b - t;
            if (photoOnly) {
                int mx = photo.getRight() - mw - AndroidUtilities.dp(6);
                int my = photo.getBottom() - mh - AndroidUtilities.dp(6);
                meta.layout(mx, my, mx + mw, my + mh);
            } else {
                int mx = w - padEnd() - mw;
                int my = h - padBottom() - mh + (metaInline ? AndroidUtilities.dp(3) : AndroidUtilities.dp(2));
                meta.layout(mx, my, mx + mw, my + mh);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            MessageDrawable d = out ? outDrawable : inDrawable;
            if (d == null) return;
            int top = getTop() + (row != null ? row.getTop() : 0);
            int listW = listView != null ? listView.getMeasuredWidth() : getWidth();
            int listH = listView != null ? listView.getMeasuredHeight() : getHeight();
            d.setTop(top, listW, listH, topNear, bottomNear);
            d.setBounds(0, 0, getWidth(), getHeight());
            d.draw(canvas);
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final TextView date;
        final FrameLayout bubbleRow;
        final BubbleLayout bubble;
        final ImageView play;
        final TextView voiceTitle, voiceDuration;

        Holder(Context context) {
            super(new LinearLayout(context));
            LinearLayout root = (LinearLayout) itemView;
            root.setOrientation(LinearLayout.VERTICAL);
            root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            date = new TextView(context);
            date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            date.setTypeface(AndroidUtilities.bold());
            date.setGravity(Gravity.CENTER);
            date.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(3), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
            root.addView(date, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 6));

            bubbleRow = new FrameLayout(context);
            root.addView(bubbleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            bubble = new BubbleLayout(context);
            bubble.row = bubbleRow;
            bubbleRow.addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

            play = new ImageView(context);
            play.setScaleType(ImageView.ScaleType.CENTER);
            bubble.voice.addView(play, LayoutHelper.createLinear(44, 44));
            LinearLayout voiceText = new LinearLayout(context);
            voiceText.setOrientation(LinearLayout.VERTICAL);
            voiceTitle = new TextView(context);
            voiceTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            voiceTitle.setTypeface(AndroidUtilities.bold());
            voiceTitle.setSingleLine(true);
            voiceText.addView(voiceTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            voiceDuration = new TextView(context);
            voiceDuration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            voiceText.addView(voiceDuration, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            bubble.voice.addView(voiceText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 4, 0));
        }

        void bind(int position) {
            UMessageMeshStore.Message m = messages.get(position);
            UMessageMeshStore.Message prev = position > 0 ? messages.get(position - 1) : null;
            UMessageMeshStore.Message next = position + 1 < messages.size() ? messages.get(position + 1) : null;
            boolean out = m.out;
            boolean newDay = prev == null || !sameDay(prev.ts, m.ts);
            date.setVisibility(newDay ? View.VISIBLE : View.GONE);
            if (newDay) {
                date.setText(LocaleController.formatDateChat(m.ts / 1000));
                date.setTextColor(getThemedColor(Theme.key_chat_serviceText));
                date.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), getThemedColor(Theme.key_chat_serviceBackground)));
            }
            boolean topNear = !newDay && grouped(prev, m);
            boolean bottomNear = next != null && grouped(m, next);

            int textColor = getThemedColor(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn);
            int metaColor = getThemedColor(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText);
            bubble.out = out;
            bubble.topNear = topNear;
            bubble.bottomNear = bottomNear;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
            lp.gravity = Gravity.TOP | (out ? Gravity.RIGHT : Gravity.LEFT);
            lp.leftMargin = AndroidUtilities.dp(out ? 54 : 4);
            lp.rightMargin = AndroidUtilities.dp(out ? 4 : 54);
            lp.topMargin = AndroidUtilities.dp(topNear ? 1 : newDay ? 0 : 5);
            bubble.setLayoutParams(lp);

            bubble.chip.setVisibility(View.GONE);
            if (!out && m.unverified) {
                int red = getThemedColor(Theme.key_text_RedBold);
                bubble.chip.setVisibility(View.VISIBLE);
                bubble.chip.setText(LocaleController.getString(R.string.UMessageMeshUnverified));
                bubble.chip.setTextColor(red);
                bubble.chip.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(9), ColorUtils.setAlphaComponent(red, 0x22)));
            }

            BackupImageView photo = bubble.photo;
            photo.setVisibility(View.GONE);
            bubble.voice.setVisibility(View.GONE);
            photo.setOnClickListener(null);
            if (m.kind == UMessageMeshStore.KIND_PHOTO && m.media != null) {
                Bitmap bm = bitmaps.get(m.rowId);
                if (bm == null) {
                    bm = BitmapFactory.decodeByteArray(m.media, 0, m.media.length);
                    if (bm != null) {
                        bitmaps.put(m.rowId, bm);
                    }
                }
                if (bm != null) {
                    photo.setImageBitmap(bm);
                    photo.setRoundRadius(AndroidUtilities.dp(Math.max(4, SharedConfig.bubbleRadius - 3)));
                    ViewGroup.LayoutParams pl = photo.getLayoutParams();
                    if (pl == null) {
                        pl = new ViewGroup.LayoutParams(0, 0);
                    }
                    pl.width = AndroidUtilities.dp(240);
                    pl.height = Math.max(AndroidUtilities.dp(100), Math.min(AndroidUtilities.dp(320), pl.width * bm.getHeight() / Math.max(1, bm.getWidth())));
                    photo.setLayoutParams(pl);
                    photo.setVisibility(View.VISIBLE);
                    final Bitmap full = bm;
                    photo.setOnClickListener(v -> showPhoto(full));
                }
            } else if (m.kind == UMessageMeshStore.KIND_VOICE) {
                bubble.voice.setVisibility(View.VISIBLE);
                play.setImageResource(playingRow == m.rowId ? R.drawable.ic_pauseinline : R.drawable.ic_playinline);
                play.setColorFilter(getThemedColor(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble));
                play.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(44), getThemedColor(out ? Theme.key_chat_outLoader : Theme.key_chat_inLoader)));
                voiceTitle.setTextColor(textColor);
                voiceTitle.setText(LocaleController.getString(R.string.UMessageMeshVoice));
                voiceDuration.setTextColor(metaColor);
                voiceDuration.setText(AndroidUtilities.formatShortDuration(m.duration));
                bubble.voice.setOnClickListener(v -> togglePlayback(m));
            }
            boolean hasText = !TextUtils.isEmpty(m.text);
            bubble.text.setVisibility(hasText ? View.VISIBLE : View.GONE);
            bubble.text.setText(hasText ? m.text : "");
            bubble.text.setTextColor(textColor);
            bubble.photoOnly = photo.getVisibility() == View.VISIBLE && !hasText && bubble.chip.getVisibility() == View.GONE;

            String time = android.text.format.DateFormat.getTimeFormat(itemView.getContext()).format(new Date(m.ts));
            int status = UMessageMeshCells.Ticks.NONE;
            int tickColor = metaColor;
            if (out) {
                status = m.status >= UMessageMeshStore.STATUS_DELIVERED ? UMessageMeshCells.Ticks.DELIVERED
                        : m.status == UMessageMeshStore.STATUS_SENT ? UMessageMeshCells.Ticks.SENT : UMessageMeshCells.Ticks.QUEUED;
                tickColor = getThemedColor(status == UMessageMeshCells.Ticks.QUEUED ? Theme.key_chat_outSentClock
                        : status == UMessageMeshCells.Ticks.DELIVERED ? Theme.key_chat_outSentCheckRead : Theme.key_chat_outSentCheck);
            }
            if (bubble.photoOnly) {
                int media = getThemedColor(Theme.key_chat_mediaTimeText);
                bubble.meta.set(time, media, status, media, true, getThemedColor(Theme.key_chat_mediaTimeBackground));
            } else {
                bubble.meta.set(time, metaColor, status, tickColor, false, 0);
            }
            if (out) {
                String s = LocaleController.getString(status == UMessageMeshCells.Ticks.DELIVERED ? R.string.UMessageMeshDelivered
                        : status == UMessageMeshCells.Ticks.SENT ? R.string.UMessageMeshSent : R.string.UMessageMeshQueued);
                bubble.meta.setContentDescription(time + ", " + s);
            } else {
                bubble.meta.setContentDescription(time);
            }
            bubble.requestLayout();
            bubble.invalidate();
        }
    }

    private void showPhoto(Bitmap bitmap) {
        if (getParentActivity() == null) {
            return;
        }
        ImageView view = new ImageView(getParentActivity());
        view.setAdjustViewBounds(true);
        view.setImageBitmap(bitmap);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setView(view);
        b.setPositiveButton(LocaleController.getString(R.string.Close), null);
        showDialog(b.create());
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }
    }
}
