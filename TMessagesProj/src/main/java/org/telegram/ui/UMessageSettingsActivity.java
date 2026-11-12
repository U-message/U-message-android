package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.Components.RoundVideoEffects;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageAdminFolders;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UMessagePremiumController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.privacyguard.OwnerFaceStore;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageLogoView;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** U message options presented with Telegram's native settings layout and cells. */
public class UMessageSettingsActivity extends BaseFragment {

    private static final int BLOCK_ADS = 1;
    private static final int TEMPLATES = 2;
    private static final int FOCUS = 3;
    private static final int DESIGN = 4;
    private static final int BASED_ON = 5;

    private static final int TOGGLE_BASE = 100; // + index in TOGGLES
    private static final int AUTO_REPLY_TEXT = 10;
    private static final int REMINDER_DELAY = 11;
    private static final int REMINDER_SOUND = 12;
    private static final int STRANGER_CHATS = 13;
    private static final int HIDDEN_CHATS = 14;
    private static final int HIDDEN_PASSWORD = 15;
    private static final int HIDDEN_ACCESS = 16;
    private static final int ACCOUNT_AUTO_SWITCH = 17;
    private static final int ROUND_EFFECTS = 18;
    private static final int PRIVACY_GUARD = 19;
    private static final int USE_PROXY = 20;
    private static final int HIDDEN_FOLDERS = 21;
    private static final int UM_PREMIUM = 22;
    private static final int NEARBY_SHARE = 23;
    private static final int SPEECH_ENABLED = 25;
    private static final int SPEECH_MODEL = 26;
    private static final int PARENTAL = 40;
    private static final int NSFW_FILTER = 43;
    private static final int NSFW_LEVEL = 47;
    private static final int SHAZAM = 42;
    private static final int NEARBY_SHARE_PAGE = 44;
    private static final int LOCAL_DATA_CLEAR = 45;
    private static final int LOCAL_CACHE = 46;
    private static final int CHAT_TRASH = 50;
    private static final int KEPT_MEDIA = 62;
    private static final int INSTAGRAM_BUSINESS = 63;
    private static final int LOCAL_TG_PREMIUM = 70;
    private static final int ERROR_REPORTS = 71;
    private static final int CHAT_GUARD_SHARE = 72;
    private static final int GHOST_SCOPE = 73;
    private static final int GHOST_USERS = 74;
    private static final int DELETE_SERVER_DATA = 75;
    // Offline chat temporarily disabled; keep the id reserved for restoring it.
    // private static final int OFFLINE_CHAT = 61;

    /** Every switch on this screen, by its UMessageConfig key; the item id is TOGGLE_BASE + index. */
    private static final String[] TOGGLES = {
            UMessageConfig.KEY_GHOST_MODE,
            UMessageConfig.KEY_GHOST_BUTTON,
            UMessageConfig.KEY_HIDDEN_CHATS,
            UMessageConfig.KEY_SAVE_EDITED,
            UMessageConfig.KEY_SAVE_DELETED,
            UMessageConfig.KEY_STRANGER_PROTECTION,
            UMessageConfig.KEY_GROUP_SPAM_GUARD,
            UMessageConfig.KEY_BLOCK_APK,
            UMessageConfig.KEY_FOLDER_ICONS,
            UMessageConfig.KEY_HIDE_FOLDER_TABS,
            UMessageConfig.KEY_ADMIN_FOLDERS,
            UMessageConfig.KEY_STORIES_ANONYMOUS,
            UMessageConfig.KEY_STORIES_HIDDEN,
            UMessageConfig.KEY_STORIES_DOWNLOAD,
            UMessageConfig.KEY_UNREAD_REMINDER,
            UMessageConfig.KEY_AUTO_REPLY,
            UMessageConfig.KEY_CONFIRM_STICKER,
            UMessageConfig.KEY_CONFIRM_VOICE,
            UMessageConfig.KEY_CONFIRM_GIF,
            UMessageConfig.KEY_VOICE_INPUT,
            UMessageConfig.KEY_ROUND_FRONT_CAMERA,
            UMessageConfig.KEY_AUTO_APPROVE,
            UMessageConfig.KEY_STOP_AUTODOWNLOAD,
            UMessageConfig.KEY_SAVE_VIEW_ONCE,
    };
    private static final int[] TOGGLE_TITLES = {
            R.string.UMessageGhostMode,
            R.string.UMessageGhostButton,
            R.string.UMessageSecretChat,
            R.string.UMessageSaveEdited,
            R.string.UMessageSaveDeleted,
            R.string.UMessageStrangerProtection,
            R.string.UMessageGroupSpamGuard,
            R.string.UMessageBlockApk,
            R.string.UMessageFolderIcons,
            R.string.UMessageHideFolderTabs,
            R.string.UMessageAdminFolders,
            R.string.UMessageStoriesAnonymous,
            R.string.UMessageStoriesHide,
            R.string.UMessageStoriesDownload,
            R.string.UMessageReminder,
            R.string.UMessageAutoReply,
            R.string.UMessageConfirmSticker,
            R.string.UMessageConfirmVoice,
            R.string.UMessageConfirmGif,
            R.string.UMessageVoiceInput,
            R.string.UMessageFrontCamera,
            R.string.UMessageAutoApprove,
            R.string.UMessageStopAutoDownload,
            R.string.UMessageSaveViewOnce,
    };
    private static final int[] TOGGLE_INFOS = {
            R.string.UMessageGhostModeInfo,
            R.string.UMessageGhostButtonInfo,
            R.string.UMessageSecretChatInfo,
            R.string.UMessageSaveEditedInfo,
            R.string.UMessageSaveDeletedInfo,
            R.string.UMessageStrangerProtectionInfo,
            R.string.UMessageGroupSpamGuardInfo,
            R.string.UMessageBlockApkInfo,
            R.string.UMessageFolderIconsInfo,
            R.string.UMessageHideFolderTabsInfo,
            R.string.UMessageAdminFoldersInfo,
            R.string.UMessageStoriesAnonymousInfo,
            R.string.UMessageStoriesHideInfo,
            R.string.UMessageStoriesDownloadInfo,
            R.string.UMessageReminderInfo,
            R.string.UMessageAutoReplyInfo,
            R.string.UMessageConfirmStickerInfo,
            R.string.UMessageConfirmVoiceInfo,
            R.string.UMessageConfirmGifInfo,
            R.string.UMessageVoiceInputInfo,
            R.string.UMessageFrontCameraInfo,
            R.string.UMessageAutoApproveInfo,
            R.string.UMessageStopAutoDownloadInfo,
            R.string.UMessageSaveViewOnceInfo,
    };

    private FrameLayout contentView;
    private UniversalRecyclerView listView;
    private View actionBarBackground;
    private TextView versionView;
    private boolean actionBarVisible;
    private ValueAnimator actionBarVisibleAnimator;
    private long localCacheBytes;
    private long localHistoryBytes;
    private boolean localDataLoading = true;
    private boolean localDataBusy;
    private boolean localDataError;
    private int localDataRequest;
    private final Runnable localDataListener = this::onLocalDataChanged;

    private void onLocalDataChanged() {
        if (section != SECTION_MESSAGES && section != SECTION_DATA) return;
        localDataBusy = false;
        refreshLocalData();
    }
    private final Runnable configurationListener = () -> {
        if (listView != null) {
            listView.adapter.update(true);
        }
    };

    /** Remote feature switches changed: rows of switched-off features disappear (or come back) at once. */
    private final NotificationCenter.NotificationCenterDelegate remoteFlagsObserver = (id, account, args) -> {
        if (listView != null) {
            listView.adapter.update(true);
        }
    };

    private static boolean on(String feature) {
        return org.telegram.messenger.UMessageRemoteFlags.isEnabled(feature);
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        NotificationCenter.getGlobalInstance().addObserver(remoteFlagsObserver, NotificationCenter.umessageRemoteFlagsChanged);
        org.telegram.configuration.ConfigurationManager.addListener(configurationListener);
        org.telegram.messenger.UMessageLocalData.addListener(localDataListener);
        return true;
    }

