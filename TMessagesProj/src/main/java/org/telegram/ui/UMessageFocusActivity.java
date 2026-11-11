package org.telegram.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageModernList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/** U message focus mode: during the chosen hours only the allowed folders notify. */
public class UMessageFocusActivity extends BaseFragment {

    private UMessageModernList.RowView startCell, endCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageFocus));
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

        final Theme.ResourcesProvider rp = getResourceProvider();
        content.setPadding(0, 0, 0, AndroidUtilities.dp(16));

        // on/off + hours
        LinearLayout main = UMessageModernList.card(context, rp);
        final UMessageModernList.RowView enabledCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_SWITCH,
                R.drawable.msg_mute, UMessageModernList.INDIGO, LocaleController.getString(R.string.UMessageFocusEnable), null, null,
                UMessageConfig.isFocusEnabled(), true);
        enabledCell.setOnClickListener(v -> {
            final boolean enabled = !UMessageConfig.isFocusEnabled();
            UMessageConfig.setFocusEnabled(enabled);
            enabledCell.setChecked(enabled);
        });
        main.addView(enabledCell);

        startCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_NAV, R.drawable.msg_recent, UMessageModernList.ORANGE,
                LocaleController.getString(R.string.UMessageFocusFrom), null, null, false, true);
        startCell.setOnClickListener(v -> pickTime(true));
        main.addView(startCell);

        endCell = UMessageModernList.row(context, rp, UMessageModernList.KIND_NAV, R.drawable.msg_recent, UMessageModernList.ORANGE,
                LocaleController.getString(R.string.UMessageFocusTo), null, null, false, false);
        endCell.setOnClickListener(v -> pickTime(false));
        main.addView(endCell);
        updateTimes();
        content.addView(UMessageModernList.header(context, LocaleController.getString(R.string.UMessageFocus), rp));
        content.addView(main, UMessageModernList.cardParams());
        content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageFocusInfo), rp));

        // allowed folders
        content.addView(UMessageModernList.header(context, LocaleController.getString(R.string.UMessageFocusFolders), rp));
        final HashSet<Integer> allowed = UMessageConfig.getFocusFolders();
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        if (filters.isEmpty()) {
            content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageFocusNoFolders), rp));
        } else {
            LinearLayout folders = UMessageModernList.card(context, rp);
            for (int i = 0; i < filters.size(); i++) {
                final MessagesController.DialogFilter filter = filters.get(i);
                final String name = filter.isDefault() ? LocaleController.getString(R.string.FilterAllChats) : filter.name;
                final UMessageModernList.RowView cell = UMessageModernList.row(context, rp, UMessageModernList.KIND_SWITCH,
                        R.drawable.msg_folders, UMessageModernList.BLUE, name, null, null, allowed.contains(filter.id), i < filters.size() - 1);
                cell.setOnClickListener(v -> {
                    final HashSet<Integer> set = UMessageConfig.getFocusFolders();
                    final boolean checked = !set.contains(filter.id);
                    if (checked) {
                        set.add(filter.id);
                    } else {
                        set.remove(filter.id);
                    }
                    UMessageConfig.setFocusFolders(set);
                    cell.setChecked(checked);
                });
                folders.addView(cell);
            }
            content.addView(folders, UMessageModernList.cardParams());
        }
        content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageFocusFoldersInfo), rp));

        fragmentView = scrollView;
        return fragmentView;
    }

    private void updateTimes() {
        startCell.setValue(formatTime(UMessageConfig.getFocusStart()));
        endCell.setValue(formatTime(UMessageConfig.getFocusEnd()));
    }

    private static String formatTime(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void pickTime(boolean start) {
        if (getParentActivity() == null) {
            return;
        }
        final int current = start ? UMessageConfig.getFocusStart() : UMessageConfig.getFocusEnd();
        new TimePickerDialog(getParentActivity(), (view, hour, minute) -> {
            final int value = hour * 60 + minute;
            if (start) {
                UMessageConfig.setFocusHours(value, UMessageConfig.getFocusEnd());
            } else {
                UMessageConfig.setFocusHours(UMessageConfig.getFocusStart(), value);
            }
            updateTimes();
        }, current / 60, current % 60, DateFormat.is24HourFormat(getParentActivity())).show();
    }
}
