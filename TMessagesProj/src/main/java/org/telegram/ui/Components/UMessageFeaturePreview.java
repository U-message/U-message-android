package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.RenderEffect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.util.ArrayList;

/**
 * Looping, non-interactive demo of one U message switch, built from real chat bubbles and the
 * user's own chats. It replays the scenario for the current switch state, so flipping the switch
 * shows the difference right away.
 */
public class UMessageFeaturePreview extends FrameLayout {

    private static final String PEER_FALLBACK = "Aziza";

    private final int account;
    private final String key;
    private final Theme.ResourcesProvider resourcesProvider;
    private final ArrayList<Runnable> pending = new ArrayList<>();
    private final ArrayList<Chat> chats = new ArrayList<>();
    private boolean on;
    private boolean wallpaper;
    private int nextMessageId = 1;

    private FrameLayout stage;
    private LinearLayout messages;
    private AnimatedTextView statusView;
    private TextView inputText;
    private LinearLayout rows;
    private FrameLayout header;

    /** One of the user's real chats, used to fill the chat list demos. */
    private static class Chat {
        TLObject object;
        String name;
        CharSequence text;
        int date;
        int unread;
    }

    public UMessageFeaturePreview(Context context, int account, String key, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.account = account;
        this.key = key;
        this.resourcesProvider = resourcesProvider;
        Theme.createChatResources(context, false);
        setWillNotDraw(false);
        loadChats();
    }

    public void setFeatureEnabled(boolean enabled) {
        if (on == enabled && stage != null) {
            return;
        }
        on = enabled;
        if (isAttachedToWindow()) {
            restart();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(340), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelPending();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (wallpaper) {
            final Drawable drawable = Theme.getCachedWallpaperNonBlocking();
            if (drawable != null) {
                StoryEntry.drawBackgroundDrawable(canvas, drawable, getWidth(), getHeight());
            } else {
                canvas.drawColor(color(Theme.key_windowBackgroundGray));
            }
        } else {
            canvas.drawColor(color(Theme.key_windowBackgroundWhite));
        }
    }

    /* Scenario engine */

    private void later(long delay, Runnable runnable) {
        final Runnable wrapped = new Runnable() {
            @Override
            public void run() {
                pending.remove(this);
                runnable.run();
            }
        };
        pending.add(wrapped);
        AndroidUtilities.runOnUIThread(wrapped, delay);
    }

    private void cancelPending() {
        for (Runnable runnable : pending) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
        }
        pending.clear();
    }