    @Override
    public View createView(Context context) {
        contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setUseContainerForTitles();
        actionBar.setTitle(getSectionTitle(section));
        actionBar.setAddToContainer(false);
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        versionView = new TextView(context);
        versionView.setText("U message " + BuildVars.BUILD_VERSION_STRING);
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText4));
        versionView.setGravity(Gravity.CENTER);
        versionView.setPadding(dp(21), dp(10), dp(21), dp(18));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        UMessageModernList.setup(listView);
        // sub pages have a plain action bar on top; the main page scrolls under a transparent one past its logo
        listView.setPadding(0, AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), 0, AndroidUtilities.navigationBarHeight);
        // keep the paddings in sync with the real bars (rotation, gesture/3-button nav switch, multi-window)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(listView, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, bars.top + ActionBar.getCurrentActionBarHeight(), 0, bars.bottom);
            return insets;
        });
        listView.setClipToPadding(false);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateActionBarVisible(true);
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        actionBarBackground = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final int height = actionBar.getHeight();
                paint.setColor(getThemedColor(Theme.key_actionBarDefault));
                canvas.drawRect(0, 0, getWidth(), height, paint);
                if (getParentLayout() != null) {
                    getParentLayout().drawHeaderShadow(canvas, height);
                }
            }
        };
        contentView.addView(actionBarBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.TOP));
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        listView.adapter.update(false);
        updateActionBarVisible(false);

        fragmentView = contentView;
        return fragmentView;
    }

    /** Telegram-like: a main page of categories with coloured icons, each category on its own page. */
    public static final int SECTION_MAIN = 0;
    public static final int SECTION_PRIVACY = 1;
    public static final int SECTION_SECURITY = 2;
    public static final int SECTION_MESSAGES = 3;
    public static final int SECTION_REMINDER = 4;
    public static final int SECTION_FOLDERS = 5;
    public static final int SECTION_STORIES = 6;
    public static final int SECTION_CAMERA = 7;
    public static final int SECTION_DATA = 8;
    private static final int OPEN_SECTION_BASE = 200; // + section

    private final int section;

    public UMessageSettingsActivity() {
        this(SECTION_MAIN);
    }

    public UMessageSettingsActivity(int section) {
        this.section = section;
    }

    private String getSectionTitle(int section) {
        switch (section) {
            case SECTION_PRIVACY: return LocaleController.getString(R.string.UMessageSectionPrivacy);
            case SECTION_SECURITY: return LocaleController.getString(R.string.UMessageSectionSecurity);
            case SECTION_MESSAGES: return LocaleController.getString(R.string.UMessageSectionMessagesChats);
            case SECTION_REMINDER: return LocaleController.getString(R.string.UMessageSectionReminderFocus);
            case SECTION_FOLDERS: return LocaleController.getString(R.string.UMessageSectionFolders);
            case SECTION_STORIES: return LocaleController.getString(R.string.UMessageSectionStoriesCamera);
            case SECTION_CAMERA: return LocaleController.getString(R.string.UMessageSectionCamera);
            case SECTION_DATA: return LocaleController.getString(R.string.UMessageSectionData);
        }
        return "U message";
    }

    private UItem sectionCell(int section, IconBackgroundColors colors, int icon, int subtitle) {
        // U message: sections are merged into three pages (see fillItems)
        return SettingsActivity.SettingCell.Factory.of(OPEN_SECTION_BASE + section, colors.top, colors.bottom, icon,
                getSectionTitle(section), LocaleController.getString(subtitle));
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switch (section) {
            case SECTION_MAIN: fillMain(items); break;
            case SECTION_PRIVACY: fillPrivacy(items); break;
            case SECTION_SECURITY: fillSecurity(items); break;
            case SECTION_MESSAGES: fillMessages(items); fillReminder(items); fillFolders(items); fillData(items); break;
            case SECTION_REMINDER: fillReminder(items); break;
            case SECTION_FOLDERS: fillFolders(items); break;
            case SECTION_STORIES: fillStories(items); fillCamera(items); break;
            case SECTION_CAMERA: fillCamera(items); break;
            case SECTION_DATA: fillData(items); break;
        }
        dropEmptySections(items);
        UMessageModernList.apply(items, this::tileFor);
    }

    /** iOS-Settings style tile (glyph + colour) for every row of these pages. */
    private int[] tileFor(UItem item) {
        if (item.id >= TOGGLE_BASE && item.id < TOGGLE_BASE + TOGGLES.length) {
            switch (TOGGLES[item.id - TOGGLE_BASE]) {
                case UMessageConfig.KEY_GHOST_MODE: return UMessageModernList.tile(R.drawable.msg_stories_stealth, UMessageModernList.INDIGO);
                case UMessageConfig.KEY_GHOST_BUTTON: return UMessageModernList.tile(R.drawable.msg_stories_stealth2, UMessageModernList.GRAPHITE);
                case UMessageConfig.KEY_HIDDEN_CHATS: return UMessageModernList.tile(R.drawable.msg_archive_hide, UMessageModernList.GRAPHITE);
                case UMessageConfig.KEY_SAVE_EDITED: return UMessageModernList.tile(R.drawable.msg_edit, UMessageModernList.BLUE);
                case UMessageConfig.KEY_SAVE_DELETED: return UMessageModernList.tile(R.drawable.msg_delete, UMessageModernList.RED);
                case UMessageConfig.KEY_STRANGER_PROTECTION: return UMessageModernList.tile(R.drawable.msg_contacts, UMessageModernList.GREEN);
                case UMessageConfig.KEY_GROUP_SPAM_GUARD: return UMessageModernList.tile(R.drawable.msg_report, UMessageModernList.ORANGE);
                case UMessageConfig.KEY_BLOCK_APK: return UMessageModernList.tile(R.drawable.msg_block, UMessageModernList.RED);
                case UMessageConfig.KEY_FOLDER_ICONS: return UMessageModernList.tile(R.drawable.msg_folders, UMessageModernList.BLUE);
                case UMessageConfig.KEY_HIDE_FOLDER_TABS: return UMessageModernList.tile(R.drawable.msg_viewchats, UMessageModernList.BLUE);
                case UMessageConfig.KEY_ADMIN_FOLDERS: return UMessageModernList.tile(R.drawable.msg_admins, UMessageModernList.BLUE);
                case UMessageConfig.KEY_STORIES_ANONYMOUS: return UMessageModernList.tile(R.drawable.msg_stories_stealth, UMessageModernList.PINK);
                case UMessageConfig.KEY_STORIES_HIDDEN: return UMessageModernList.tile(R.drawable.msg_stories_myhide, UMessageModernList.PINK);
                case UMessageConfig.KEY_STORIES_DOWNLOAD: return UMessageModernList.tile(R.drawable.msg_stories_save, UMessageModernList.PINK);
                case UMessageConfig.KEY_UNREAD_REMINDER: return UMessageModernList.tile(R.drawable.msg_notifications, UMessageModernList.ORANGE);
                case UMessageConfig.KEY_AUTO_REPLY: return UMessageModernList.tile(R.drawable.msg_msgbubble3, UMessageModernList.TEAL);
                case UMessageConfig.KEY_CONFIRM_STICKER: return UMessageModernList.tile(R.drawable.msg_sticker, UMessageModernList.ORANGE);
                case UMessageConfig.KEY_CONFIRM_VOICE: return UMessageModernList.tile(R.drawable.msg_voicechat, UMessageModernList.RED);
                case UMessageConfig.KEY_CONFIRM_GIF: return UMessageModernList.tile(R.drawable.msg_gif, UMessageModernList.PURPLE);
                case UMessageConfig.KEY_VOICE_INPUT: return UMessageModernList.tile(R.drawable.msg_premium_voice, UMessageModernList.BLUE);
                case UMessageConfig.KEY_ROUND_FRONT_CAMERA: return UMessageModernList.tile(R.drawable.msg_videocall, UMessageModernList.PURPLE);
                case UMessageConfig.KEY_AUTO_APPROVE: return UMessageModernList.tile(R.drawable.msg_channel, UMessageModernList.BLUE);
                case UMessageConfig.KEY_STOP_AUTODOWNLOAD: return UMessageModernList.tile(R.drawable.msg_download_settings, UMessageModernList.GREEN);
                case UMessageConfig.KEY_SAVE_VIEW_ONCE: return UMessageModernList.tile(R.drawable.msg_autodelete, UMessageModernList.PURPLE);
            }
            return null;
        }
        switch (item.id) {
            // main page
            case BLOCK_ADS: return UMessageModernList.tile(R.drawable.msg_premium_ads, UMessageModernList.RED);
            case USE_PROXY: return UMessageModernList.tile(R.drawable.msg_satellite, UMessageModernList.BLUE);
            case UM_PREMIUM: return UMessageModernList.tile(R.drawable.msg_premium_badge, UMessageModernList.PURPLE);
            case LOCAL_TG_PREMIUM: return UMessageModernList.tile(R.drawable.msg_premium_liststar, UMessageModernList.INDIGO);
            case OPEN_SECTION_BASE + SECTION_SECURITY: return UMessageModernList.tile(R.drawable.msg_policy, UMessageModernList.RED);
            case OPEN_SECTION_BASE + SECTION_PRIVACY: return UMessageModernList.tile(R.drawable.settings_privacy, UMessageModernList.INDIGO);
            case OPEN_SECTION_BASE + SECTION_MESSAGES: return UMessageModernList.tile(R.drawable.settings_chat, UMessageModernList.GREEN);
            case OPEN_SECTION_BASE + SECTION_STORIES: return UMessageModernList.tile(R.drawable.settings_features, UMessageModernList.PINK);
            case SHAZAM: return UMessageModernList.tile(R.drawable.settings_sounds, UMessageModernList.BLUE);
            case INSTAGRAM_BUSINESS: return UMessageModernList.tile(R.drawable.msg_photos, UMessageModernList.PURPLE);
            case NEARBY_SHARE_PAGE: return UMessageModernList.tile(R.drawable.settings_invite, UMessageModernList.TEAL);
            case PARENTAL: return UMessageModernList.tile(R.drawable.settings_group, UMessageModernList.ORANGE);
            case DESIGN: return UMessageModernList.tile(R.drawable.msg_help, UMessageModernList.GREEN);
            case BASED_ON: return UMessageModernList.tile(R.drawable.settings_features, UMessageModernList.CYAN);
            // privacy
            case GHOST_SCOPE: return UMessageModernList.tile(R.drawable.msg_contacts, UMessageModernList.INDIGO);
            case GHOST_USERS: return UMessageModernList.tile(R.drawable.msg_user_search, UMessageModernList.INDIGO);
            case PRIVACY_GUARD: return UMessageModernList.tile(R.drawable.menu_privacy, UMessageModernList.GREEN);
            case NSFW_FILTER: return UMessageModernList.tile(R.drawable.msg_photo_blur, UMessageModernList.MINT);
            case CHAT_TRASH: return UMessageModernList.tile(R.drawable.msg_delete, UMessageModernList.RED);
            case HIDDEN_CHATS: return UMessageModernList.tile(R.drawable.msg_archive, UMessageModernList.GRAPHITE);
            case HIDDEN_FOLDERS: return UMessageModernList.tile(R.drawable.msg_folders_private, UMessageModernList.BLUE);
            case HIDDEN_ACCESS: return UMessageModernList.tile(R.drawable.msg_permissions, UMessageModernList.GRAPHITE);
            case HIDDEN_PASSWORD: return UMessageModernList.tile(R.drawable.msg_pin_code, UMessageModernList.GRAPHITE);
            // security
            case ACCOUNT_AUTO_SWITCH: return UMessageModernList.tile(R.drawable.msg_openprofile, UMessageModernList.BLUE);
            case STRANGER_CHATS: return UMessageModernList.tile(R.drawable.msg_discussion, UMessageModernList.TEAL);
            // messages
            case KEPT_MEDIA: return UMessageModernList.tile(R.drawable.msg_saved, UMessageModernList.BLUE);
            case TEMPLATES: return UMessageModernList.tile(R.drawable.msg_msgbubble, UMessageModernList.TEAL);
            case AUTO_REPLY_TEXT: return UMessageModernList.tile(R.drawable.msg_edit, UMessageModernList.TEAL);
            case SPEECH_ENABLED: return UMessageModernList.tile(R.drawable.msg_premium_voice, UMessageModernList.TEAL);
            case SPEECH_MODEL: return UMessageModernList.tile(R.drawable.msg_download, UMessageModernList.BLUE);
            // reminder / focus
            case REMINDER_DELAY: return UMessageModernList.tile(R.drawable.msg_recent, UMessageModernList.ORANGE);
            case REMINDER_SOUND: return UMessageModernList.tile(R.drawable.msg_tone_on, UMessageModernList.ORANGE);
            case FOCUS: return UMessageModernList.tile(R.drawable.msg_mute, UMessageModernList.INDIGO);
            // camera
            case ROUND_EFFECTS: return UMessageModernList.tile(R.drawable.msg_mask, UMessageModernList.PINK);
            // data
            case -1: return UMessageModernList.tile(R.drawable.msg_filled_storageusage, UMessageModernList.BLUE);
            case -2: return UMessageModernList.tile(R.drawable.msg_recent, UMessageModernList.GRAPHITE);
            case LOCAL_DATA_CLEAR: return UMessageModernList.tile(R.drawable.msg_delete, UMessageModernList.RED);
            case LOCAL_CACHE: return UMessageModernList.tile(R.drawable.msg_clearcache, UMessageModernList.CYAN);
            case ERROR_REPORTS: return UMessageModernList.tile(R.drawable.msg_info, UMessageModernList.GRAPHITE);
            case CHAT_GUARD_SHARE: return UMessageModernList.tile(R.drawable.msg_policy, UMessageModernList.GREEN);
        }
        return null;
    }

    @Override
    public ArrayList<org.telegram.ui.ActionBar.ThemeDescription> getThemeDescriptions() {
        return UMessageModernList.themeDescriptions(contentView, listView);
    }

    /** Rows of remotely switched-off features are left out; a header left without rows goes too. */
    private static void dropEmptySections(ArrayList<UItem> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            final UItem item = items.get(i);
            final UItem next = i + 1 < items.size() ? items.get(i + 1) : null;
            if (item.viewType == UniversalAdapter.VIEW_TYPE_HEADER && (next == null || next.viewType == UniversalAdapter.VIEW_TYPE_SHADOW)) {
                items.remove(i);
            } else if (item.viewType == UniversalAdapter.VIEW_TYPE_SHADOW && item.text == null && i > 0
                    && items.get(i - 1).viewType == UniversalAdapter.VIEW_TYPE_SHADOW && items.get(i - 1).text == null) {
                items.remove(i);
            }
        }
    }

    private String instagramSubtitle() {
        final org.telegram.messenger.UMessageInstagramBusiness instagram = org.telegram.messenger.UMessageInstagramBusiness.getInstance(currentAccount);
        if (!instagram.isConnectedCached()) {
            return LocaleController.getString(R.string.UMessageInstagramBusinessInfo);
        }
        final int unread = instagram.getUnread();
        if (unread > 0) {
            return LocaleController.formatString(R.string.UMessageInstagramUnread, unread);
        }
        final String username = instagram.getUsernameCached();
        return username == null ? LocaleController.getString(R.string.UMessageInstagramBusinessInfo) : "@" + username;
    }

    private void fillMain(ArrayList<UItem> items) {

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionGeneral)));
        if (on(org.telegram.messenger.UMessageRemoteFlags.BLOCK_ADS)) items.add(UItem.asButtonCheck(BLOCK_ADS, LocaleController.getString(R.string.UMessageBlockAds), LocaleController.getString(R.string.UMessageBlockAdsInfo))
                .setChecked(UMessageConfig.isAdsBlocked()));
        if (on(org.telegram.messenger.UMessageRemoteFlags.PROXY_FALLBACK)) items.add(UItem.asButtonCheck(USE_PROXY, LocaleController.getString(R.string.UMessageUseProxy), LocaleController.getString(R.string.UMessageUseProxyInfo))
                .setChecked(UMessageConfig.isProxyFallbackEnabled()));
        if (on(org.telegram.messenger.UMessageRemoteFlags.UM_PREMIUM)) items.add(UItem.asButtonCheck(UM_PREMIUM, LocaleController.getString(R.string.UMessagePremium), LocaleController.getString(R.string.UMessagePremiumInfo))
                .setChecked(UMessagePremiumController.getInstance().isSelfEnabled()));
        items.add(UItem.asShadow(null));

        // four categories only: a long list of options loses people. Security first — it is on by default.
        items.add(sectionCell(SECTION_SECURITY, IconBackgroundColors.RED, R.drawable.msg_policy, R.string.UMessageSectionSecurityInfo));
        items.add(sectionCell(SECTION_PRIVACY, IconBackgroundColors.GREEN, R.drawable.settings_privacy, R.string.UMessageSectionPrivacyInfo));
        items.add(sectionCell(SECTION_MESSAGES, IconBackgroundColors.ORANGE, R.drawable.settings_chat, R.string.UMessageSectionMessagesInfo));
        items.add(sectionCell(SECTION_STORIES, IconBackgroundColors.PURPLE, R.drawable.settings_features, R.string.UMessageSectionStoriesInfo));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionTools)));
        if (on(org.telegram.messenger.UMessageRemoteFlags.SHAZAM)) items.add(SettingsActivity.SettingCell.Factory.of(SHAZAM, IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.settings_sounds, LocaleController.getString(R.string.UMessageShazam), LocaleController.getString(R.string.UMessageShazamTap)));
        if (on(org.telegram.messenger.UMessageRemoteFlags.INSTAGRAM_BUSINESS)) items.add(SettingsActivity.SettingCell.Factory.of(INSTAGRAM_BUSINESS, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_photos, LocaleController.getString(R.string.UMessageInstagramMiniApp), LocaleController.getString(R.string.UMessageInstagramMiniAppInfo)));
        // Offline chat temporarily disabled.
        // items.add(SettingsActivity.SettingCell.Factory.of(OFFLINE_CHAT, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
        //         R.drawable.msg_secret, LocaleController.getString(R.string.UMessageMeshTitle), LocaleController.getString(R.string.UMessageMeshTap)));
        if (on(org.telegram.messenger.UMessageRemoteFlags.NEARBY_SHARE)) items.add(SettingsActivity.SettingCell.Factory.of(NEARBY_SHARE_PAGE, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.settings_invite, LocaleController.getString(R.string.UMessageNearbyShare),
                LocaleController.getString(UMessageConfig.isNearbyShareEnabled() ? R.string.PasswordOn : R.string.PasswordOff)));
        if (on(org.telegram.messenger.UMessageRemoteFlags.PARENTAL_CONTROL)) items.add(SettingsActivity.SettingCell.Factory.of(PARENTAL, IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.settings_group, LocaleController.getString(R.string.UMessageParental),
                LocaleController.getString(org.telegram.messenger.UMessageParental.isControlled() ? R.string.PasswordOn : R.string.PasswordOff)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageAbout)));
        items.add(SettingsActivity.SettingCell.Factory.of(
                DESIGN,
                IconBackgroundColors.GREEN.top,
                IconBackgroundColors.GREEN.bottom,
                R.drawable.msg_help,
                LocaleController.getString(R.string.UMessageFaq),
                null,
                null
        ));
        items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageUnofficialNote)));
        items.add(UItem.asCustomShadow(versionView));
    }

    private void fillPrivacy(ArrayList<UItem> items) {
        if (on(org.telegram.messenger.UMessageRemoteFlags.GHOST_MODE)) fillGhost(items);

        fillPrivacyRest(items);
    }

    /** The whole ghost mode section: hidden together when the feature is off (admin switch or Safe Mode). */
    private void fillGhost(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageGhostMode)));
        addToggle(items, UMessageConfig.KEY_GHOST_MODE);
        addToggle(items, UMessageConfig.KEY_GHOST_BUTTON);
        final int ghostScope = org.telegram.messenger.UMessageGhostScope.getScope();
        items.add(UItem.asButton(GHOST_SCOPE, LocaleController.getString(R.string.UMessageGhostScope), getGhostScopeName(ghostScope)));
        if (ghostScope == org.telegram.messenger.UMessageGhostScope.SCOPE_ALL) {
            items.add(UItem.asShadow(null));
        } else {
            final int count = org.telegram.messenger.UMessageGhostScope.getUsers(currentAccount).size();
            items.add(UItem.asButton(GHOST_USERS, LocaleController.getString(R.string.UMessageGhostUsers),
                    count == 0 ? LocaleController.getString(R.string.UMessageGhostUsersAdd) : LocaleController.formatPluralString("Users", count)));
            items.add(UItem.asShadow(LocaleController.getString(ghostScope == org.telegram.messenger.UMessageGhostScope.SCOPE_ONLY
                    ? R.string.UMessageGhostScopeInfoOnly : R.string.UMessageGhostScopeInfoExcept)));
        }
    }

    private void fillPrivacyRest(ArrayList<UItem> items) {
        if (on(org.telegram.messenger.UMessageRemoteFlags.PRIVACY_GUARD)) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionProtection)));
            items.add(UItem.asButton(PRIVACY_GUARD, LocaleController.getString(R.string.PrivacyGuard),
                    LocaleController.getString(PrivacyGuardSettings.isEnabled() && OwnerFaceStore.hasOwner() ? R.string.PasswordOn : R.string.PasswordOff)));
            items.add(UItem.asShadow(null));
        }

        if (on(org.telegram.messenger.UMessageRemoteFlags.CHAT_TRASH)) {
            final int trashCount = org.telegram.messenger.UMessageChatTrash.getInstance(currentAccount).getCount();
            items.add(UItem.asButton(CHAT_TRASH, R.drawable.msg_delete, LocaleController.getString(R.string.UMessageChatTrash), trashCount > 0 ? String.valueOf(trashCount) : ""));
            items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageChatTrashSettingsInfo)));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageHiddenChats)));
        addToggle(items, UMessageConfig.KEY_HIDDEN_CHATS);
        if (UMessageConfig.isHiddenChatsManageable()) {
            items.add(UItem.asButton(HIDDEN_CHATS, LocaleController.getString(R.string.UMessageHiddenChats), String.valueOf(UMessageConfig.getHiddenChats(currentAccount).size())));
            items.add(UItem.asButton(HIDDEN_FOLDERS, LocaleController.getString(R.string.UMessageHiddenFolders), String.valueOf(UMessageConfig.getHiddenFolders(currentAccount).size())));
            items.add(UItem.asButton(HIDDEN_ACCESS, LocaleController.getString(R.string.UMessageHiddenAccess), getAccessName(UMessageConfig.getHiddenAccess())));
            items.add(UItem.asButton(HIDDEN_PASSWORD, LocaleController.getString(UMessageConfig.hasHiddenPassword() ? R.string.UMessageHiddenPasswordChange : R.string.UMessageHiddenPasswordSet)));
        }
        items.add(UItem.asShadow(null));
    }

    /**
     * User safety: protection against malware, scams, spam and unwanted content. These switches are on by default
     * (UMessageConfig.isDefaultOn), every check runs on the device, and the user can turn each one off.
     */
    private void fillSecurity(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionSecurity)));
        addToggle(items, UMessageConfig.KEY_BLOCK_APK);
        if (on(org.telegram.messenger.UMessageRemoteFlags.NSFW_FILTER)) items.add(UItem.asButtonCheck(NSFW_FILTER, LocaleController.getString(R.string.UMessageNsfwFilter), LocaleController.getString(R.string.UMessageNsfwFilterInfo))
                .setChecked(org.telegram.messenger.UMessageNsfwFilter.isEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageSecurityFooter)));
        if (org.telegram.messenger.UMessageNsfwFilter.isEnabled() && on(org.telegram.messenger.UMessageRemoteFlags.NSFW_FILTER)) {
            // Telegram-style level slider (like "Text size"), the shadow below explains the chosen level
            final int level = org.telegram.messenger.UMessageNsfwFilter.getLevel();
            final String[] names = new String[NSFW_LEVEL_NAMES.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = getNsfwLevelName(i);
            }
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageNsfwLevel)));
            items.add(UItem.asSlideView(names, level, which -> {
                if (which == org.telegram.messenger.UMessageNsfwFilter.getLevel()) {
                    return;
                }
                if (which < org.telegram.messenger.UMessageNsfwFilter.LEVEL_MEDIUM && org.telegram.messenger.UMessageNsfwFilter.parentalLocked()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.UMessageNsfwParentalLocked)).show();
                    listView.adapter.update(true);
                    return;
                }
                org.telegram.messenger.UMessageNsfwFilter.setLevel(which);
                listView.adapter.update(true);
            }));
            items.add(UItem.asShadow(LocaleController.getString(NSFW_LEVEL_INFOS[level])));
        }

        if (on(org.telegram.messenger.UMessageRemoteFlags.ACCOUNT_SWITCH_PINS)) {
            items.add(UItem.asButton(ACCOUNT_AUTO_SWITCH,
                    LocaleController.getString(R.string.UMessageAccountAutoSwitch),
                    LocaleController.getString(UMessageConfig.hasAccountSwitchPins() ? R.string.UMessageConfigured : R.string.UMessageNotConfigured)));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionStrangers)));
        addToggle(items, UMessageConfig.KEY_STRANGER_PROTECTION);
        if (on(org.telegram.messenger.UMessageRemoteFlags.STRANGER_PROTECTION)) items.add(UItem.asButton(STRANGER_CHATS, LocaleController.getString(R.string.UMessageStrangerChats)));
        addToggle(items, UMessageConfig.KEY_GROUP_SPAM_GUARD);
        items.add(UItem.asShadow(null));
    }

    private void fillMessages(ArrayList<UItem> items) {
        if (!org.telegram.configuration.ConfigurationManager.isSafeModeActive()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionSaved)));
            addToggle(items, UMessageConfig.KEY_SAVE_EDITED);
            addToggle(items, UMessageConfig.KEY_SAVE_DELETED);
            items.add(UItem.asShadow(null));
        }
        addToggle(items, UMessageConfig.KEY_SAVE_VIEW_ONCE);
        if (on(org.telegram.messenger.UMessageRemoteFlags.VIEW_ONCE_SAVE)) items.add(UItem.asButton(KEPT_MEDIA, R.drawable.msg_saved, LocaleController.getString(R.string.UMessageLocalCacheSize)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionAutoReply)));
        if (on(org.telegram.messenger.UMessageRemoteFlags.TEMPLATES)) items.add(UItem.asButton(TEMPLATES, LocaleController.getString(R.string.UMessageTemplates), String.valueOf(UMessageConfig.getTemplates().size())));
        addToggle(items, UMessageConfig.KEY_AUTO_REPLY);
        if (on(org.telegram.messenger.UMessageRemoteFlags.AUTO_REPLY)) items.add(UItem.asButton(AUTO_REPLY_TEXT, LocaleController.getString(R.string.UMessageAutoReplyText)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionConfirm)));
        addToggle(items, UMessageConfig.KEY_CONFIRM_STICKER);
        addToggle(items, UMessageConfig.KEY_CONFIRM_VOICE);
        addToggle(items, UMessageConfig.KEY_CONFIRM_GIF);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionMessageField)));
        addToggle(items, UMessageConfig.KEY_VOICE_INPUT);
        items.add(UItem.asShadow(null));

        if (org.telegram.messenger.UMessageUzbekSpeech.isSupported() && on(org.telegram.messenger.UMessageRemoteFlags.UZBEK_SPEECH)) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSpeechModelTitle)));
            items.add(UItem.asCheck(SPEECH_ENABLED, LocaleController.getString(R.string.UMessageSpeechEnabled)).setChecked(org.telegram.messenger.UMessageUzbekSpeech.isEnabled()));
            final long totalMb = org.telegram.messenger.UMessageUzbekSpeech.TOTAL_BYTES / (1024 * 1024);
            String state;
            if (org.telegram.messenger.UMessageUzbekSpeech.isDownloading()) {
                state = LocaleController.formatString(R.string.UMessageSpeechModelProgress, (int) (org.telegram.messenger.UMessageUzbekSpeech.getProgressBytes() * 100 / org.telegram.messenger.UMessageUzbekSpeech.TOTAL_BYTES));
            } else if (org.telegram.messenger.UMessageUzbekSpeech.isReady()) {
                state = LocaleController.getString(R.string.UMessageSpeechModelDelete);
            } else {
                state = LocaleController.getString(R.string.UMessageSpeechModelDownload) + " · " + totalMb + " MB";
            }
            items.add(UItem.asButton(SPEECH_MODEL, LocaleController.getString(R.string.UMessageSpeechModelRow), state));
            items.add(UItem.asShadow(LocaleController.formatString(R.string.UMessageSpeechModelInfo, totalMb)));
        }
    }

    private void fillReminder(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionReminder)));
        addToggle(items, UMessageConfig.KEY_UNREAD_REMINDER);
        if (on(org.telegram.messenger.UMessageRemoteFlags.UNREAD_REMINDER)) {
            items.add(UItem.asButton(REMINDER_DELAY, LocaleController.getString(R.string.UMessageReminderDelay), formatDelay(UMessageConfig.getReminderDelayMinutes()))
                    .setEnabled(UMessageConfig.isUnreadReminder()));
            items.add(UItem.asButton(REMINDER_SOUND, LocaleController.getString(R.string.UMessageReminderSound), getSoundName(UMessageConfig.getReminderSound()))
                    .setEnabled(UMessageConfig.isUnreadReminder()));
        }
        items.add(UItem.asShadow(null));

        if (on(org.telegram.messenger.UMessageRemoteFlags.FOCUS_MODE)) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageFocus)));
            items.add(UItem.asButton(FOCUS, LocaleController.getString(R.string.UMessageFocus),
                    LocaleController.getString(UMessageConfig.isFocusEnabled() ? R.string.PasswordOn : R.string.PasswordOff)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageFocusInfo)));
        }
    }

    private void fillFolders(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionFolders)));
        addToggle(items, UMessageConfig.KEY_FOLDER_ICONS);
        addToggle(items, UMessageConfig.KEY_HIDE_FOLDER_TABS);
        addToggle(items, UMessageConfig.KEY_ADMIN_FOLDERS);
        items.add(UItem.asShadow(null));
    }

    private void fillStories(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionStories)));
        addToggle(items, UMessageConfig.KEY_STORIES_ANONYMOUS);
        addToggle(items, UMessageConfig.KEY_STORIES_HIDDEN);
        addToggle(items, UMessageConfig.KEY_STORIES_DOWNLOAD);
        items.add(UItem.asShadow(null));
    }

    private void fillCamera(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionVideoMessages)));
        addToggle(items, UMessageConfig.KEY_ROUND_FRONT_CAMERA);
        if (on(org.telegram.messenger.UMessageRemoteFlags.ROUND_EFFECTS)) items.add(UItem.asButton(ROUND_EFFECTS, LocaleController.getString(R.string.UMessageRoundEffects), getRoundEffectsValue()));
        items.add(UItem.asShadow(null));
    }

    private void fillData(ArrayList<UItem> items) {
        // Deletion controls remain available even when remote configuration is restricted.
        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageLocalData)));
        final boolean clearing = localDataBusy || org.telegram.messenger.UMessageLocalData.isClearing();
        final String pending = LocaleController.getString(clearing ? R.string.UMessageLocalDataClearing : R.string.Loading);
        items.add(UItem.asButton(-1, LocaleController.getString(R.string.UMessageLocalCacheSize),
                localDataLoading || clearing ? pending : AndroidUtilities.formatFileSize(localCacheBytes)).setEnabled(false));
        items.add(UItem.asButton(-2, LocaleController.getString(R.string.UMessageLocalHistorySize),
                localDataLoading || clearing ? pending : AndroidUtilities.formatFileSize(localHistoryBytes)).setEnabled(false));
        items.add(UItem.asButton(LOCAL_DATA_CLEAR, LocaleController.getString(R.string.UMessageLocalDataClear))
                .setEnabled(!localDataLoading && !clearing));
        items.add(UItem.asButton(LOCAL_CACHE, LocaleController.getString(R.string.UMessageGeneralCache)));
        items.add(UItem.asShadow(LocaleController.getString(localDataError ? R.string.UMessageLocalDataError : R.string.UMessageLocalDataInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionChannels)));
        addToggle(items, UMessageConfig.KEY_AUTO_APPROVE);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageSectionDownload)));
        addToggle(items, UMessageConfig.KEY_STOP_AUTODOWNLOAD);
        items.add(UItem.asShadow(null));

    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case DESIGN: // FAQ
                presentFragment(new UMessageFaqActivity());
                return;
            case GHOST_SCOPE:
                showGhostScopePicker();
                return;
            case GHOST_USERS:
                openGhostUsers();
                return;
            case LOCAL_TG_PREMIUM:
                UMessagePremiumController.getInstance().setLocalTelegramPremium(!UMessagePremiumController.getInstance().isLocalTelegramPremium());
                listView.adapter.update(true);
                return;
            case ERROR_REPORTS:
                org.telegram.messenger.UMessageErrorReporter.setEnabled(!org.telegram.messenger.UMessageErrorReporter.isEnabled());
                listView.adapter.update(true);
                return;
            case CHAT_GUARD_SHARE:
                org.telegram.messenger.UMessageChatGuard.setSharingEnabled(!org.telegram.messenger.UMessageChatGuard.isSharingEnabled());
                listView.adapter.update(true);
                return;
            case KEPT_MEDIA:
                presentFragment(new UMessageKeptMediaActivity());
                return;
            case LOCAL_DATA_CLEAR:
                showLocalDataConfirmation();
                return;
            case LOCAL_CACHE:
                presentFragment(new CacheControlActivity());
                return;
            case BLOCK_ADS:
            case USE_PROXY:
            case NEARBY_SHARE:
            case UM_PREMIUM: {
                final UMessageFeatureActivity.Switch sw = item.id == BLOCK_ADS ? UMessageFeatureActivity.BLOCK_ADS
                        : item.id == USE_PROXY ? UMessageFeatureActivity.USE_PROXY
                        : item.id == UM_PREMIUM ? UMessageFeatureActivity.UM_PREMIUM : UMessageFeatureActivity.NEARBY_SHARE;
                if (isSwitchClick(view, x)) {
                    sw.toggle(this, () -> listView.adapter.update(true));
                } else {
                    presentFragment(new UMessageFeatureActivity(sw));
                }
                break;
            }
            case SPEECH_ENABLED:
                org.telegram.messenger.UMessageUzbekSpeech.setEnabled(!org.telegram.messenger.UMessageUzbekSpeech.isEnabled());
                listView.adapter.update(true);
                break;
            case SPEECH_MODEL:
                onSpeechModelClick();
                break;
            case TEMPLATES:
                presentFragment(new UMessageTemplatesActivity());
                break;
            case NEARBY_SHARE_PAGE:
                presentFragment(new UMessageFeatureActivity(UMessageFeatureActivity.NEARBY_SHARE));
                break;
            case SHAZAM:
                presentFragment(new UMessageShazamActivity());
                break;
            case INSTAGRAM_BUSINESS:
                presentFragment(new UMessageInstagramMiniAppActivity()); // business tools are in its menu
                break;
            // Offline chat temporarily disabled.
            // case OFFLINE_CHAT:
            //     presentFragment(new UMessageMeshActivity());
            //     break;
            case CHAT_TRASH:
                presentFragment(new UMessageChatTrashActivity());
                break;
            case FOCUS:
                presentFragment(new UMessageFocusActivity());
                break;
            case PARENTAL:
                presentFragment(new UMessageParentalActivity());
                break;
            case AUTO_REPLY_TEXT:
                showAutoReplyEditor();
                break;
            case REMINDER_DELAY:
                showReminderDelayPicker();
                break;
            case REMINDER_SOUND:
                showReminderSoundPicker();
                break;
            case STRANGER_CHATS:
                presentFragment(new UMessageChatsActivity(UMessageChatsActivity.MODE_STRANGERS));
                break;
            case HIDDEN_CHATS:
                UMessageHiddenChats.open(this);
                break;
            case HIDDEN_FOLDERS:
                showHiddenFoldersPicker();
                break;
            case HIDDEN_PASSWORD:
                UMessageHiddenChats.changePassword(this);
                break;
            case DELETE_SERVER_DATA:
                confirmDeleteServerData();
                break;
            case HIDDEN_ACCESS:
                showHiddenAccessPicker();
                break;
            case ACCOUNT_AUTO_SWITCH:
                showAccountAutoSwitchEditor();
                break;
            case ROUND_EFFECTS:
                presentFragment(new UMessageRoundEffectsActivity());
                break;
            case PRIVACY_GUARD:
                presentFragment(new PrivacyGuardSettingsActivity());
                break;
            case NSFW_FILTER:
                if (org.telegram.messenger.UMessageNsfwFilter.parentalLocked()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.UMessageNsfwParentalLocked)).show();
                    break;
                }
                org.telegram.messenger.UMessageNsfwFilter.setEnabled(!org.telegram.messenger.UMessageNsfwFilter.isEnabled());
                listView.adapter.update(true);
                break;
            case NSFW_LEVEL:
                showNsfwLevelPicker();
                break;
            default:
                if (item.id > OPEN_SECTION_BASE && item.id <= OPEN_SECTION_BASE + SECTION_DATA) {
                    presentFragment(new UMessageSettingsActivity(item.id - OPEN_SECTION_BASE));
                    break;
                }
                if (item.id >= TOGGLE_BASE && item.id < TOGGLE_BASE + TOGGLES.length) {
                    final int index = item.id - TOGGLE_BASE;
                    if (!UMessageConfig.isConfigurationAvailable(TOGGLES[index])) {
                        return;
                    }
                    if (isSwitchClick(view, x)) {
                        toggle(this, TOGGLES[index], () -> listView.adapter.update(true));
                    } else {
                        presentFragment(new UMessageFeatureActivity(TOGGLES[index], TOGGLE_TITLES[index], TOGGLE_INFOS[index]));
                    }
                }
                break;
        }
    }

    private void showHiddenFoldersPicker() {
        final ArrayList<MessagesController.DialogFilter> hidden = new ArrayList<>();
        for (MessagesController.DialogFilter filter : MessagesController.getInstance(currentAccount).getDialogFilters()) {
            if (!filter.isDefault() && UMessageConfig.getHiddenFolders(currentAccount).contains(filter.id)) {
                hidden.add(filter);
            }
        }
        if (hidden.isEmpty()) {
            showDialog(new AlertDialog.Builder(getContext(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.UMessageHiddenFolders))
                    .setMessage(LocaleController.getString(R.string.UMessageHiddenFoldersEmpty))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .create());
            return;
        }
        CharSequence[] names = new CharSequence[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) {
            names[i] = hidden.get(i).name;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageHiddenFolders));
        builder.setItems(names, (dialog, which) -> {
            UMessageConfig.setFolderHidden(currentAccount, hidden.get(which).id, false);
            listView.adapter.update(true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_unhide, LocaleController.getString(R.string.UMessageShowFolder)).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** The switch after the divider flips the option, the rest of the row opens its page. */
    static boolean isSwitchClick(View view, float x) {
        return LocaleController.isRTL ? x <= dp(76) : x >= view.getMeasuredWidth() - dp(76);
    }

    private static String getRoundEffectsValue() {
        final int preset = RoundVideoEffects.getConfiguredPreset();
        final String name = RoundVideoEffects.getPresetName(preset);
        if (preset == RoundVideoEffects.PRESET_ORIGINAL) {
            return name;
        }
        return name + " " + RoundVideoEffects.getConfiguredIntensity() + "%";
    }

    private void addToggle(ArrayList<UItem> items, String key) {
        if (!org.telegram.messenger.UMessageRemoteFlags.isKeyEnabled(key)) {
            return;
        }
        final int index = indexOf(key);
        items.add(UItem.asButtonCheck(TOGGLE_BASE + index, LocaleController.getString(TOGGLE_TITLES[index]), LocaleController.getString(TOGGLE_INFOS[index]))
                .setChecked(UMessageConfig.get(key)));
    }

    private static int indexOf(String key) {
        for (int i = 0; i < TOGGLES.length; i++) {
            if (TOGGLES[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** Flips a switch, including the extra work some of them need, then runs {@code done}. */
    static void toggle(BaseFragment fragment, String key, Runnable done) {
        if (!UMessageConfig.isConfigurationAvailable(key)) {
            done.run();
            return;
        }
        final boolean enable = !UMessageConfig.get(key);
        if (UMessageConfig.KEY_ADMIN_FOLDERS.equals(key)) {
            if (enable) {
                UMessageAdminFolders.enable(fragment, created -> {
                    if (created) {
                        UMessageConfig.set(key, true);
                    } else {
                        UMessageAdminFolders.showLimitError(fragment);
                    }
                    done.run();
                });
                return;
            }
            UMessageAdminFolders.disable(fragment.getCurrentAccount());
        }
        UMessageConfig.set(key, enable);
        done.run();
    }

    private void showAccountAutoSwitchEditor() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final int[] accounts = new int[2];
        int count = 0;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT && count < accounts.length; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                accounts[count++] = account;
            }
        }
        if (count < 2) {
            AlertDialog.Builder error = new AlertDialog.Builder(context, getResourceProvider());
            error.setTitle(LocaleController.getString(R.string.UMessageAccountAutoSwitch));
            error.setMessage(LocaleController.getString(R.string.UMessageAccountAutoSwitchNeedTwo));
            error.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(error.create());
            return;
        }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(4), dp(24), 0);

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.UMessageAccountAutoSwitchInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        info.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.bottomMargin = dp(16);
        container.addView(info, infoParams);

        EditTextBoldCursor[] fields = new EditTextBoldCursor[2];
        for (int i = 0; i < fields.length; i++) {
            TextView label = new TextView(context);
            String name = UserObject.getUserName(UserConfig.getInstance(accounts[i]).getCurrentUser());
            label.setText(LocaleController.formatString(R.string.UMessageAccountAutoSwitchAccount, i + 1, name));
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setTypeface(AndroidUtilities.bold());
            label.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            container.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            fields[i] = createAccountPinField(context, i == fields.length - 1);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            fieldParams.bottomMargin = dp(i == fields.length - 1 ? 4 : 14);
            container.addView(fields[i], fieldParams);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageAccountAutoSwitch));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (UMessageConfig.hasAccountSwitchPins()) {
            builder.setNeutralButton(LocaleController.getString(R.string.Disable), (dialog, which) -> {
                UMessageConfig.clearAccountSwitchPins();
                listView.adapter.update(true);
            });
        }
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView saveButton = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> {
                String firstPin = fields[0].getText().toString();
                String secondPin = fields[1].getText().toString();
                if (firstPin.length() != 4) {
                    fields[0].setError(LocaleController.getString(R.string.UMessageAccountAutoSwitchFourDigits));
                    fields[0].requestFocus();
                    return;
                }
                if (secondPin.length() != 4) {
                    fields[1].setError(LocaleController.getString(R.string.UMessageAccountAutoSwitchFourDigits));
                    fields[1].requestFocus();
                    return;
                }
                if (firstPin.equals(secondPin)) {
                    fields[1].setError(LocaleController.getString(R.string.UMessageAccountAutoSwitchDifferent));
                    fields[1].requestFocus();
                    return;
                }
                UMessageConfig.setAccountSwitchPins(accounts[0], firstPin, accounts[1], secondPin);
                AndroidUtilities.hideKeyboard(fields[1]);
                dialog.dismiss();
                listView.adapter.update(true);
            });
        }
        fields[0].requestFocus();
        AndroidUtilities.showKeyboard(fields[0]);
    }

    private EditTextBoldCursor createAccountPinField(Context context, boolean last) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.UMessageAccountAutoSwitchPinHint));
        field.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setLineColors(getThemedColor(Theme.key_dialogInputField), getThemedColor(Theme.key_dialogInputFieldActivated), getThemedColor(Theme.key_text_RedBold));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        field.setSingleLine(true);
        field.setImeOptions(last ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        return field;
    }

    private void showAutoReplyEditor() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setText(UMessageConfig.getAutoReplyText());
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.UMessageAutoReplyText));
        field.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setLineColors(getThemedColor(Theme.key_dialogInputField), getThemedColor(Theme.key_dialogInputFieldActivated), getThemedColor(Theme.key_text_RedBold));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMaxLines(6);
        field.setBackground(null);
        field.setPadding(0, dp(6), 0, dp(6));
        FrameLayout container = new FrameLayout(context);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 8, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageAutoReplyText));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String text = field.getText().toString().trim();
            if (!TextUtils.isEmpty(text)) {
                UMessageConfig.setAutoReplyText(text);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            field.setSelection(field.length());
            AndroidUtilities.showKeyboard(field);
        });
        showDialog(dialog);
    }

    private static final int[] ACCESS = {
            UMessageConfig.HIDDEN_ACCESS_HIDE_ICON,
            UMessageConfig.HIDDEN_ACCESS_NEW_CHAT_LONG_PRESS,
            UMessageConfig.HIDDEN_ACCESS_EDIT_LONG_PRESS
    };

    private String getAccessName(int access) {
        switch (access) {
            case UMessageConfig.HIDDEN_ACCESS_NEW_CHAT_LONG_PRESS:
                return LocaleController.getString(R.string.UMessageHiddenAccessNewChat);
            case UMessageConfig.HIDDEN_ACCESS_EDIT_LONG_PRESS:
                return LocaleController.getString(R.string.UMessageHiddenAccessEdit);
            default:
                return LocaleController.getString(R.string.UMessageHiddenAccessIcon);
        }
    }

    private void showHiddenAccessPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] titles = new CharSequence[ACCESS.length];
        for (int i = 0; i < ACCESS.length; i++) {
            titles[i] = getAccessName(ACCESS[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageHiddenAccess));
        builder.setItems(titles, (dialog, which) -> {
            UMessageConfig.setHiddenAccess(ACCESS[which]);
            listView.adapter.update(true);
        });
        showDialog(builder.create());
    }

    private static final int[] NSFW_LEVEL_NAMES = {R.string.UMessageNsfwLevelLow, R.string.UMessageNsfwLevelMedium, R.string.UMessageNsfwLevelHigh};
    private static final int[] NSFW_LEVEL_INFOS = {R.string.UMessageNsfwLevelLowInfo, R.string.UMessageNsfwLevelMediumInfo, R.string.UMessageNsfwLevelHighInfo};

    private String getNsfwLevelName(int level) {
        return LocaleController.getString(NSFW_LEVEL_NAMES[level]);
    }

    private void showNsfwLevelPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] titles = new CharSequence[NSFW_LEVEL_NAMES.length];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = getNsfwLevelName(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageNsfwLevel));
        builder.setItems(titles, (dialog, which) -> {
            if (which < org.telegram.messenger.UMessageNsfwFilter.LEVEL_MEDIUM && org.telegram.messenger.UMessageNsfwFilter.parentalLocked()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.UMessageNsfwParentalLocked)).show();
                return;
            }
            org.telegram.messenger.UMessageNsfwFilter.setLevel(which);
            listView.adapter.update(true);
        });
        showDialog(builder.create());
    }

    private String formatDelay(int minutes) {
        return LocaleController.formatPluralString("Minutes", minutes);
    }

    private String getGhostScopeName(int scope) {
        switch (scope) {
            case org.telegram.messenger.UMessageGhostScope.SCOPE_ONLY:
                return LocaleController.getString(R.string.UMessageGhostScopeOnly);
            case org.telegram.messenger.UMessageGhostScope.SCOPE_EXCEPT:
                return LocaleController.getString(R.string.UMessageGhostScopeExcept);
            default:
                return LocaleController.getString(R.string.UMessageGhostScopeAll);
        }
    }

    private void showGhostScopePicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] titles = new CharSequence[3];
        for (int i = 0; i < 3; i++) {
            titles[i] = getGhostScopeName(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageGhostScope));
        builder.setItems(titles, (dialog, which) -> {
            if (which == org.telegram.messenger.UMessageGhostScope.getScope()) {
                return;
            }
            org.telegram.messenger.UMessageGhostScope.setScope(which);
            listView.adapter.update(true);
            if (which != org.telegram.messenger.UMessageGhostScope.SCOPE_ALL
                    && org.telegram.messenger.UMessageGhostScope.getUsers(currentAccount).isEmpty()) {
                openGhostUsers();
            }
        });
        showDialog(builder.create());
    }

    private void openGhostUsers() {
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("isAlwaysShare", true);
        args.putInt("chatAddType", 1);
        GroupCreateActivity fragment = new GroupCreateActivity(args);
        fragment.setTitle(LocaleController.getString(R.string.UMessageGhostUsers));
        fragment.select(new ArrayList<>(org.telegram.messenger.UMessageGhostScope.getUsers(currentAccount)), false, false);
        fragment.setDelegate((premium, miniapps, ids) -> {
            org.telegram.messenger.UMessageGhostScope.setUsers(currentAccount, ids);
            listView.adapter.update(true);
        });
        presentFragment(fragment);
    }

    private void showReminderDelayPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final int[] delays = UMessageConfig.REMINDER_DELAYS;
        final CharSequence[] titles = new CharSequence[delays.length];
        for (int i = 0; i < delays.length; i++) {
            titles[i] = formatDelay(delays[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageReminderDelay));
        builder.setItems(titles, (dialog, which) -> {
            UMessageConfig.setReminderDelayMinutes(delays[which]);
            listView.adapter.update(true);
        });
        showDialog(builder.create());
    }

    private static final int[] SOUNDS = {
            UMessageConfig.REMINDER_SOUND_NOTIFICATION,
            UMessageConfig.REMINDER_SOUND_ALARM,
            UMessageConfig.REMINDER_SOUND_RINGTONE,
            UMessageConfig.REMINDER_SOUND_NONE
    };

    private String getSoundName(int sound) {
        switch (sound) {
            case UMessageConfig.REMINDER_SOUND_ALARM:
                return LocaleController.getString(R.string.UMessageSoundAlarm);
            case UMessageConfig.REMINDER_SOUND_RINGTONE:
                return LocaleController.getString(R.string.UMessageSoundRingtone);
            case UMessageConfig.REMINDER_SOUND_NONE:
                return LocaleController.getString(R.string.UMessageSoundNone);
            default:
                return LocaleController.getString(R.string.UMessageSoundNotification);
        }
    }

    private void showReminderSoundPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] titles = new CharSequence[SOUNDS.length];
        for (int i = 0; i < SOUNDS.length; i++) {
            titles[i] = getSoundName(SOUNDS[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UMessageReminderSound));
        builder.setItems(titles, (dialog, which) -> {
            UMessageConfig.setReminderSound(SOUNDS[which]);
            listView.adapter.update(true);
        });
        showDialog(builder.create());
    }

    private void updateActionBarVisible(boolean animated) {
        if (listView == null || actionBarBackground == null || actionBar.getTitlesContainer() == null) {
            return;
        }
        boolean visible = true;
        if (!visible && listView.getChildCount() > 0) {
            final View firstChild = listView.getChildAt(0);
            visible = listView.getChildAdapterPosition(firstChild) > 0
                    || firstChild.getY() + firstChild.getHeight() < actionBar.getHeight();
        }
        if (actionBarVisible == visible && animated) {
            return;
        }
        actionBarVisible = visible;
        if (actionBarVisibleAnimator != null) {
            actionBarVisibleAnimator.cancel();
            actionBarVisibleAnimator = null;
        }
        final float target = visible ? 1f : 0f;
        if (!animated) {
            actionBar.getTitlesContainer().setAlpha(target);
            actionBarBackground.setAlpha(target);
            return;
        }
        actionBarVisibleAnimator = ValueAnimator.ofFloat(actionBar.getTitlesContainer().getAlpha(), target);
        actionBarVisibleAnimator.addUpdateListener(animation -> {
            final float value = (float) animation.getAnimatedValue();
            actionBar.getTitlesContainer().setAlpha(value);
            actionBarBackground.setAlpha(value);
        });
        actionBarVisibleAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        actionBarVisibleAnimator.setDuration(420);
        actionBarVisibleAnimator.start();
    }

    private final Runnable speechProgress = new Runnable() {
        @Override
        public void run() {
            if (listView != null) listView.adapter.update(false);
            if (org.telegram.messenger.UMessageUzbekSpeech.isDownloading()) AndroidUtilities.runOnUIThread(this, 700);
        }
    };

    /** Download / cancel / delete the offline speech model. */
    private void onSpeechModelClick() {
        if (org.telegram.messenger.UMessageUzbekSpeech.isDownloading()) {
            org.telegram.messenger.UMessageUzbekSpeech.cancelDownload();
            AndroidUtilities.runOnUIThread(speechProgress, 500);
        } else if (org.telegram.messenger.UMessageUzbekSpeech.isReady()) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            b.setTitle(LocaleController.getString(R.string.UMessageSpeechModelDelete));
            b.setMessage(LocaleController.getString(R.string.UMessageSpeechModelDeleteInfo));
            b.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                org.telegram.messenger.UMessageUzbekSpeech.delete();
                listView.adapter.update(true);
            });
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(b.create());
        } else {
            org.telegram.messenger.UMessageUzbekSpeech.download(new org.telegram.messenger.UMessageUzbekSpeech.DownloadListener() {
                @Override
                public void onProgress(long done, long total) {
                }

                @Override
                public void onDone(boolean ok, String error) {
                    if (listView != null) listView.adapter.update(true);
                    if (!ok && error != null && !"cancelled".equals(error)) {
                        BulletinFactory.of(UMessageSettingsActivity.this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageSpeechModelFailed) + " (" + error + ")").show();
                    }
                }
            });
            AndroidUtilities.runOnUIThread(speechProgress, 300);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (section == SECTION_MESSAGES || section == SECTION_DATA) refreshLocalData();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(remoteFlagsObserver, NotificationCenter.umessageRemoteFlagsChanged);
        localDataRequest++;
        org.telegram.messenger.UMessageLocalData.removeListener(localDataListener);
        org.telegram.configuration.ConfigurationManager.removeListener(configurationListener);
        if (actionBarVisibleAnimator != null) {
            actionBarVisibleAnimator.cancel();
            actionBarVisibleAnimator = null;
        }
        super.onFragmentDestroy();
    }

    private void refreshLocalData() {
        if (localDataBusy || org.telegram.messenger.UMessageLocalData.isClearing()) return;
        final int request = ++localDataRequest;
        localDataLoading = true;
        org.telegram.messenger.UMessageLocalData.inspect((cacheBytes, historyBytes, success) -> {
            if (request != localDataRequest) return;
            localDataLoading = false;
            if (success) {
                localCacheBytes = cacheBytes;
                localHistoryBytes = historyBytes;
            } else {
                localDataError = true;
            }
            if (listView != null) listView.adapter.update(false);
        });
    }

    private void showLocalDataConfirmation() {
        if (localDataLoading || localDataBusy || org.telegram.messenger.UMessageLocalData.isClearing() || getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.UMessageLocalDataClear))
                .setMessage(LocaleController.getString(R.string.UMessageLocalDataConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> clearLocalData())
                .create());
    }

    private void clearLocalData() {
        if (localDataBusy) return;
        localDataBusy = true;
        final int request = ++localDataRequest;
        if (listView != null) listView.adapter.update(false);
        org.telegram.messenger.UMessageLocalData.clear((cacheBytes, historyBytes, success) -> {
            if (request != localDataRequest) return;
            localDataBusy = false;
            localDataError = !success;
            if (success) {
                localCacheBytes = cacheBytes;
                localHistoryBytes = historyBytes;
            }
            if (listView != null) listView.adapter.update(false);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                    LocaleController.getString(success ? R.string.UMessageLocalDataCleared : R.string.UMessageLocalDataError)).show();
        });
    }

    private void confirmDeleteServerData() {
        if (getContext() == null) return;
        AlertDialog dialog = new AlertDialog.Builder(getContext(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.UMessageDeleteServerData))
                .setMessage(LocaleController.getString(R.string.UMessageDeleteServerDataConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) ->
                        UMessagePremiumController.getInstance().deleteServerData(currentAccount, ok -> {
                            if (getParentActivity() == null) return;
                            BulletinFactory.of(this).createSimpleBulletin(ok ? R.raw.contact_check : R.raw.error,
                                    LocaleController.getString(ok ? R.string.UMessageDeleteServerDataDone : R.string.UMessageDeleteServerDataFailed)).show();
                        }))
                .create();
        showDialog(dialog);
        android.widget.TextView button = (android.widget.TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
    }

}
