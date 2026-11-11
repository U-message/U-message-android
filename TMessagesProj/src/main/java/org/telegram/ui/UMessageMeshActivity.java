package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageMesh;
import org.telegram.messenger.UMessageMeshStore;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UMessageMeshCells;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** "Internetsiz chat": offline mode switch, mesh status and offline conversations; also the contact picker for a new chat. */
public class UMessageMeshActivity extends BaseFragment {

    private static final int TOGGLE = 1;
    private static final int NEW_CHAT = 2;
    private static final int REFRESH = 3;
    private static final int BATTERY = 4;
    private static final int STRANGERS = 5;
    private static final int ROW_BASE = 1000;
    private static final int REQUEST_BASE = 5000;
    private static final int REQUEST_PERMISSIONS = 7481;

    private final boolean pickMode;
    private UniversalRecyclerView listView;
    private ArrayList<UMessageMeshStore.Message> conversations = new ArrayList<>();
    private ArrayList<UMessageMeshStore.PeerKey> keys = new ArrayList<>();
    private ArrayList<UMessageMeshStore.Message> requests = new ArrayList<>();
    private HashMap<Long, Integer> unread = new HashMap<>();
    private final ArrayList<Long> rowUids = new ArrayList<>();
    private final Runnable listener = this::reload;
    private boolean pickerSyncing;
    private String searchQuery;

    public UMessageMeshActivity() {
        this(false);
    }

    public UMessageMeshActivity(boolean pickMode) {
        this.pickMode = pickMode;
    }

