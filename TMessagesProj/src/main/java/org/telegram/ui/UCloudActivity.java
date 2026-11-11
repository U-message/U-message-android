package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UCloudGitHub;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/** U Cloud home: connect GitHub, list projects. */
public class UCloudActivity extends BaseFragment {

    private LinearLayout content;
    private UCloudGitHub github;

    @Override
    public boolean onFragmentCreate() {
        github = new UCloudGitHub(currentAccount);
        return super.onFragmentCreate();
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
        actionBar.setTitle(LocaleController.getString(R.string.UCloud));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
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

    private LinearLayout section(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(layout);
        return layout;
    }

    private TextCell action(Context context, int text, int icon, boolean divider, View.OnClickListener click) {
        TextCell cell = new TextCell(context);
        cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
        cell.setTextAndIcon(LocaleController.getString(text), icon, divider);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(click);
        return cell;
    }

    private void info(Context context, CharSequence text) {
        TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
        cell.setText(text);
        content.addView(cell);
    }

    private void rebuild() {
        if (content == null || getParentActivity() == null) return;
        Context context = getParentActivity();
        content.removeAllViews();

        if (!github.isConnected()) {
            LinearLayout section = section(context);
            section.addView(action(context, R.string.UCloudConnect, R.drawable.msg_link, false, v -> connect()));
            info(context, LocaleController.getString(R.string.UCloudConnectInfo));
            return;
        }

        LinearLayout section = section(context);
        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(R.string.UCloudProjects));
        section.addView(header);
        List<UCloudGitHub.Project> projects = github.projects();
        for (UCloudGitHub.Project project : projects) {
            TextDetailCell cell = new TextDetailCell(context);
            cell.setTextAndValue(project.repo, project.url(), true);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> presentFragment(new UCloudProjectActivity(project)));
            section.addView(cell);
        }
        section.addView(action(context, R.string.UCloudNewProject, R.drawable.msg_add, false, v -> presentFragment(new UCloudRepoActivity())));
        info(context, LocaleController.getString(R.string.UCloudHostingInfo));

        LinearLayout account = section(context);
        TextCell disconnect = action(context, R.string.UCloudDisconnect, R.drawable.msg_delete, false, v -> confirmDisconnect());
        disconnect.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
        account.addView(disconnect);
        info(context, LocaleController.formatString(R.string.UCloudConnectedAs, github.login()));
    }

    private void connect() {
        if (UCloudGitHub.CLIENT_ID.isEmpty()) {
            error(LocaleController.getString(R.string.UCloudNotConfigured));
            return;
        }
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        UCloudGitHub.async(github::requestDeviceCode, (code, error) -> {
            progress.dismiss();
            if (error != null) {
                error(error);
            } else {
                showDeviceCode(code);
            }
        });
    }

    private void showDeviceCode(UCloudGitHub.DeviceCode code) {
        boolean[] cancelled = {false};
        AndroidUtilities.addToClipboard(code.userCode);
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.UCloudConnect))
                .setMessage(LocaleController.formatString(R.string.UCloudDeviceCode, code.userCode))
                .setPositiveButton(LocaleController.getString(R.string.UCloudOpenGitHub), (d, w) -> Browser.openUrlInSystemBrowser(getParentActivity(), code.verificationUri))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
                    cancelled[0] = true;
                    d.dismiss();
                })
                .create();
        dialog.setDismissDialogByButtons(false);
        dialog.setCanceledOnTouchOutside(false);
        showDialog(dialog, d -> cancelled[0] = true);
        UCloudGitHub.async(() -> github.waitForApproval(code, cancelled), (approved, error) -> {
            dialog.dismiss();
            if (error != null) {
                error(error);
            } else if (Boolean.TRUE.equals(approved)) {
                rebuild();
            }
        });
    }

    private void confirmDisconnect() {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.UCloudDisconnect))
                .setMessage(LocaleController.getString(R.string.UCloudDisconnectInfo))
                .setPositiveButton(LocaleController.getString(R.string.UCloudDisconnect), (d, w) -> {
                    github.disconnect();
                    rebuild();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    @Override
    public boolean dismissDialogOnPause(android.app.Dialog dialog) {
        return false; // the code must stay on screen while the user is in the browser
    }

    private void error(String text) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, text, 4).show();
    }
}
