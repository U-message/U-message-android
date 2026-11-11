package org.telegram.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UCloudGitHub;
import org.telegram.messenger.UCloudWorkflow;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** New project: pick a repository, see what was detected, deploy. */
public class UCloudRepoActivity extends BaseFragment {

    private LinearLayout list;
    private UCloudGitHub github;
    private List<UCloudGitHub.Repo> repos = new ArrayList<>();
    private String query = "";
    private boolean loading = true;

    @Override
    public boolean onFragmentCreate() {
        github = new UCloudGitHub(currentAccount);
        UCloudGitHub.async(github::repos, (value, error) -> {
            loading = false;
            if (error != null) {
                error(error);
            } else {
                repos = value;
            }
            rebuild();
        });
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UCloudSelectRepo));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        EditText search = new EditText(context);
        search.setHint(LocaleController.getString(R.string.Search));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        search.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        search.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        search.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        search.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                rebuild();
            }
        });
        root.addView(search, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        fragmentView = root;
        rebuild();
        return fragmentView;
    }

    private void rebuild() {
        if (list == null || getParentActivity() == null) return;
        Context context = getParentActivity();
        list.removeAllViews();
        if (loading) {
            TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
            info.setText(LocaleController.getString(R.string.Loading));
            list.addView(info);
            return;
        }
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        for (UCloudGitHub.Repo repo : repos) {
            String full = repo.owner + "/" + repo.name;
            if (!query.isEmpty() && !full.toLowerCase(Locale.ROOT).contains(query)) continue;
            TextDetailCell cell = new TextDetailCell(context);
            cell.setTextAndValue(repo.name, repo.owner + " · " + repo.branch + (repo.isPrivate ? " · " + LocaleController.getString(R.string.UCloudPrivate) : ""), true);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> pick(repo));
            section.addView(cell);
        }
        list.addView(section);
        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.UCloudRepoInfo));
        list.addView(info);
    }

    private void pick(UCloudGitHub.Repo repo) {
        if (!repo.admin) {
            error(LocaleController.getString(R.string.UCloudNeedAdmin));
            return;
        }
        if (repo.isPrivate) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(repo.name)
                    .setMessage(LocaleController.getString(R.string.UCloudPrivateInfo))
                    .setPositiveButton(LocaleController.getString(R.string.UCloudContinue), (d, w) -> analyze(repo))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
            return;
        }
        analyze(repo);
    }

    private void analyze(UCloudGitHub.Repo repo) {
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        UCloudGitHub.async(() -> github.detect(repo), (detected, error) -> {
            progress.dismiss();
            if (error != null) {
                error(error);
                return;
            }
            UCloudWorkflow.Plan plan = detected.plan;
            if (plan.error == UCloudWorkflow.ERR_NEXT_SSR) {
                explain(repo.name, R.string.UCloudErrNextSsr);
            } else if (plan.error == UCloudWorkflow.ERR_NO_BUILD) {
                explain(repo.name, R.string.UCloudErrNoBuild);
            } else if (plan.error != UCloudWorkflow.OK) {
                explain(repo.name, R.string.UCloudErrUnsupported);
            } else {
                confirm(detected);
            }
        });
    }

    private void explain(String title, int message) {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setMessage(LocaleController.getString(message))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());
    }

    private void confirm(UCloudGitHub.Detected detected) {
        UCloudGitHub.Repo repo = detected.repo;
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(repo.name)
                .setMessage(LocaleController.formatString(R.string.UCloudDeployConfirm, detected.plan.framework, repo.branch,
                        UCloudWorkflow.siteUrl(repo.owner, repo.name), UCloudWorkflow.PATH))
                .setPositiveButton(LocaleController.getString(R.string.UCloudDeploy), (d, w) -> create(detected))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void create(UCloudGitHub.Detected detected) {
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        showDialog(progress);
        UCloudGitHub.async(() -> github.createProject(detected), (project, error) -> {
            progress.dismiss();
            if (error != null) {
                error(error);
                return;
            }
            presentFragment(new UCloudProjectActivity(project), true);
        });
    }

    private void error(String text) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, text, 4).show();
    }
}