    private void restart() {
        cancelPending();
        removeAllViews();
        messages = null;
        statusView = null;
        inputText = null;
        rows = null;
        header = null;
        stage = new FrameLayout(getContext());
        addView(stage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final long end = play();
        later(end + 2600, this::restart);
        invalidate();
    }

    /** Builds the scene for {@link #key} and schedules its steps; returns when the last step runs. */
    private long play() {
        switch (key) {
            case UMessageConfig.KEY_GHOST_MODE: return playGhostMode();
            case UMessageConfig.KEY_GHOST_BUTTON: return playGhostButton();
            case UMessageConfig.KEY_HIDDEN_CHATS: return playHiddenChats();
            case UMessageConfig.KEY_SAVE_EDITED: return playSaveEdited();
            case UMessageConfig.KEY_SAVE_DELETED: return playSaveDeleted();
            case UMessageConfig.KEY_SAVE_VIEW_ONCE: return playSaveViewOnce();
            case UMessageConfig.KEY_STRANGER_PROTECTION: return playStranger();
            case UMessageConfig.KEY_BLOCK_APK: return playBlockApk();
            case UMessageConfig.KEY_FOLDER_ICONS: return playFolderIcons();
            case UMessageConfig.KEY_HIDE_FOLDER_TABS: return playHideFolderTabs();
            case UMessageConfig.KEY_ADMIN_FOLDERS: return playAdminFolders();
            case UMessageConfig.KEY_STORIES_ANONYMOUS: return playStoriesAnonymous();
            case UMessageConfig.KEY_STORIES_HIDDEN: return playStoriesHidden();
            case UMessageConfig.KEY_STORIES_DOWNLOAD: return playStoriesDownload();
            case UMessageConfig.KEY_UNREAD_REMINDER: return playReminder();
            case UMessageConfig.KEY_AUTO_REPLY: return playAutoReply();
            case UMessageConfig.KEY_CONFIRM_STICKER: return playConfirm(R.string.UMessageConfirmStickerAsk, "😎", false);
            case UMessageConfig.KEY_CONFIRM_GIF: return playConfirm(R.string.UMessageConfirmGifAsk, "🎉", false);
            case UMessageConfig.KEY_CONFIRM_VOICE: return playConfirm(R.string.UMessageConfirmVoiceAsk, null, true);
            case UMessageConfig.KEY_VOICE_INPUT: return playVoiceInput();
            case UMessageConfig.KEY_ROUND_FRONT_CAMERA: return playFrontCamera();
            case UMessageConfig.KEY_AUTO_APPROVE: return playAutoApprove();
            case UMessageConfig.KEY_STOP_AUTODOWNLOAD: return playStopAutoDownload();
            case UMessageConfig.KEY_BLOCK_ADS: return playBlockAds();
            case UMessageConfig.KEY_UM_PREMIUM: return playPremium();
            case UMessageConfig.KEY_PROXY_FALLBACK: return playProxy();
            case UMessageConfig.KEY_NEARBY_SHARE: return playNearbyShare();
            case PrivacyGuardSettings.KEY_OWNER_AWAY: return playGuardOwnerAway();
            case PrivacyGuardSettings.KEY_PROTECT_MEDIA: return playGuardMedia();
            case PrivacyGuardSettings.KEY_PROTECT_COMPOSER: return playGuardComposer();
            case PrivacyGuardSettings.KEY_PROTECT_SCREENSHOTS: return playGuardScreenshots();
        }
        return 0;
    }

    /* Scenarios */

    private long playGhostMode() {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        chatScene(self, UserObject.getUserName(self), LocaleController.getString(on ? R.string.Lately : R.string.Online), false);
        toast(LocaleController.getString(R.string.UMessagePreviewFriendScreen), 0, 1800);
        final TLRPC.TL_message hi = textMessage(true, LocaleController.getString(R.string.UMessagePreviewHi));
        hi.unread = true;
        final ChatMessageCell[] cell = new ChatMessageCell[1];
        later(700, () -> cell[0] = addMessage(hi));
        if (!on) {
            later(2000, () -> {
                hi.unread = false;
                replaceMessage(cell[0], hi);
            });
            later(2600, () -> statusView.setText(LocaleController.getString(R.string.Typing), true));
        }
        later(4000, () -> {
            addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewReply)));
            if (!on) {
                statusView.setText(LocaleController.getString(R.string.Online), true);
            }
        });
        return 4000;
    }

    private long playGhostButton() {
        final ImageView[] ghost = new ImageView[1];
        listScene(false, 0, icons -> {
            if (on) {
                ghost[0] = addHeaderIcon(icons, R.drawable.ghost);
            }
        });
        if (on) {
            later(1000, () -> {
                pulse(ghost[0]);
                ghost[0].setColorFilter(new PorterDuffColorFilter(color(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
                toast(LocaleController.getString(R.string.UMessageGhostModeOn), 0, 1600);
            });
            later(3200, () -> {
                pulse(ghost[0]);
                ghost[0].setColorFilter(new PorterDuffColorFilter(color(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
                toast(LocaleController.getString(R.string.UMessageGhostModeOff), 0, 1600);
            });
        }
        return 4800;
    }

    private long playHiddenChats() {
        final ImageView[] hide = new ImageView[1];
        listScene(false, 0, icons -> {
            hide[0] = addHeaderIcon(icons, R.drawable.msg_archive_hide);
            hide[0].setVisibility(GONE);
        });
        final int index = Math.min(1, rows.getChildCount() - 1);
        later(1000, () -> {
            setRowSelected(rows.getChildAt(index), true);
            if (on) {
                hide[0].setVisibility(VISIBLE);
            }
        });
        if (on) {
            later(2000, () -> pulse(hide[0]));
            later(2400, () -> {
                rows.removeViewAt(index);
                hide[0].setVisibility(GONE);
                toast(LocaleController.formatPluralString("UMessageChatsHidden", 1), 0, 1800);
            });
        } else {
            later(2600, () -> setRowSelected(rows.getChildAt(index), false));
        }
        return 4200;
    }

    private long playSaveEdited() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        final String original = LocaleController.getString(R.string.UMessagePreviewMeet);
        final TLRPC.TL_message message = textMessage(false, original);
        final ChatMessageCell[] cell = new ChatMessageCell[1];
        later(600, () -> cell[0] = addMessage(message));
        later(2000, () -> {
            message.message = LocaleController.getString(R.string.UMessagePreviewMeetEdited);
            message.flags |= TLRPC.MESSAGE_FLAG_EDITED;
            message.edit_date = message.date + 60;
            replaceMessage(cell[0], message);
        });
        if (on) {
            later(2900, () -> addChip(LocaleController.formatString(R.string.UMessagePreviewOriginal, original)));
        }
        return 4200;
    }

    private long playSaveDeleted() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        final TLRPC.TL_message secret = textMessage(false, LocaleController.getString(R.string.UMessagePreviewSecret));
        final ChatMessageCell[] cell = new ChatMessageCell[1];
        later(500, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewHi))));
        later(1200, () -> cell[0] = addMessage(secret));
        later(2800, () -> {
            if (on) {
                final MessageObject object = createMessageObject(secret);
                object.umPreviewDeleted = true;
                cell[0].setMessageObject(object, null, false, false, false);
                cell[0].requestLayout();
                cell[0].invalidate();
            } else {
                messages.removeView(cell[0]);
            }
            toast(LocaleController.getString(R.string.UMessagePreviewDeletedBySender), 0, 1800);
        });
        return 4400;
    }

    private long playSaveViewOnce() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        final ChatMessageCell[] cell = new ChatMessageCell[1];
        later(500, () -> cell[0] = addMessage(textMessage(false, "📷 " + LocaleController.getString(R.string.AttachDestructingPhoto))));
        later(2200, () -> {
            final String text = on
                    ? "📷 " + LocaleController.getString(R.string.AttachPhoto) + " · " + LocaleController.getString(R.string.UMessageViewOnceLabel)
                    : "📷 " + LocaleController.getString(R.string.AttachPhotoExpired);
            cell[0].setMessageObject(createMessageObject(textMessage(false, text)), null, false, false, false);
            cell[0].requestLayout();
            cell[0].invalidate();
            if (on) {
                toast(LocaleController.getString(R.string.UMessageViewOnceKept), 0, 1800);
            }
        });
        return 4200;
    }

    private long playStranger() {
        listScene(false, 0, null);
        final Chat stranger = new Chat();
        stranger.name = "+998 90 123 45 67";
        stranger.text = LocaleController.getString(R.string.UMessagePreviewStranger);
        stranger.date = (int) (System.currentTimeMillis() / 1000);
        stranger.unread = 1;
        final View[] row = new View[1];
        later(900, () -> {
            row[0] = createRow(stranger);
            rows.addView(row[0], 0);
        });
        if (on) {
            later(2500, () -> {
                rows.removeView(row[0]);
                toast(LocaleController.getString(R.string.UMessagePreviewMovedToStrangers), 0, 1800);
            });
        }
        return 4200;
    }

    private long playBlockApk() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        later(500, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewApkText))));
        later(1500, () -> {
            if (on) {
                toast(LocaleController.getString(R.string.UMessagePreviewApkBlocked), 0, 1800);
            } else {
                addMessage(documentMessage(false, "U-message-mod.apk", "application/vnd.android.package-archive", 24 * 1024 * 1024));
            }
        });
        return 3600;
    }

    private long playFolderIcons() {
        final LinearLayout[] tabs = new LinearLayout[1];
        listScene(false, 1, null);
        tabs[0] = (LinearLayout) stage.findViewWithTag("tabs");
        if (on) {
            later(1300, () -> {
                final int count = tabs[0].getChildCount();
                for (int i = 0; i < count; i++) {
                    final TextView tab = (TextView) tabs[0].getChildAt(i);
                    final Drawable icon = getContext().getResources().getDrawable(FOLDER_ICONS[i % FOLDER_ICONS.length]).mutate();
                    icon.setColorFilter(new PorterDuffColorFilter(tab.getCurrentTextColor(), PorterDuff.Mode.SRC_IN));
                    tab.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                        tab.setText(null);
                        tab.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null);
                        tab.animate().alpha(1f).setDuration(200).start();
                    }).start();
                }
            });
        }
        return 3400;
    }

    private long playHideFolderTabs() {
        listScene(false, 1, null);
        final View tabs = stage.findViewWithTag("tabsScroll");
        if (on) {
            later(1300, () -> collapse(tabs));
        }
        return 3400;
    }

    private long playAdminFolders() {
        listScene(false, 1, null);
        final LinearLayout tabs = (LinearLayout) stage.findViewWithTag("tabs");
        final HorizontalScrollView scroll = (HorizontalScrollView) stage.findViewWithTag("tabsScroll");
        if (on) {
            final int[] names = {R.string.UMessageFolderMyGroups, R.string.UMessageFolderMyChannels, R.string.UMessageFolderAdminGroups, R.string.UMessageFolderAdminChannels};
            for (int i = 0; i < names.length; i++) {
                final int name = names[i];
                later(1000 + i * 450L, () -> {
                    tabs.addView(createTab(LocaleController.getString(name), false));
                    scroll.post(() -> scroll.smoothScrollTo(tabs.getWidth(), 0));
                });
            }
        }
        return 3600;
    }

    private long playStoriesAnonymous() {
        listScene(true, 0, null);
        final LinearLayout stories = (LinearLayout) stage.findViewWithTag("stories");
        final int index = Math.min(1, stories.getChildCount() - 1);
        final View story = stories.getChildAt(index);
        later(1000, () -> pulse(story));
        later(1500, () -> {
            ((StoryCircle) story.getTag()).setViewed();
            toast(LocaleController.getString(on ? R.string.UMessagePreviewStoryHidden : R.string.UMessagePreviewStoryVisible), 0, 2000);
        });
        return 3800;
    }

    private long playStoriesHidden() {
        listScene(true, 0, null);
        final View stories = stage.findViewWithTag("storiesRow");
        if (on) {
            later(1300, () -> collapse(stories));
        }
        return 3400;
    }

    private long playStoriesDownload() {
        wallpaper = false;
        final Chat owner = chats.isEmpty() ? null : chats.get(0);
        final FrameLayout story = new FrameLayout(getContext());
        final GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff5b8def, 0xffb461e8, 0xfff0718c});
        background.setCornerRadius(dp(14));
        story.setBackground(background);
        stage.addView(story, LayoutHelper.createFrame(200, LayoutHelper.MATCH_PARENT, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 14));

        final View progress = new View(getContext()) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final long start = System.currentTimeMillis();

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final float p = Math.min(1f, (System.currentTimeMillis() - start) / 4200f);
                paint.setColor(0x66ffffff);
                AndroidUtilities.rectTmp.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(1), dp(1), paint);
                paint.setColor(0xffffffff);
                AndroidUtilities.rectTmp.set(0, 0, getWidth() * p, getHeight());
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(1), dp(1), paint);
                if (p < 1f) {
                    invalidate();
                }
            }
        };
        story.addView(progress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.TOP, 8, 8, 8, 0));

        final BackupImageView avatar = createAvatar(owner != null ? owner.object : null, owner != null ? owner.name : PEER_FALLBACK, 28);
        story.addView(avatar, LayoutHelper.createFrame(28, 28, Gravity.TOP | Gravity.LEFT, 10, 18, 0, 0));
        final TextView name = text(owner != null ? owner.name : PEER_FALLBACK, 13, 0xffffffff, true);
        story.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 44, 23, on ? 40 : 10, 0));

        if (on) {
            final ImageView download = new ImageView(getContext());
            download.setImageResource(R.drawable.msg_download);
            download.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
            download.setScaleType(ImageView.ScaleType.CENTER);
            story.addView(download, LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.RIGHT, 0, 14, 4, 0));
            later(1500, () -> pulse(download));
            later(1900, () -> toast(LocaleController.getString(R.string.UMessagePreviewSaved), 0, 1800));
        }
        return 4200;
    }

    private long playReminder() {
        listScene(false, 0, null);
        final Chat chat = firstPrivateChat();
        if (on) {
            final LinearLayout banner = new LinearLayout(getContext());
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(dp(12), dp(10), dp(12), dp(10));
            banner.setBackground(Theme.createRoundRectDrawable(dp(16), color(Theme.key_dialogBackground)));
            banner.setElevation(dp(6));
            banner.addView(createAvatar(chat.object, chat.name, 36), LayoutHelper.createLinear(36, 36));
            final LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(LocaleController.formatString(R.string.UMessageReminderTitle, chat.name), 14, color(Theme.key_dialogTextBlack), true));
            texts.addView(text(chat.text != null ? chat.text : LocaleController.getString(R.string.UMessagePreviewHi), 13, color(Theme.key_dialogTextGray2), false));
            banner.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
            banner.setTranslationY(-dp(120));
            stage.addView(banner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 10, 8, 10, 0));
            later(1500, () -> banner.animate().translationY(0).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start());
            later(4200, () -> banner.animate().translationY(-dp(120)).setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_IN).start());
        }
        return 4600;
    }

    private long playAutoReply() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        later(600, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewBusy))));
        if (on) {
            later(1800, () -> addMessage(textMessage(true, UMessageConfig.getAutoReplyText())));
        }
        return 3600;
    }

    private long playConfirm(int askRes, String emoji, boolean voice) {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), true);
        final ImageView trigger = (ImageView) stage.findViewWithTag(voice ? "mic" : "smile");
        final Runnable send = () -> addMessage(voice
                ? voiceMessage()
                : textMessage(true, emoji));
        later(900, () -> pulse(trigger));
        if (on) {
            final View[] card = new View[1];
            final TextView[] sendButton = new TextView[1];
            later(1300, () -> {
                card[0] = createConfirmCard(LocaleController.getString(askRes), sendButton);
                stage.addView(card[0], LayoutHelper.createFrame(260, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
                card[0].setAlpha(0f);
                card[0].setScaleX(0.9f);
                card[0].setScaleY(0.9f);
                card[0].animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
            });
            later(2700, () -> pulse(sendButton[0]));
            later(3000, () -> {
                card[0].animate().alpha(0f).setDuration(160).start();
                send.run();
            });
            return 4200;
        }
        later(1300, send);
        return 3000;
    }

    private long playVoiceInput() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), true);
        if (!on) {
            return 2500;
        }
        final ImageView dictation = (ImageView) stage.findViewWithTag("dictation");
        final String phrase = LocaleController.getString(R.string.UMessagePreviewDictation);
        later(900, () -> {
            pulse(dictation);
            dictation.setColorFilter(new PorterDuffColorFilter(color(Theme.key_chat_messagePanelSend), PorterDuff.Mode.SRC_IN));
            inputText.setTextColor(color(Theme.key_chat_messagePanelText));
            inputText.setText("");
        });
        final long start = 1300;
        final long step = 45;
        for (int i = 1; i <= phrase.length(); i++) {
            final int length = i;
            later(start + i * step, () -> inputText.setText(phrase.substring(0, length)));
        }
        final long sendAt = start + phrase.length() * step + 700;
        later(sendAt, () -> {
            inputText.setText(LocaleController.getString(R.string.TypeMessage));
            inputText.setTextColor(color(Theme.key_chat_messagePanelHint));
            dictation.setColorFilter(new PorterDuffColorFilter(color(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
            addMessage(textMessage(true, phrase));
        });
        return sendAt + 800;
    }

    private long playFrontCamera() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), true);
        final FrameLayout circle = new FrameLayout(getContext()) {
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                super.dispatchDraw(canvas);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(dp(3));
                ring.setColor(0xffffffff);
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - dp(1.5f), ring);
            }
        };
        circle.setClipToOutline(true);
        circle.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        if (on) {
            final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
            final BackupImageView selfie = createAvatar(self, UserObject.getUserName(self), 170);
            selfie.setRoundRadius(0);
            circle.addView(selfie, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        } else {
            circle.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff7cc6f2, 0xffbfe4a8, 0xff6f9d58}));
            final ImageView camera = new ImageView(getContext());
            camera.setImageResource(R.drawable.msg_camera);
            camera.setScaleType(ImageView.ScaleType.CENTER);
            camera.setColorFilter(new PorterDuffColorFilter(0xccffffff, PorterDuff.Mode.SRC_IN));
            circle.addView(camera, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        circle.setScaleX(0f);
        circle.setScaleY(0f);
        stage.addView(circle, LayoutHelper.createFrame(170, 170, Gravity.CENTER, 0, 0, 0, 10));
        later(700, () -> circle.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start());
        later(1000, () -> toast(LocaleController.getString(on ? R.string.UMessageFrontCamera : R.string.UMessagePreviewRearCamera), 0, 2200));
        later(3400, () -> circle.animate().scaleX(0f).scaleY(0f).setDuration(260).start());
        return 3800;
    }

    private long playAutoApprove() {
        final TLRPC.Chat group = firstManagedGroup();
        chatScene(group, group != null ? group.title : LocaleController.getString(R.string.UMessageFolderMyGroups),
                LocaleController.formatPluralString("Members", group != null && group.participants_count > 0 ? group.participants_count : 128), false);
        final String name = PEER_FALLBACK;
        later(700, () -> addChip(LocaleController.formatString(R.string.UMessagePreviewJoinRequest, name)));
        if (on) {
            later(1900, () -> addChip(LocaleController.formatString(R.string.UMessagePreviewJoinApproved, name)));
        }
        return 3600;
    }

    private long playStopAutoDownload() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        later(600, () -> addMessage(documentMessage(false, "report.pdf", "application/pdf", 64 * 1024 * 1024)));
        later(1400, () -> toast(LocaleController.getString(on ? R.string.UMessagePreviewTapToDownload : R.string.UMessagePreviewAutoDownloading), 0, 2000));
        return 3600;
    }

    private long playBlockAds() {
        final Chat channel = firstChannel();
        chatScene(channel.object, channel.name, LocaleController.formatPluralString("Subscribers", 12480), false);
        later(500, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewPost))));
        later(1700, () -> {
            if (on) {
                toast(LocaleController.getString(R.string.UMessagePreviewAdBlocked), 0, 1800);
            } else {
                addSponsoredMessage(channel.name, LocaleController.getString(R.string.UMessagePreviewAd), LocaleController.getString(R.string.Open));
            }
        });
        return 3700;
    }

    private long playPremium() {
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        final String name = UserObject.getUserName(self);
        chatScene(self, name, LocaleController.getString(R.string.Online), false);
        toast(LocaleController.getString(R.string.UMessagePreviewFriendScreen), 0, 1800);
        later(700, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewHi))));
        if (on) {
            final TextView title = (TextView) header.getChildAt(1);
            later(1600, () -> {
                final SpannableStringBuilder text = new SpannableStringBuilder(name).append("  ");
                final ColoredImageSpan star = new ColoredImageSpan(R.drawable.msg_premium_liststar);
                star.setOverrideColor(0xffffb300);
                text.setSpan(star, text.length() - 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                title.setText(text);
                pulse(title);
            });
        }
        return 3600;
    }

    private long playProxy() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Connecting), false);
        if (on) {
            later(1400, () -> {
                statusView.setText(LocaleController.getString(R.string.ConnectingToProxy), true);
                toast(LocaleController.getString(R.string.UMessagePreviewProxyOn), 0, 1600);
            });
            later(2800, () -> {
                statusView.setText(LocaleController.getString(R.string.Online), true);
                addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewHi)));
            });
        } else {
            toast(LocaleController.getString(R.string.UMessagePreviewNoConnection), 1800, 1800);
        }
        return 4000;
    }

    private long playNearbyShare() {
        final Chat peer = firstPrivateChat();
        final UMessageNearbyCard card = new UMessageNearbyCard(getContext(), peer.name, null, LocaleController.getString(R.string.UMessagePreviewHi), null, null);
        card.setScaleX(0.74f);
        card.setScaleY(0.74f);
        stage.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 18, 0, 18, 0));
        final long end = card.play(on);
        if (!on) {
            toast(LocaleController.getString(R.string.UMessageNearbyShareOff), 1400, 1600);
            return 3000;
        }
        return end + 1400;
    }

    private long playGuardOwnerAway() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        later(400, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewHi))));
        later(900, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewSecret))));
        toast(LocaleController.getString(R.string.UMessagePreviewOwnerAway), 1800, 1600);
        if (on) {
            later(2100, () -> veil(messages, true));
            later(3700, () -> veil(messages, false));
            toast(LocaleController.getString(R.string.UMessagePreviewOwnerBack), 3700, 1200);
        }
        return 4900;
    }

    private long playGuardMedia() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        final ChatMessageCell[] cell = new ChatMessageCell[1];
        later(500, () -> cell[0] = addMessage(documentMessage(false, "IMG_2041.jpg", "image/jpeg", 2 * 1024 * 1024)));
        toast(LocaleController.getString(R.string.UMessagePreviewStrangerFace), 1700, 1600);
        if (on) {
            later(2000, () -> veil(cell[0], true));
            later(3600, () -> veil(cell[0], false));
        }
        return 4600;
    }

    private long playGuardComposer() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), true);
        later(500, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewHi))));
        later(1000, () -> {
            inputText.setText(LocaleController.getString(R.string.UMessagePreviewDraft));
            inputText.setTextColor(color(Theme.key_chat_messagePanelText));
        });
        toast(LocaleController.getString(R.string.UMessagePreviewStrangerFace), 1900, 1600);
        if (on) {
            later(2200, () -> veil(inputText, true));
            later(3800, () -> veil(inputText, false));
        }
        return 4800;
    }

    private long playGuardScreenshots() {
        final Chat peer = firstPrivateChat();
        chatScene(peer.object, peer.name, LocaleController.getString(R.string.Online), false);
        later(400, () -> addMessage(textMessage(false, LocaleController.getString(R.string.UMessagePreviewSecret))));
        later(1600, () -> {
            final View flash = new View(getContext());
            flash.setBackgroundColor(on ? 0xff000000 : 0xffffffff);
            stage.addView(flash, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            flash.animate().alpha(0f).setDuration(on ? 900 : 350).withEndAction(() -> stage.removeView(flash)).start();
        });
        toast(LocaleController.getString(on ? R.string.UMessagePreviewScreenshotBlocked : R.string.UMessagePreviewScreenshotSaved), 1900, 1800);
        return 4000;
    }

    /** Blurs a view like Privacy Guard does (plain fade before Android 12). */
    private static void veil(View view, boolean veil) {
        if (view == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(veil ? RenderEffect.createBlurEffect(dp(14), dp(14), Shader.TileMode.CLAMP) : null);
        } else {
            view.animate().alpha(veil ? 0.06f : 1f).setDuration(250).start();
        }
    }

    private Chat firstChannel() {
        for (Chat chat : chats) {
            if (chat.object instanceof TLRPC.Chat && ((TLRPC.Chat) chat.object).broadcast) {
                return chat;
            }
        }
        return firstPrivateChat();
    }

    /* Chat scene */

    private void chatScene(TLObject peer, String name, String status, boolean withInput) {
        wallpaper = true;
        final LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        stage.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        header = new FrameLayout(getContext());
        header.setBackgroundColor(color(Theme.key_actionBarDefault));
        header.addView(createAvatar(peer, name, 36), LayoutHelper.createFrame(36, 36, Gravity.CENTER_VERTICAL | Gravity.LEFT, 14, 0, 0, 0));
        header.addView(text(name, 15, color(Theme.key_actionBarDefaultTitle), true),
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 62, 8, 14, 0));
        statusView = new AnimatedTextView(getContext(), true, true, false);
        statusView.setTextSize(dp(13));
        statusView.setTextColor(color(Theme.key_actionBarDefaultSubtitle));
        statusView.setText(status, false);
        header.addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 18, Gravity.TOP | Gravity.LEFT, 62, 29, 14, 0));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));

        messages = new LinearLayout(getContext());
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setGravity(Gravity.BOTTOM);
        messages.setPadding(0, 0, 0, dp(6));
        messages.setLayoutTransition(createTransition());
        column.addView(messages, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        if (withInput) {
            column.addView(createInputBar(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
    }

    private View createInputBar() {
        final LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(Theme.key_chat_messagePanelBackground));
        bar.addView(inputIcon(R.drawable.input_smile, "smile"), LayoutHelper.createLinear(48, 48));
        inputText = text(LocaleController.getString(R.string.TypeMessage), 16, color(Theme.key_chat_messagePanelHint), false);
        bar.addView(inputText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (UMessageConfig.KEY_VOICE_INPUT.equals(key) && on) {
            bar.addView(inputIcon(R.drawable.msg_voice_unmuted, "dictation"), LayoutHelper.createLinear(44, 48));
        }
        bar.addView(inputIcon(R.drawable.input_attach, "attach"), LayoutHelper.createLinear(44, 48));
        bar.addView(inputIcon(R.drawable.input_mic, "mic"), LayoutHelper.createLinear(48, 48));
        return bar;
    }

    private ImageView inputIcon(int res, String tag) {
        final ImageView icon = new ImageView(getContext());
        icon.setImageResource(res);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(new PorterDuffColorFilter(color(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
        icon.setTag(tag);
        return icon;
    }

    private View createConfirmCard(String message, TextView[] sendButton) {
        final LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(12), dp(8));
        card.setBackground(Theme.createRoundRectDrawable(dp(14), color(Theme.key_dialogBackground)));
        card.setElevation(dp(8));
        card.addView(text(LocaleController.getString(R.string.UMessageConfirmTitle), 17, color(Theme.key_dialogTextBlack), true),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 8));
        final TextView body = text(message, 15, color(Theme.key_dialogTextBlack), false);
        body.setSingleLine(false);
        card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 10));
        final LinearLayout buttons = new LinearLayout(getContext());
        buttons.setGravity(Gravity.RIGHT);
        final TextView cancel = text(LocaleController.getString(R.string.Cancel), 15, color(Theme.key_dialogTextBlue), true);
        cancel.setPadding(dp(10), dp(8), dp(10), dp(8));
        buttons.addView(cancel);
        sendButton[0] = text(LocaleController.getString(R.string.Send), 15, color(Theme.key_dialogTextBlue), true);
        sendButton[0].setPadding(dp(10), dp(8), dp(10), dp(8));
        buttons.addView(sendButton[0]);
        card.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    private ChatMessageCell addMessage(TLRPC.TL_message message) {
        final ChatMessageCell cell = new ChatMessageCell(getContext(), account, false, null, resourcesProvider);
        cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {});
        cell.setFullyDraw(true);
        cell.setMessageObject(createMessageObject(message), null, false, false, false);
        messages.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    /** A message rendered like a real channel sponsored post: a "Sponsored" label, title and a button. */
    private ChatMessageCell addSponsoredMessage(String advertiser, String text, String button) {
        final ChatMessageCell cell = new ChatMessageCell(getContext(), account, false, null, resourcesProvider);
        cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {});
        cell.setFullyDraw(true);
        final MessageObject object = new MessageObject(account, textMessage(false, text), true, false);
        object.sponsoredId = new byte[]{1, 2, 3, 4};
        object.sponsoredTitle = advertiser;
        object.sponsoredButtonText = button;
        object.sponsoredUrl = "https://t.me";
        object.sponsoredRecommended = true;
        object.resetLayout();
        cell.setMessageObject(object, null, false, false, false);
        messages.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private void replaceMessage(ChatMessageCell cell, TLRPC.TL_message message) {
        if (cell == null) {
            return;
        }
        cell.setMessageObject(createMessageObject(message), null, false, false, false);
        cell.requestLayout();
        cell.invalidate();
    }

    private MessageObject createMessageObject(TLRPC.TL_message message) {
        final MessageObject object = new MessageObject(account, message, true, false);
        object.resetLayout();
        return object;
    }

    private TLRPC.TL_message baseMessage(boolean out) {
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = nextMessageId++;
        message.date = (int) (System.currentTimeMillis() / 1000) - 60;
        message.dialog_id = 1;
        message.flags = 257;
        message.out = out;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = out ? selfId : 0;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = out ? 0 : selfId;
        message.media = new TLRPC.TL_messageMediaEmpty();
        return message;
    }

    private TLRPC.TL_message textMessage(boolean out, String text) {
        final TLRPC.TL_message message = baseMessage(out);
        message.message = text;
        return message;
    }

    private TLRPC.TL_message documentMessage(boolean out, String fileName, String mime, long size) {
        final TLRPC.TL_message message = baseMessage(out);
        message.message = "";
        final TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = -1000 - message.id;
        document.mime_type = mime;
        document.size = size;
        document.file_reference = new byte[0];
        final TLRPC.TL_documentAttributeFilename attribute = new TLRPC.TL_documentAttributeFilename();
        attribute.file_name = fileName;
        document.attributes.add(attribute);
        final TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.flags |= 1;
        media.document = document;
        message.media = media;
        return message;
    }

    private TLRPC.TL_message voiceMessage() {
        final TLRPC.TL_message message = documentMessage(true, "voice.ogg", "audio/ogg", 24 * 1024);
        final TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.voice = true;
        audio.duration = 7;
        audio.waveform = new byte[63];
        for (int i = 0; i < audio.waveform.length; i++) {
            audio.waveform[i] = (byte) (Math.abs(Math.sin(i * 0.7)) * 200 + 30);
        }
        audio.flags |= 4;
        message.media.document.attributes.clear();
        message.media.document.attributes.add(audio);
        message.media_unread = false;
        return message;
    }

    private void addChip(CharSequence text) {
        final TextView chip = serviceText(text);
        final FrameLayout wrap = new FrameLayout(getContext());
        wrap.addView(chip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 4, 24, 4));
        messages.addView(wrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /* Chat list scene */

    private interface HeaderIcons {
        void add(LinearLayout icons);
    }

    /** @param tabs 0 — no folder tabs, 1 — folder tabs with names */
    private void listScene(boolean withStories, int tabs, HeaderIcons headerIcons) {
        wallpaper = false;
        final LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutTransition(createTransition());
        stage.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        header = new FrameLayout(getContext());
        header.addView(text(LocaleController.getString(R.string.AppName), 19, color(Theme.key_windowBackgroundWhiteBlackText), true),
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 16, 0, 0, 0));
        final LinearLayout icons = new LinearLayout(getContext());
        icons.setOrientation(LinearLayout.HORIZONTAL);
        if (headerIcons != null) {
            headerIcons.add(icons);
        }
        addHeaderIcon(icons, R.drawable.outline_header_search);
        header.addView(icons, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.RIGHT, 0, 0, 6, 0));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        if (withStories) {
            column.addView(createStoriesRow(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 84));
        }
        if (tabs > 0) {
            column.addView(createTabsRow(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
        }

        rows = new LinearLayout(getContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setLayoutTransition(createTransition());
        for (Chat chat : chats) {
            rows.addView(createRow(chat));
        }
        column.addView(rows, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private ImageView addHeaderIcon(LinearLayout icons, int res) {
        final ImageView icon = new ImageView(getContext());
        icon.setImageResource(res);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(new PorterDuffColorFilter(color(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        icons.addView(icon, LayoutHelper.createLinear(44, LayoutHelper.MATCH_PARENT));
        return icon;
    }

    private static final int[] FOLDER_ICONS = {
            R.drawable.msg_folders_read, R.drawable.msg_folders_private, R.drawable.msg_folders_groups, R.drawable.msg_folders_channels
    };

    private View createTabsRow() {
        final HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setTag("tabsScroll");
        final LinearLayout tabs = new LinearLayout(getContext());
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(8), 0, dp(8), 0);
        tabs.setTag("tabs");
        tabs.setLayoutTransition(createTransition());
        tabs.addView(createTab(LocaleController.getString(R.string.FilterAllChats), true));
        tabs.addView(createTab(LocaleController.getString(R.string.FilterContacts), false));
        tabs.addView(createTab(LocaleController.getString(R.string.FilterGroups), false));
        tabs.addView(createTab(LocaleController.getString(R.string.FilterChannels), false));
        scroll.addView(tabs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private TextView createTab(String title, boolean selected) {
        final TextView tab = text(title, 14, color(selected ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteGrayText), true);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(12), dp(4), dp(12), dp(4));
        tab.setMinWidth(dp(48));
        if (selected) {
            tab.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(color(Theme.key_featuredStickers_addButton), 0.12f)));
        }
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        params.gravity = Gravity.CENTER_VERTICAL;
        params.leftMargin = params.rightMargin = dp(2);
        tab.setLayoutParams(params);
        return tab;
    }

    private View createStoriesRow() {
        final LinearLayout stories = new LinearLayout(getContext());
        stories.setOrientation(LinearLayout.HORIZONTAL);
        stories.setPadding(dp(8), 0, dp(8), 0);
        stories.setTag("stories");
        for (Chat chat : chats) {
            final LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            final StoryCircle circle = new StoryCircle(getContext(), chat);
            item.setTag(circle);
            item.addView(circle, LayoutHelper.createLinear(58, 58));
            final TextView name = text(chat.name, 11, color(Theme.key_windowBackgroundWhiteBlackText), false);
            name.setGravity(Gravity.CENTER);
            item.addView(name, LayoutHelper.createLinear(64, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            stories.addView(item, LayoutHelper.createLinear(68, LayoutHelper.MATCH_PARENT));
        }
        final FrameLayout wrap = new FrameLayout(getContext());
        wrap.setTag("storiesRow");
        wrap.addView(stories, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 4, 0, 0));
        return wrap;
    }

    private class StoryCircle extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float viewed;

        StoryCircle(Context context, Chat chat) {
            super(context);
            setWillNotDraw(false);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            addView(createAvatar(chat.object, chat.name, 50), LayoutHelper.createFrame(50, 50, Gravity.CENTER));
        }

        void setViewed() {
            final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.addUpdateListener(a -> {
                viewed = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(400);
            animator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            rect.set(dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1));
            paint.setShader(new LinearGradient(0, getHeight(), getWidth(), 0, 0xff34c76f, 0xff3da1fd, Shader.TileMode.CLAMP));
            paint.setAlpha((int) (255 * (1f - viewed)));
            canvas.drawOval(rect, paint);
            paint.setShader(null);
            paint.setColor(color(Theme.key_windowBackgroundWhiteGrayText));
            paint.setAlpha((int) (140 * viewed));
            canvas.drawOval(rect, paint);
        }
    }

    private View createRow(Chat chat) {
        final FrameLayout row = new FrameLayout(getContext());
        row.addView(createAvatar(chat.object, chat.name, 50), LayoutHelper.createFrame(50, 50, Gravity.CENTER_VERTICAL | Gravity.LEFT, 12, 0, 0, 0));
        row.addView(text(chat.name, 15, color(Theme.key_chats_name), true),
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 74, 10, 60, 0));
        row.addView(text(chat.text, 14, color(Theme.key_chats_message), false),
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 74, 33, chat.unread > 0 ? 48 : 16, 0));
        if (chat.date != 0) {
            row.addView(text(LocaleController.stringForMessageListDate(chat.date), 12, color(Theme.key_chats_date), false),
                    LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 12, 14, 0));
        }
        if (chat.unread > 0) {
            final TextView badge = text(chat.unread > 99 ? "99+" : String.valueOf(chat.unread), 12, color(Theme.key_chats_unreadCounterText), true);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(22));
            badge.setPadding(dp(6), 0, dp(6), 0);
            badge.setBackground(Theme.createRoundRectDrawable(dp(11), color(Theme.key_chats_unreadCounter)));
            row.addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 22, Gravity.TOP | Gravity.RIGHT, 0, 34, 14, 0));
        }
        final View check = new View(getContext()) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final float r = getWidth() / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color(Theme.key_windowBackgroundWhite));
                canvas.drawCircle(r, r, r, paint);
                paint.setColor(color(Theme.key_featuredStickers_addButton));
                canvas.drawCircle(r, r, r - dp(2), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(0xffffffff);
                canvas.drawLine(r - dp(4), r, r - dp(1), r + dp(3), paint);
                canvas.drawLine(r - dp(1), r + dp(3), r + dp(4.5f), r - dp(3), paint);
            }
        };
        check.setTag("check");
        check.setScaleX(0f);
        check.setScaleY(0f);
        row.addView(check, LayoutHelper.createFrame(22, 22, Gravity.TOP | Gravity.LEFT, 44, 38, 0, 0));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        return row;
    }

    private void setRowSelected(View row, boolean selected) {
        if (row == null) {
            return;
        }
        row.setBackgroundColor(selected ? Theme.multAlpha(color(Theme.key_featuredStickers_addButton), 0.08f) : 0);
        final View check = row.findViewWithTag("check");
        if (check != null) {
            check.animate().scaleX(selected ? 1f : 0f).scaleY(selected ? 1f : 0f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
    }

    /* Data */

    private void loadChats() {
        final MessagesController controller = MessagesController.getInstance(account);
        for (TLRPC.Dialog dialog : new ArrayList<>(controller.getAllDialogs())) {
            if (chats.size() >= 4) {
                break;
            }
            if (dialog == null || UMessageConfig.isHiddenFromChatList(account, dialog)) {
                continue;
            }
            final Chat chat = new Chat();
            if (DialogObject.isUserDialog(dialog.id)) {
                final TLRPC.User user = controller.getUser(dialog.id);
                if (user == null || UserObject.isUserSelf(user) || UserObject.isDeleted(user) || UserObject.isReplyUser(user)) {
                    continue;
                }
                chat.object = user;
                chat.name = UserObject.getUserName(user);
            } else if (DialogObject.isChatDialog(dialog.id)) {
                final TLRPC.Chat tlChat = controller.getChat(-dialog.id);
                if (tlChat == null) {
                    continue;
                }
                chat.object = tlChat;
                chat.name = tlChat.title;
            } else {
                continue;
            }
            final ArrayList<MessageObject> last = controller.dialogMessage.get(dialog.id);
            if (last != null && !last.isEmpty() && last.get(0) != null && last.get(0).messageText != null) {
                chat.text = last.get(0).messageText.toString().replace('\n', ' ');
            } else {
                chat.text = "";
            }
            chat.date = dialog.last_message_date;
            chat.unread = dialog.unread_count;
            chats.add(chat);
        }
        if (chats.isEmpty()) {
            final Chat chat = new Chat();
            chat.name = PEER_FALLBACK;
            chat.text = LocaleController.getString(R.string.UMessagePreviewHi);
            chat.date = (int) (System.currentTimeMillis() / 1000);
            chat.unread = 2;
            chats.add(chat);
        }
    }

    private Chat firstPrivateChat() {
        for (Chat chat : chats) {
            if (chat.object instanceof TLRPC.User && !((TLRPC.User) chat.object).bot) {
                return chat;
            }
        }
        final Chat chat = new Chat();
        chat.name = PEER_FALLBACK;
        chat.text = LocaleController.getString(R.string.UMessagePreviewHi);
        return chat;
    }

    private TLRPC.Chat firstManagedGroup() {
        final MessagesController controller = MessagesController.getInstance(account);
        for (TLRPC.Dialog dialog : new ArrayList<>(controller.getAllDialogs())) {
            if (!DialogObject.isChatDialog(dialog.id)) {
                continue;
            }
            final TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat != null && (chat.creator || chat.admin_rights != null) && !UMessageConfig.isHiddenFromChatList(account, dialog)) {
                return chat;
            }
        }
        return null;
    }

    /* Helpers */

    private BackupImageView createAvatar(TLObject object, String name, int sizeDp) {
        final BackupImageView avatar = new BackupImageView(getContext());
        avatar.setRoundRadius(dp(sizeDp / 2f));
        final AvatarDrawable drawable = new AvatarDrawable();
        if (object != null) {
            drawable.setInfo(account, object);
            avatar.setForUserOrChat(object, drawable);
        } else {
            drawable.setInfo(0, name, null);
            avatar.setImageDrawable(drawable);
        }
        return avatar;
    }

    private TextView text(CharSequence value, int sizeDp, int color, boolean bold) {
        final TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            view.setTypeface(AndroidUtilities.bold());
        }
        return view;
    }

    private TextView serviceText(CharSequence value) {
        final TextView view = text(value, 13, color(Theme.key_chat_serviceText), true);
        view.setSingleLine(false);
        view.setMaxLines(3);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(4), dp(10), dp(5));
        view.setBackground(Theme.createRoundRectDrawable(dp(12), color(Theme.key_chat_serviceBackground)));
        return view;
    }

    /** A service-style pill that fades in over the scene and goes away by itself. */
    private void toast(CharSequence value, long delay, long duration) {
        later(delay, () -> {
            final TextView view = serviceText(value);
            if (!wallpaper) {
                view.setTextColor(0xffffffff);
                view.setBackground(Theme.createRoundRectDrawable(dp(12), 0xcc1c1c1e));
            }
            view.setAlpha(0f);
            view.setTranslationY(dp(6));
            stage.addView(view, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 24, header != null ? 66 : 14, 24, 0));
            view.animate().alpha(1f).translationY(0).setDuration(220).start();
            later(duration, () -> view.animate().alpha(0f).setDuration(200).withEndAction(() -> stage.removeView(view)).start());
        });
    }

    private static void pulse(View view) {
        if (view == null) {
            return;
        }
        view.animate().scaleX(0.8f).scaleY(0.8f).setDuration(120).withEndAction(() ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start()
        ).start();
    }

    private static void collapse(View view) {
        if (view == null) {
            return;
        }
        final int height = view.getHeight();
        final ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.addUpdateListener(a -> {
            final float value = (float) a.getAnimatedValue();
            view.getLayoutParams().height = (int) (height * value);
            view.setAlpha(value);
            view.requestLayout();
        });
        animator.setDuration(320);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.start();
    }

    private static LayoutTransition createTransition() {
        final LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.setDuration(260);
        return transition;
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }
}
