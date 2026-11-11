package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.UMessageParental;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.ui.Components.UMessageParentalLockView;

import java.util.ArrayList;

/** U message parental control: my children (rules I set) and my parents (rules I follow). */
public class UMessageParentalActivity extends BaseFragment implements UMessageParental.Listener {

    private LinearLayout content;
    private UMessageParental controller;

    @Override
    public boolean onFragmentCreate() {
        controller = UMessageParental.getInstance(currentAccount);
        controller.addListener(this);
        controller.scan(true);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        controller.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onParentalChanged() {
        rebuild();
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageParental));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        fragmentView = scrollView;
        rebuild();
        return fragmentView;
    }

    private void rebuild() {
        if (content == null || getParentActivity() == null) return;
        Context context = getParentActivity();
        content.removeAllViews();

        final Theme.ResourcesProvider rp = getResourceProvider();
        content.setPadding(0, 0, 0, AndroidUtilities.dp(16));

        // my parents (shown first: a controlled phone must always see that control is on)
        ArrayList<UMessageParental.Link> parents = controller.getLinks(UMessageParental.ROLE_PARENT);
        if (!parents.isEmpty()) {
            content.addView(UMessageModernList.header(context, LocaleController.getString(R.string.UMessageParentalParents), rp));
            LinearLayout section = UMessageModernList.card(context, rp);
            for (int i = 0; i < parents.size(); i++) {
                UMessageParental.Link l = parents.get(i);
                section.addView(userRow(context, l, i < parents.size() - 1, v -> onParentClick(l)));
            }
            content.addView(section, UMessageModernList.cardParams());
            content.addView(UMessageModernList.footer(context, rulesSummary() + parentSees(), rp));
        }

        // my children
        content.addView(UMessageModernList.header(context, LocaleController.getString(R.string.UMessageParentalChildren), rp));
        LinearLayout section = UMessageModernList.card(context, rp);
        ArrayList<UMessageParental.Link> children = controller.getLinks(UMessageParental.ROLE_CHILD);
        for (UMessageParental.Link l : children) {
            section.addView(userRow(context, l, true, v -> {
                if (l.state == UMessageParental.STATE_ACTIVE) {
                    presentFragment(new UMessageParentalChildActivity(l.uid));
                } else {
                    confirmRemove(l);
                }
            }));
        }
        final UMessageModernList.RowView add = UMessageModernList.row(context, rp, UMessageModernList.KIND_ACTION, R.drawable.msg_contact_add, UMessageModernList.BLUE,
                LocaleController.getString(R.string.UMessageParentalAddChild), null, null, false, false);
        add.set(UMessageModernList.KIND_ACTION, R.drawable.msg_contact_add, null, UMessageModernList.BLUE,
                LocaleController.getString(R.string.UMessageParentalAddChild), null, null, false, true, false, true, false, false);
        add.setOnClickListener(v -> openPicker());
        section.addView(add);
        content.addView(section, UMessageModernList.cardParams());

        content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageParentalInfo), rp));
    }

    private String rulesSummary() {
        UMessageParental.Rules r = UMessageParental.effectiveRules();
        String used = LocaleController.getString(R.string.UMessageParentalUsedToday) + ": " + UMessageParental.formatMinutes((int) (UMessageParental.getUsedTodayMs() / 60_000));
        if (r == null || r.limit == 0 && !r.night && r.blocked.isEmpty()) {
            return LocaleController.getString(R.string.UMessageParentalNoRules) + "\n" + used;
        }
        StringBuilder sb = new StringBuilder();
        if (r.limit > 0) {
            sb.append(LocaleController.getString(R.string.UMessageParentalDailyLimit)).append(": ").append(UMessageParental.formatMinutes(r.limit)).append("\n");
        }
        if (r.night) {
            sb.append(LocaleController.getString(R.string.UMessageParentalNight)).append(": ")
                    .append(UMessageParentalLockView.time(r.nightFrom)).append(" – ").append(UMessageParentalLockView.time(r.nightTo)).append("\n");
        }
        if (!r.blocked.isEmpty()) {
            sb.append(LocaleController.formatString(R.string.UMessageParentalBlockedCount, r.blocked.size())).append("\n");
        }
        return sb.append(used).toString();
    }

    private String parentSees() {
        return "\n\n" + LocaleController.getString(R.string.UMessageParentalChildNotice);
    }

    private View userRow(Context context, UMessageParental.Link l, boolean divider, View.OnClickListener click) {
        TLRPC.User user = getMessagesController().getUser(l.uid);
        UserCell cell = new UserCell(context, 16, 0, false);
        int status = l.state == UMessageParental.STATE_ACTIVE ? R.string.UMessageParentalActive
                : l.state == UMessageParental.STATE_INCOMING ? R.string.UMessageParentalIncoming : R.string.UMessageParentalPending;
        cell.setData(user, user != null ? UserObject.getUserName(user) : String.valueOf(l.uid), LocaleController.getString(status), 0, divider);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(click);
        return cell;
    }

    private String name(long uid) {
        TLRPC.User user = getMessagesController().getUser(uid);
        return user != null ? UserObject.getUserName(user) : String.valueOf(uid);
    }

    private void onParentClick(UMessageParental.Link l) {
        if (l.state == UMessageParental.STATE_INCOMING) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.UMessageParental))
                    .setMessage(LocaleController.formatString(R.string.UMessageParentalAcceptConfirm, name(l.uid)))
                    .setPositiveButton(LocaleController.getString(R.string.UMessageFriendMapAccept), (d, w) -> {
                        controller.accept(l.uid);
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.UMessageParentalAccepted)).show();
                    })
                    .setNegativeButton(LocaleController.getString(R.string.UMessageFriendMapDecline), (d, w) -> controller.remove(l.uid))
                    .create());
            return;
        }
        if (controller.needsPinToRemove(l.uid)) {
            UMessageParentalLockView.askPin(getParentActivity(), LocaleController.getString(R.string.UMessageParentalNeedPinToRemove), pin -> {
                long locked = UMessageConfig.getPinLockRemainingMs(UMessageConfig.PIN_LOCK_PARENTAL);
                if (locked <= 0 && controller.checkPin(l.uid, pin)) {
                    UMessageConfig.resetPinLock(UMessageConfig.PIN_LOCK_PARENTAL);
                    controller.remove(l.uid);
                } else {
                    if (locked <= 0) {
                        locked = UMessageConfig.onPinLockFailed(UMessageConfig.PIN_LOCK_PARENTAL);
                    }
                    BulletinFactory.of(this).createErrorBulletin(locked > 0 ? UMessageConfig.formatPinLock(locked) : LocaleController.getString(R.string.UMessageParentalPinWrong)).show();
                }
            });
        } else {
            confirmRemove(l);
        }
    }

    private void confirmRemove(UMessageParental.Link l) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.UMessageParentalRemove))
                .setMessage(LocaleController.formatString(R.string.UMessageParentalRemoveConfirm, name(l.uid)))
                .setPositiveButton(LocaleController.getString(R.string.UMessageParentalRemove), (d, w) -> controller.remove(l.uid))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("needForwardCount", false);
        ContactsActivity activity = new ContactsActivity(args);
        activity.setDelegate((user, param, a) -> AndroidUtilities.runOnUIThread(() -> {
            if (user == null) return;
            UMessageParental.Link l = controller.get(user.id);
            if (l == null) {
                controller.sendRequest(user.id);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                        LocaleController.formatString(R.string.UMessageParentalRequestSent, UserObject.getFirstName(user))).show();
            } else if (l.role == UMessageParental.ROLE_CHILD && l.state == UMessageParental.STATE_ACTIVE) {
                presentFragment(new UMessageParentalChildActivity(user.id));
            }
        }, 300));
        presentFragment(activity);
    }
}
