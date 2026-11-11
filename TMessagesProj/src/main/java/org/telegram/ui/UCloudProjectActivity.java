package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

import java.util.ArrayList;
import java.util.List;

/** Project dashboard: site link, deploy, live status, history, logs, rollback. All state is read from GitHub. */
public class UCloudProjectActivity extends BaseFragment {

    private static final long POLL_MS = 5000;

    private final UCloudGitHub.Project project;
    private UCloudGitHub github;
    private LinearLayout content;
    private List<UCloudGitHub.Run> runs = new ArrayList<>();
    private boolean loaded;
    private boolean resumed;
    private String loadError;

    private final Runnable poll = this::refresh;

    public UCloudProjectActivity(UCloudGitHub.Project project) {
        this.project = project;
    }

    @Override
    public boolean onFragmentCreate() {
        github = new UCloudGitHub(currentAccount);
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
        AndroidUtilities.cancelRunOnUIThread(poll);
    }

    @Override
    public void onFragmentDestroy() {
        resumed = false;
        AndroidUtilities.cancelRunOnUIThread(poll);
        super.onFragmentDestroy();
    }

    private void refresh() {
        AndroidUtilities.cancelRunOnUIThread(poll);
        UCloudGitHub.async(() -> github.runs(project), (value, error) -> {
            loaded = true;
            loadError = error;
            if (value != null) runs = value;
            rebuild();
            if (!resumed) return;
            boolean active = false;
            for (UCloudGitHub.Run run : runs) {
                active |= run.active();
            }
            // A fresh commit takes a few seconds to show up as a run, so keep looking while the list is empty too.
            if (active || runs.isEmpty()) {
                AndroidUtilities.runOnUIThread(poll, POLL_MS);
            }
        });
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(project.repo);
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

    private void rebuild() {
        if (content == null || getParentActivity() == null) return;
        Context context = getParentActivity();
        content.removeAllViews();

        LinearLayout site = section(context);
        TextDetailCell url = new TextDetailCell(context);
        url.setTextAndValue(project.url(), statusLine(), true);
        url.setBackground(Theme.getSelectorDrawable(false));
        url.setOnClickListener(v -> Browser.openUrlInSystemBrowser(getParentActivity(), project.url()));
        url.setOnLongClickListener(v -> {
            AndroidUtilities.addToClipboard(project.url());
            BulletinFactory.of(this).createCopyLinkBulletin().show();
            return true;
        });
        site.addView(url);
        TextCell deploy = new TextCell(context);
        deploy.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
        deploy.setTextAndIcon(LocaleController.getString(R.string.UCloudDeployLatest), R.drawable.msg_reset, false);
        deploy.setBackground(Theme.getSelectorDrawable(false));
        deploy.setOnClickListener(v -> deploy(null));
        site.addView(deploy);
        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.formatString(R.string.UCloudProjectInfo, project.framework, project.owner + "/" + project.repo, project.branch));
        content.addView(info);

        LinearLayout history = section(context);
        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(R.string.UCloudDeployments));
        history.addView(header);
        if (loadError != null && runs.isEmpty()) {
            TextInfoPrivacyCell failed = new TextInfoPrivacyCell(context);
            failed.setText(loadError);
            history.addView(failed);
        }
        for (int i = 0; i < runs.size(); i++) {
            UCloudGitHub.Run run = runs.get(i);
            TextDetailCell cell = new TextDetailCell(context);
            cell.setTextAndValue(run.title, statusText(run) + " · " + shortSha(run.sha) + " · " + run.created.replace('T', ' ').replace("Z", ""), i < runs.size() - 1);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> openRun(run));
            history.addView(cell);
        }

        LinearLayout danger = section(context);
        TextCell remove = new TextCell(context);
        remove.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
        remove.setTextAndIcon(LocaleController.getString(R.string.UCloudRemove), R.drawable.msg_delete, false);
        remove.setBackground(Theme.getSelectorDrawable(false));
        remove.setOnClickListener(v -> confirmRemove());
        danger.addView(remove);
        TextInfoPrivacyCell removeInfo = new TextInfoPrivacyCell(context);
        removeInfo.setText(LocaleController.getString(R.string.UCloudRemoveInfo));
        content.addView(removeInfo);
    }

    private String statusLine() {
        if (!loaded) return LocaleController.getString(R.string.Loading);
        if (runs.isEmpty()) return LocaleController.getString(R.string.UCloudNoDeployments);
        return statusText(runs.get(0));
    }

    private static String statusText(UCloudGitHub.Run run) {
        if (run.active()) {
            return LocaleController.getString("queued".equals(run.status) ? R.string.UCloudStatusQueued : R.string.UCloudStatusBuilding);
        }
        if (run.success()) return LocaleController.getString(R.string.UCloudStatusLive);
        if ("cancelled".equals(run.conclusion)) return LocaleController.getString(R.string.UCloudStatusCancelled);
        return LocaleController.getString(R.string.UCloudStatusFailed);
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
    }

    private void deploy(String sha) {
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        UCloudGitHub.async(() -> {
            github.deploy(project, sha);
            return Boolean.TRUE;
        }, (ok, error) -> {
            progress.dismiss();
            if (error != null) {
                error(error);
                return;
            }
            // GitHub takes a moment to list a dispatched run.
            AndroidUtilities.runOnUIThread(poll, 3000);
        });
    }

    private void openRun(UCloudGitHub.Run run) {
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        UCloudGitHub.async(() -> github.jobs(project, run.id), (jobs, error) -> {
            progress.dismiss();
            if (error != null) {
                error(error);
                return;
            }
            showRun(run, jobs);
        });
    }

    private void showRun(UCloudGitHub.Run run, List<UCloudGitHub.Job> jobs) {
        StringBuilder text = new StringBuilder();
        UCloudGitHub.Job logJob = null;
        for (UCloudGitHub.Job job : jobs) {
            text.append(job.name).append('\n');
            for (String[] step : job.steps) {
                text.append(stepMark(step[1])).append(' ').append(step[0]).append('\n');
            }
            text.append('\n');
            // The failing job's log is the useful one; otherwise the build job.
            if (logJob == null || "failure".equals(job.conclusion)) logJob = job;
        }
        UCloudGitHub.Job target = logJob;
        boolean canRollback = !run.active() && run.success() && !runs.isEmpty() && run.id != runs.get(0).id;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity())
                .setTitle(statusText(run))
                .setMessage(text.toString().trim());
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        if (target != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.UCloudLogs), (d, w) -> openLog(target));
        }
        if (canRollback) {
            builder.setNegativeButton(LocaleController.getString(R.string.UCloudRollback), (d, w) -> confirmRollback(run));
        }
        showDialog(builder.create());
    }

    private static String stepMark(String state) {
        if ("success".equals(state)) return "✓";
        if ("failure".equals(state)) return "✕";
        if ("skipped".equals(state) || "cancelled".equals(state)) return "–";
        return "…";
    }

    private void confirmRollback(UCloudGitHub.Run run) {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.UCloudRollback))
                .setMessage(LocaleController.formatString(R.string.UCloudRollbackInfo, shortSha(run.sha)))
                .setPositiveButton(LocaleController.getString(R.string.UCloudRollback), (d, w) -> deploy(run.sha))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void openLog(UCloudGitHub.Job job) {
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        UCloudGitHub.async(() -> github.jobLog(project, job.id), (log, error) -> {
            progress.dismiss();
            if (error != null) {
                error(error);
                return;
            }
            showLog(job.name, log);
        });
    }

    private void showLog(String title, String log) {
        Context context = getParentActivity();
        TextView text = new TextView(context);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        text.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        text.setTextIsSelectable(true);
        text.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        text.setText(log);
        HorizontalScrollView horizontal = new HorizontalScrollView(context);
        horizontal.addView(text);
        ScrollView vertical = new ScrollView(context);
        vertical.addView(horizontal);
        vertical.post(() -> vertical.fullScroll(View.FOCUS_DOWN));
        showDialog(new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(vertical, AndroidUtilities.dp(420))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());
    }

    private void confirmRemove() {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.UCloudRemove))
                .setMessage(LocaleController.getString(R.string.UCloudRemoveInfo))
                .setPositiveButton(LocaleController.getString(R.string.UCloudRemove), (d, w) -> {
                    github.forgetProject(project);
                    finishFragment();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void error(String text) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, text, 4).show();
    }
}