    @Override
    public boolean onFragmentCreate() {
        UMessageMesh.addListener(listener);
        if (pickMode) {
            // keys are fetched automatically: nobody should have to press "Refresh keys" to see a contact
            pickerSyncing = true;
            UMessageMesh.syncKeys(currentAccount, true, result -> {
                pickerSyncing = false;
                reload();
            });
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        UMessageMesh.removeListener(listener);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(pickMode ? R.string.UMessageMeshPickContact : R.string.UMessageMeshTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        if (pickMode) {
            ActionBarMenuItem search = actionBar.createMenu().addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onSearchCollapse() {
                            searchQuery = null;
                            update();
                        }

                        @Override
                        public void onTextChanged(EditText editText) {
                            String q = editText.getText().toString().trim();
                            searchQuery = TextUtils.isEmpty(q) ? null : q.toLowerCase();
                            update();
                        }
                    });
            search.setSearchFieldHint(LocaleController.getString(R.string.Search));
            search.setContentDescription(LocaleController.getString(R.string.Search));
        }
        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, this::onItemLongClick);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = contentView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        UMessageMesh.onAppResumed();
        if (!pickMode) {
            UMessageMesh.syncKeys(currentAccount, false);
        }
        reload();
    }

    private long me() {
        return UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void reload() {
        final long me = me();
        Utilities.globalQueue.postRunnable(() -> {
            UMessageMeshStore store = UMessageMeshStore.get();
            ArrayList<UMessageMeshStore.Message> c = store.getConversations(me);
            ArrayList<UMessageMeshStore.Message> r = store.getConversations(me, true);
            ArrayList<UMessageMeshStore.PeerKey> k = store.getKeys(me);
            HashMap<Long, Integer> u = new HashMap<>();
            for (UMessageMeshStore.Message m : c) {
                if (m.unread || !m.out) {
                    u.put(m.peerUid, store.unreadCount(me, m.peerUid));
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                conversations = c;
                requests = r;
                keys = k;
                unread = u;
                update();
            });
        });
    }

    private TLRPC.User user(long uid, String fallbackName) {
        TLRPC.User u = getMessagesController().getUser(uid);
        if (u == null) {
            TLRPC.TL_user stub = new TLRPC.TL_user();
            stub.id = uid;
            stub.first_name = TextUtils.isEmpty(fallbackName) ? "U message" : fallbackName;
            u = stub;
        }
        return u;
    }

    private UMessageMeshStore.PeerKey keyOf(long uid) {
        for (UMessageMeshStore.PeerKey k : keys) {
            if (k.uid == uid) {
                return k;
            }
        }
        return null;
    }

    /** A stranger: their Telegram name only if this app already knows the user, else id + key fingerprint. */
    private String strangerName(long uid) {
        TLRPC.User u = getMessagesController().getUser(uid);
        if (u != null) {
            return org.telegram.messenger.UserObject.getUserName(u);
        }
        UMessageMeshStore.PeerKey k = keyOf(uid);
        String fp = k == null ? "?" : org.telegram.messenger.UMessageMeshCrypto.fingerprint(k.ed, k.x);
        return LocaleController.formatString(R.string.UMessageMeshRequestFrom, String.valueOf(uid), fp);
    }

    private static String preview(UMessageMeshStore.Message m) {
        return m.kind == UMessageMeshStore.KIND_PHOTO ? LocaleController.getString(R.string.UMessageMeshPhoto)
                : m.kind == UMessageMeshStore.KIND_VOICE ? LocaleController.getString(R.string.UMessageMeshVoice) : m.text;
    }

    /** Telegram dialog preview: "You: " in the name colour, media labels in the accent colour. */
    private CharSequence dialogPreview(UMessageMeshStore.Message m) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (m.out) {
            sb.append(LocaleController.getString(R.string.FromYou)).append(": ");
            sb.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_chats_nameMessage)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (m.kind == UMessageMeshStore.KIND_PHOTO || m.kind == UMessageMeshStore.KIND_VOICE) {
            int start = sb.length();
            sb.append(preview(m));
            sb.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_chats_attachMessage)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (m.text != null) {
            sb.append(m.text.replace('\n', ' '));
        }
        return sb;
    }

    private static int ticksOf(UMessageMeshStore.Message m) {
        if (!m.out) {
            return UMessageMeshCells.Ticks.NONE;
        }
        return m.status >= UMessageMeshStore.STATUS_DELIVERED ? UMessageMeshCells.Ticks.DELIVERED
                : m.status == UMessageMeshStore.STATUS_SENT ? UMessageMeshCells.Ticks.SENT : UMessageMeshCells.Ticks.QUEUED;
    }

    private String keyName(long uid) {
        for (UMessageMeshStore.PeerKey k : keys) {
            if (k.uid == uid) {
                return k.name;
            }
        }
        return null;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        rowUids.clear();
        if (pickMode) {
            fillPicker(items);
            return;
        }
        boolean enabled = UMessageMesh.isEnabled();
        String status;
        if (!enabled) {
            status = LocaleController.getString(R.string.PasswordOff);
        } else if (!UMessageMesh.scanning && UMessageMesh.isBatteryGuard() && UMessageMesh.neighbours == 0) {
            status = LocaleController.formatString(R.string.UMessageMeshStatusPaused, UMessageMesh.neighbours);
        } else {
            status = LocaleController.formatString(R.string.UMessageMeshStatus, UMessageMesh.neighbours, (int) UMessageMesh.getRelayedCount());
        }
        items.add(UMessageMeshCells.HeroCell.Factory.of(LocaleController.getString(R.string.UMessageMeshTitle), status, enabled));
        items.add(UMessageMeshCells.ToggleCell.Factory.of(TOGGLE, IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.filled_calls_bluetooth_s, LocaleController.getString(R.string.UMessageMeshMode), null, enabled));
        items.add(UItem.asShadow(enabled ? null : LocaleController.getString(R.string.UMessageMeshStatusOff)));

        if (conversations.isEmpty()) {
            items.add(UMessageMeshCells.EmptyCell.Factory.of(R.raw.filter_no_chats, LocaleController.getString(R.string.UMessageMeshNoChats),
                    LocaleController.getString(R.string.UMessageMeshTap), LocaleController.getString(R.string.UMessageMeshNewChat), false,
                    () -> presentFragment(new UMessageMeshActivity(true))));
            items.add(UItem.asShadow(null));
        } else {
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageMeshChats)));
            items.add(SettingsActivity.SettingCell.Factory.of(NEW_CHAT, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                    R.drawable.msg_add, LocaleController.getString(R.string.UMessageMeshNewChat)));
            for (UMessageMeshStore.Message m : conversations) {
                TLRPC.User u = user(m.peerUid, keyName(m.peerUid));
                Integer count = unread.get(m.peerUid);
                items.add(UMessageMeshCells.DialogCell.Factory.asDialog(ROW_BASE + rowUids.size(), u,
                        UMessageMesh.getName(currentAccount, m.peerUid), dialogPreview(m),
                        LocaleController.stringForMessageListDate(m.ts / 1000), ticksOf(m), count == null ? 0 : count));
                rowUids.add(m.peerUid);
            }
            items.add(UItem.asShadow(null));
        }

        if (!requests.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.UMessageMeshRequests) + " · " + requests.size()));
            for (int i = 0; i < requests.size(); i++) {
                UMessageMeshStore.Message m = requests.get(i);
                TLRPC.TL_user stub = new TLRPC.TL_user();
                stub.id = m.peerUid;
                stub.first_name = strangerName(m.peerUid);
                items.add(UMessageMeshCells.DialogCell.Factory.asDialog(REQUEST_BASE + i, stub, stub.first_name, dialogPreview(m),
                        LocaleController.stringForMessageListDate(m.ts / 1000), UMessageMeshCells.Ticks.NONE, 0));
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageMeshRequestsInfo)));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.Settings)));
        items.add(UMessageMeshCells.ToggleCell.Factory.of(STRANGERS, IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.msg_contacts, LocaleController.getString(R.string.UMessageMeshAcceptStrangers), null, UMessageMesh.isAcceptStrangers()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageMeshAcceptStrangersInfo)));

        items.add(UMessageMeshCells.ToggleCell.Factory.of(BATTERY, IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom,
                R.drawable.settings_power, LocaleController.getString(R.string.UMessageMeshBatteryGuard), null, UMessageMesh.isBatteryGuard()));
        items.add(SettingsActivity.SettingCell.Factory.of(REFRESH, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_retry, LocaleController.getString(R.string.UMessageMeshRefreshKeys), null, String.valueOf(keys.size())));
        items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageMeshKeysInfo)));
    }

    private void fillPicker(ArrayList<UItem> items) {
        HashSet<Long> contacts = new HashSet<>();
        for (TLRPC.TL_contact c : getContactsController().contacts) {
            contacts.add(c.user_id);
        }
        boolean any = false;
        for (UMessageMeshStore.PeerKey k : keys) {
            // contacts, or people we already chat with (accepted); strangers stay in requests
            if (k.uid == me() || (!contacts.contains(k.uid) && !inChats(k.uid))) {
                continue;
            }
            any = true;
            TLRPC.User u = user(k.uid, k.name);
            String name = org.telegram.messenger.UserObject.getUserName(u);
            if (searchQuery != null && (name == null || !name.toLowerCase().contains(searchQuery))) {
                continue;
            }
            boolean verified = k.trusted() && !k.hasPending();
            items.add(UMessageMeshCells.DialogCell.Factory.asContact(ROW_BASE + rowUids.size(), u, name,
                    LocaleController.getString(verified ? R.string.UMessageMeshVerified : R.string.UMessageMeshUnverified), verified));
            rowUids.add(k.uid);
        }
        if (rowUids.isEmpty()) {
            if (pickerSyncing && !any) {
                items.add(UMessageMeshCells.EmptyCell.Factory.of(0, LocaleController.getString(R.string.Loading), null, null, true, null));
            } else if (any) {
                items.add(UMessageMeshCells.EmptyCell.Factory.of(0, LocaleController.getString(R.string.NoResult), null, null, false, null));
            } else {
                items.add(UMessageMeshCells.EmptyCell.Factory.of(R.raw.utyan_empty, null, LocaleController.getString(R.string.UMessageMeshNoKeys), null, false, null));
            }
            items.add(UItem.asShadow(null));
        } else {
            if (pickerSyncing) {
                items.add(UMessageMeshCells.EmptyCell.Factory.of(0, null, null, null, true, null));
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageMeshKeysInfo)));
        }
    }

    private boolean inChats(long uid) {
        for (UMessageMeshStore.Message m : conversations) {
            if (m.peerUid == uid) {
                return true;
            }
        }
        return false;
    }

    private void showRequest(UMessageMeshStore.Message m) {
        if (getParentActivity() == null) {
            return;
        }
        final long uid = m.peerUid;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(strangerName(uid));
        b.setMessage(preview(m));
        b.setPositiveButton(LocaleController.getString(R.string.UMessageMeshKeyAccept), (d, w) -> {
            UMessageMesh.acceptRequest(currentAccount, uid);
            Bundle args = new Bundle();
            args.putLong("uid", uid);
            presentFragment(new UMessageMeshChatActivity(args));
        });
        b.setNeutralButton(LocaleController.getString(R.string.UMessageMeshBlock), (d, w) -> UMessageMesh.blockPeer(currentAccount, uid));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = b.create();
        showDialog(dialog);
        android.widget.TextView block = (android.widget.TextView) dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (block != null) {
            block.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void confirmDelete(long uid) {
        if (getParentActivity() == null) {
            return;
        }
        final long me = me();
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.UMessageMeshDeleteChat));
        b.setMessage(LocaleController.getString(R.string.UMessageMeshDeleteConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> Utilities.globalQueue.postRunnable(() -> {
            UMessageMeshStore.get().deleteConversation(me, uid);
            AndroidUtilities.runOnUIThread(this::reload);
        }));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = b.create();
        showDialog(dialog);
        android.widget.TextView button = (android.widget.TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        if (pickMode) {
            return false;
        }
        if (item.id >= REQUEST_BASE && item.id - REQUEST_BASE < requests.size()) {
            showRequest(requests.get(item.id - REQUEST_BASE));
            return true;
        }
        if (item.id >= ROW_BASE && item.id < REQUEST_BASE && item.id - ROW_BASE < rowUids.size()) {
            confirmDelete(rowUids.get(item.id - ROW_BASE));
            return true;
        }
        return false;
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!pickMode && item.id >= REQUEST_BASE && item.id - REQUEST_BASE < requests.size()) {
            showRequest(requests.get(item.id - REQUEST_BASE));
            return;
        }
        if (item.id >= ROW_BASE && item.id < REQUEST_BASE && item.id - ROW_BASE < rowUids.size()) {
            long uid = rowUids.get(item.id - ROW_BASE);
            Bundle args = new Bundle();
            args.putLong("uid", uid);
            presentFragment(new UMessageMeshChatActivity(args), pickMode);
            return;
        }
        switch (item.id) {
            case TOGGLE:
                if (UMessageMesh.isEnabled()) {
                    UMessageMesh.setEnabled(false);
                } else {
                    enableWithPermissions();
                }
                update();
                break;
            case NEW_CHAT:
                presentFragment(new UMessageMeshActivity(true));
                break;
            case REFRESH:
                UMessageMesh.syncKeys(currentAccount, true, result -> {
                    if (result != null && getParentActivity() != null) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, result).show();
                    }
                });
                break;
            case STRANGERS:
                UMessageMesh.setAcceptStrangers(!UMessageMesh.isAcceptStrangers());
                update();
                break;
            case BATTERY:
                UMessageMesh.setBatteryGuard(!UMessageMesh.isBatteryGuard());
                update();
                break;
        }
    }

    private void enableWithPermissions() {
        if (getParentActivity() == null) {
            return;
        }
        if (UMessageMesh.hasPermissions(getParentActivity())) {
            UMessageMesh.setEnabled(true);
            UMessageMesh.syncKeys(currentAccount, false);
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            getParentActivity().requestPermissions(UMessageMesh.requiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_PERMISSIONS || getParentActivity() == null) {
            return;
        }
        if (UMessageMesh.hasPermissions(getParentActivity())) {
            UMessageMesh.setEnabled(true);
            UMessageMesh.syncKeys(currentAccount, false);
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.UMessageMeshPermissions)).show();
        }
        update();
    }
}
