package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageLocalData;
import org.telegram.messenger.UMessageViewOnceStore;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.ui.Components.UniversalRecyclerView;
import java.util.ArrayList;

/** Account-scoped access to retained attachments after the original message expires. */
public class UMessageKeptMediaActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private UniversalRecyclerView listView;
    private final ArrayList<UMessageViewOnceStore.Item> entries = new ArrayList<>();
    private boolean destroyed;
    private UMessageViewOnceStore.Session session;

    @Override public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        // not dialogsNeedReload: it fires on every incoming message and used to close the open dialog
        getNotificationCenter().addObserver(this, NotificationCenter.umessageMessagesChanged);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageLocalCacheSize));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, (items, adapter) -> {
            for (int i = 0; i < entries.size(); i++) {
                UMessageViewOnceStore.Item entry = entries.get(i);
                if (!UMessageViewOnceStore.canAccess(session, entry)) continue;
                items.add(UItem.asButton(i + 1, R.drawable.msg_filehq, entry.peerName + " · " + entry.fileName));
            }
            if (entries.isEmpty()) items.add(UItem.asShadow(LocaleController.getString(R.string.UMessageKeptMediaEmpty)));
            UMessageModernList.apply(items, item -> item.id > 0 ? UMessageModernList.tile(R.drawable.msg_filehq, UMessageModernList.BLUE) : null);
        }, this::onItemClick, null);
        UMessageModernList.setup(listView);
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frame;
        return frame;
    }

    /** FLAG_SECURE while kept media is listed (set on resume, cleared on pause). */
    private FlagSecureReason flagSecure;

    @Override public void onResume() {
        super.onResume();
        if (getParentActivity() != null) {
            if (flagSecure == null) flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
            flagSecure.attach();
        }
        reload();
    }

    @Override public void onPause() {
        super.onPause();
        if (flagSecure != null) flagSecure.detach();
    }

    private void reload() {
        final UMessageViewOnceStore.Session requestSession = UMessageViewOnceStore.captureSession(currentAccount);
        session = requestSession;
        entries.clear();
        if (listView != null) listView.adapter.update(true);
        if (requestSession == null) return;
        final long generation = UMessageLocalData.getGeneration();
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<UMessageViewOnceStore.Item> items = UMessageViewOnceStore.getItems(requestSession);
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || session != requestSession || !requestSession.isCurrent()
                        || generation != UMessageLocalData.getGeneration()) return;
                items.removeIf(item -> !UMessageViewOnceStore.canAccess(requestSession, item));
                entries.clear();
                entries.addAll(items);
                if (listView != null) listView.adapter.update(true);
            });
        });
    }

    @Override public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        getNotificationCenter().removeObserver(this, NotificationCenter.umessageMessagesChanged);
        if (flagSecure != null) flagSecure.detach();
        destroyed = true;
        session = null;
        entries.clear();
        super.onFragmentDestroy();
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appDidLogout) {
            dismissCurrentDialog();
            session = null;
            entries.clear();
            if (listView != null) listView.adapter.update(true);
        } else if (!destroyed && getVisibleDialog() == null) {
            // never reload under (or close) the dialog the user is looking at
            reload();
        }
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        int index = item.id - 1;
        if (index < 0 || index >= entries.size()) return;
        UMessageViewOnceStore.Item entry = entries.get(index);
        final UMessageViewOnceStore.Session actionSession = session;
        if (!UMessageViewOnceStore.canAccess(actionSession, entry)) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.SavedMessages));
        builder.setMessage(entry.fileName);
        builder.setPositiveButton(LocaleController.getString(R.string.Send), (dialog, which) -> UMessageViewOnce.sendToSaved(actionSession, entry));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
