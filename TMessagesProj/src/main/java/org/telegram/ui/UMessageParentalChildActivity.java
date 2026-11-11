package org.telegram.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageParental;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.UMessageParentalLockView;

/** U message parental control: the rules a parent sets for one child. */
public class UMessageParentalChildActivity extends BaseFragment implements UMessageParental.Listener {

    private static final int[] LIMITS = {0, 30, 60, 90, 120, 180, 240, 360};

    private final long childId;
    private UMessageParental.Rules rules;
    private UMessageModernList.RowView limitCell, fromCell, toCell, pinCell;
    private UMessageModernList.RowView nightCell;
    private LinearLayout reportContainer;

    public UMessageParentalChildActivity(long childId) {
        this.childId = childId;
    }

    @Override
    public boolean onFragmentCreate() {
        UMessageParental.Link l = UMessageParental.getInstance(currentAccount).get(childId);
        if (l == null || l.role != UMessageParental.ROLE_CHILD) return false;
        rules = l.rules != null ? l.rules.copy() : new UMessageParental.Rules();
        UMessageParental.getInstance(currentAccount).addListener(this);
        UMessageParental.getInstance(currentAccount).refreshReport(childId);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        UMessageParental.getInstance(currentAccount).removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onParentalChanged() {
        UMessageParental.Link l = UMessageParental.getInstance(currentAccount).get(childId);
        if (l == null) {
            finishFragment();
            return;
        }
        updateReport();
    }

    @Override
    public View createView(Context context) {
        TLRPC.User user = getMessagesController().getUser(childId);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(user != null ? UserObject.getUserName(user) : LocaleController.getString(R.string.UMessageParental));
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
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        content.setPadding(0, 0, 0, AndroidUtilities.dp(16));
        final Theme.ResourcesProvider rp = getResourceProvider();

        // time
        content.addView(UMessageModernList.header(context, LocaleController.getString(R.string.UMessageParentalTime), rp));
        LinearLayout time = UMessageModernList.card(context, rp);
        limitCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_NAV, R.drawable.msg_recent, UMessageModernList.ORANGE,
                LocaleController.getString(R.string.UMessageParentalDailyLimit), null, null, false, true);
        limitCell.setOnClickListener(v -> pickLimit());
        time.addView(limitCell);

        nightCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_SWITCH, R.drawable.msg_mute, UMessageModernList.INDIGO,
                LocaleController.getString(R.string.UMessageParentalNight), null, null, rules.night, true);
        nightCell.setOnClickListener(v -> {
            rules.night = !rules.night;
            nightCell.setChecked(rules.night);
            apply();
        });
        time.addView(nightCell);
        fromCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_NAV, R.drawable.msg_calendar, UMessageModernList.INDIGO,
                LocaleController.getString(R.string.UMessageFocusFrom), null, null, false, true);
        fromCell.setOnClickListener(v -> pickTime(true));
        time.addView(fromCell);
        toCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_NAV, R.drawable.msg_calendar, UMessageModernList.INDIGO,
                LocaleController.getString(R.string.UMessageFocusTo), null, null, false, false);
        toCell.setOnClickListener(v -> pickTime(false));
        time.addView(toCell);
        content.addView(time, UMessageModernList.cardParams());
        content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageParentalNightInfo), rp));

        // PIN
        LinearLayout pin = UMessageModernList.card(context, rp);
        pinCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_NAV, R.drawable.msg_pin_code, UMessageModernList.GRAPHITE,
                LocaleController.getString(R.string.UMessageParentalPin), null, null, false, false);
        pinCell.setOnClickListener(v -> UMessageParentalLockView.askPin(getParentActivity(), LocaleController.getString(R.string.UMessageParentalPinEnter), value -> {
            rules.pin = UMessageParental.hashPin(childId, value);
            apply();
        }));
        pin.addView(pinCell);
        content.addView(UMessageModernList.footer(context, null, rp));
        content.addView(pin, UMessageModernList.cardParams());
        content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageParentalPinInfo), rp));

        // report: usage, people, channels (with blocking)
        reportContainer = new LinearLayout(context);
        reportContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(reportContainer);

        // remove
        LinearLayout danger = UMessageModernList.card(context, rp);
        final UMessageModernList.RowView remove = UMessageModernList.row(context, rp, UMessageModernList.KIND_ACTION, R.drawable.msg_delete, UMessageModernList.RED,
                LocaleController.getString(R.string.UMessageParentalRemove), null, null, false, false);
        remove.set(UMessageModernList.KIND_ACTION, R.drawable.msg_delete, null, UMessageModernList.RED,
                LocaleController.getString(R.string.UMessageParentalRemove), null, null, false, true, true, false, false, false);
        remove.setOnClickListener(v -> confirmRemove(user));
        danger.addView(remove);
        content.addView(UMessageModernList.footer(context, null, rp));
        content.addView(danger, UMessageModernList.cardParams());
        content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageParentalRulesInfo), rp));

        updateCells();
        updateReport();
        fragmentView = scrollView;
        return fragmentView;
    }

    private void updateCells() {
        limitCell.setValue(rules.limit > 0 ? UMessageParental.formatMinutes(rules.limit) : LocaleController.getString(R.string.PasswordOff));
        fromCell.setValue(UMessageParentalLockView.time(rules.nightFrom));
        toCell.setValue(UMessageParentalLockView.time(rules.nightTo));
        fromCell.setAlpha(rules.night ? 1f : 0.5f);
        toCell.setAlpha(rules.night ? 1f : 0.5f);
        pinCell.setValue(LocaleController.getString(TextUtils.isEmpty(rules.pin) ? R.string.UMessageParentalPinNone : R.string.UMessageParentalPinSet));
    }

    private void updateReport() {
        if (reportContainer == null || getParentActivity() == null) return;
        Context context = getParentActivity();
        final Theme.ResourcesProvider rp = getResourceProvider();
        reportContainer.removeAllViews();
        UMessageParental.Link l = UMessageParental.getInstance(currentAccount).get(childId);
        JSONObject report = l != null ? l.report : null;

        reportContainer.addView(UMessageModernList.header(context, LocaleController.getString(R.string.UMessageParentalReport), rp));
        JSONArray days = report != null ? report.optJSONArray("u") : null;
        if (days != null && days.length() > 0) {
            LinearLayout usage = UMessageModernList.card(context, rp);
            for (int i = 0; i < days.length(); i++) {
                JSONArray d = days.optJSONArray(i);
                if (d == null) continue;
                usage.addView(UMessageModernList.row(context, rp, UMessageModernList.KIND_STATIC, R.drawable.msg_stats, UMessageModernList.BLUE,
                        i == 0 ? LocaleController.getString(R.string.UMessageParentalUsedToday) : dayName(d.optInt(0)), null,
                        UMessageParental.formatMinutes(d.optInt(1)), false, i < days.length() - 1));
            }
            reportContainer.addView(usage, UMessageModernList.cardParams());
        }
        reportContainer.addView(UMessageModernList.footer(context, report == null ? LocaleController.getString(R.string.UMessageParentalReportNone)
                : LocaleController.formatString(R.string.UMessageParentalReportTime, LocaleController.formatDateTime(report.optLong("t") / 1000, true)), rp));
        if (report == null) return;

        JSONArray people = report.optJSONArray("p");
        if (people != null && people.length() > 0) {
            reportContainer.addView(UMessageModernList.header(context, LocaleController.getString(R.string.UMessageParentalTopPeople), rp));
            LinearLayout section = UMessageModernList.card(context, rp);
            for (int i = 0; i < people.length(); i++) {
                JSONObject o = people.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("n") + (o.has("u") ? " (@" + o.optString("u") + ")" : "");
                section.addView(UMessageModernList.row(context, rp, UMessageModernList.KIND_STATIC, R.drawable.msg_openprofile, UMessageModernList.TEAL,
                        name, null, LocaleController.formatString(R.string.UMessageParentalMessagesCount, o.optInt("m")), false, i < people.length() - 1));
            }
            reportContainer.addView(section, UMessageModernList.cardParams());
            reportContainer.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageParentalTopPeopleInfo), rp));
        }

        JSONArray chats = report.optJSONArray("c");
        if (chats != null && chats.length() > 0) {
            reportContainer.addView(UMessageModernList.header(context, LocaleController.formatString(R.string.UMessageParentalChats, chats.length()), rp));
            LinearLayout section = UMessageModernList.card(context, rp);
            for (int i = 0; i < chats.length(); i++) {
                JSONObject o = chats.optJSONObject(i);
                if (o == null) continue;
                final long id = o.optLong("i");
                final boolean channel = "c".equals(o.optString("k"));
                String kind = LocaleController.getString(channel ? R.string.UMessageParentalChannel : R.string.UMessageParentalGroup);
                if (o.has("u")) kind += " · @" + o.optString("u");
                final UMessageModernList.RowView cell = UMessageModernList.row(context, rp, UMessageModernList.KIND_SWITCH,
                        channel ? R.drawable.msg_channel : R.drawable.msg_groups, UMessageModernList.RED,
                        o.optString("n"), kind, null, rules.blocked.contains(id), i < chats.length() - 1);
                cell.setOnClickListener(v -> {
                    boolean block = !rules.blocked.contains(id);
                    if (block && rules.blocked.size() >= UMessageParental.MAX_BLOCKED) return;
                    if (block) rules.blocked.add(id); else rules.blocked.remove(id);
                    cell.setChecked(block);
                    apply();
                });
                section.addView(cell);
            }
            reportContainer.addView(section, UMessageModernList.cardParams());
            reportContainer.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageParentalChatsInfo), rp));
        }
    }

    private static String dayName(int dayKey) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.YEAR, dayKey / 1000);
        c.set(java.util.Calendar.DAY_OF_YEAR, dayKey % 1000);
        return new java.text.SimpleDateFormat("EEEE, d MMM", LocaleController.getInstance().getCurrentLocale()).format(c.getTime());
    }

    private void apply() {
        UMessageParental.getInstance(currentAccount).setRules(childId, rules.copy());
        updateCells();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.UMessageParentalRulesSent)).show();
    }

    private void pickLimit() {
        CharSequence[] items = new CharSequence[LIMITS.length];
        for (int i = 0; i < LIMITS.length; i++) {
            items[i] = LIMITS[i] == 0 ? LocaleController.getString(R.string.PasswordOff) : UMessageParental.formatMinutes(LIMITS[i]);
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.UMessageParentalDailyLimit))
                .setItems(items, (d, which) -> {
                    rules.limit = LIMITS[which];
                    apply();
                })
                .create());
    }

    private void pickTime(boolean from) {
        if (getParentActivity() == null || !rules.night) {
            return;
        }
        int current = from ? rules.nightFrom : rules.nightTo;
        new TimePickerDialog(getParentActivity(), (view, hour, minute) -> {
            if (from) {
                rules.nightFrom = hour * 60 + minute;
            } else {
                rules.nightTo = hour * 60 + minute;
            }
            apply();
        }, current / 60, current % 60, DateFormat.is24HourFormat(getParentActivity())).show();
    }

    private void confirmRemove(TLRPC.User user) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.UMessageParentalRemove))
                .setMessage(LocaleController.formatString(R.string.UMessageParentalRemoveConfirm, user != null ? UserObject.getUserName(user) : ""))
                .setPositiveButton(LocaleController.getString(R.string.UMessageParentalRemove), (d, w) -> {
                    UMessageParental.getInstance(currentAccount).remove(childId);
                    finishFragment();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }
}
