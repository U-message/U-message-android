package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageInstagramBusiness;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** Publishes a photo, video, reel, story or carousel to the connected Instagram account. */
public class UMessageInstagramPublishActivity extends BaseFragment {

    private static final int REQUEST_PICK = 7301;

    private static final int KIND_BASE = 10;
    private static final int ADD_MEDIA = 2;
    private static final int PUBLISH = 3;
    private static final int CANCEL = 4;
    private static final int OPEN = 5;
    private static final int NEW_POST = 6;
    private static final int FILE_BASE = 100;

    private static final String[] KINDS = {
        UMessageInstagramBusiness.KIND_IMAGE, UMessageInstagramBusiness.KIND_VIDEO, UMessageInstagramBusiness.KIND_REELS,
        UMessageInstagramBusiness.KIND_STORY, UMessageInstagramBusiness.KIND_CAROUSEL
    };
    private static final int[] KIND_NAMES = {
        R.string.UMessageInstagramKindImage, R.string.UMessageInstagramKindVideo, R.string.UMessageInstagramKindReels,
        R.string.UMessageInstagramKindStory, R.string.UMessageInstagramKindCarousel
    };

    private UniversalRecyclerView listView;
    private EditTextCell captionCell;
    private LineProgressView progressLine;
    private int kind;
    private final ArrayList<Uri> files = new ArrayList<>();
    private final ArrayList<String> fileNames = new ArrayList<>();
    private UMessageInstagramBusiness.PublishTask task;
    private final Runnable taskListener = this::onTaskChanged;

    private UMessageInstagramBusiness api() {
        return UMessageInstagramBusiness.getInstance(currentAccount);
    }

    private boolean isCarousel() {
        return UMessageInstagramBusiness.KIND_CAROUSEL.equals(KINDS[kind]);
    }

    private boolean isStory() {
        return UMessageInstagramBusiness.KIND_STORY.equals(KINDS[kind]);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.UMessageInstagramPublish));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        captionCell = new EditTextCell(context, getString(R.string.UMessageInstagramCaptionHint), true, false, UMessageInstagramBusiness.CAPTION_MAX, resourceProvider);
        captionCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        progressLine = new LineProgressView(context);
        progressLine.setProgressColor(getThemedColor(Theme.key_featuredStickers_addButton));
        progressLine.setBackColor(getThemedColor(Theme.key_windowBackgroundGray));
        progressLine.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        // back from the list while a publish runs: show that one again
        task = api().getPublishTask();
        if (task != null) {
            task.listener = taskListener;
            progressLine.setProgress(task.progress, false);
        }

        final FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView = content;
    }

    @Override
    public void onFragmentDestroy() {
        // the publish itself goes on (publish_status polling drives it) and can be reopened
        if (task != null) {
            task.listener = null;
        }
        super.onFragmentDestroy();
    }

    private void onTaskChanged() {
        if (fragmentView != null) {
            if (task != null && task.state == UMessageInstagramBusiness.PublishTask.STATE_UPLOADING) {
                progressLine.setProgress(task.progress, true);
            }
            listView.adapter.update(false);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (task != null) {
            fillTask(items);
            return;
        }
        items.add(UItem.asHeader(getString(R.string.UMessageInstagramPublishType)));
        for (int i = 0; i < KINDS.length; i++) {
            items.add(UItem.asRadio(KIND_BASE + i, getString(KIND_NAMES[i])).setChecked(kind == i));
        }
        items.add(UItem.asShadow(kindInfo()));

        items.add(UItem.asHeader(getString(R.string.UMessageInstagramPublishMedia)));
        for (int i = 0; i < fileNames.size(); i++) {
            items.add(UItem.asButton(FILE_BASE + i, R.drawable.msg_delete, fileNames.get(i)));
        }
        if (files.size() < maxFiles()) {
            items.add(UItem.asButton(ADD_MEDIA, R.drawable.msg_addphoto, getString(R.string.UMessageInstagramPublishAdd)).accent());
        }
        items.add(UItem.asShadow(null));

        if (!isStory()) {
            items.add(UItem.asHeader(getString(R.string.UMessageInstagramCaption)));
            items.add(UItem.asCustom(captionCell));
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asButton(PUBLISH, R.drawable.msg_send, getString(R.string.UMessageInstagramPublishNow)).accent().setEnabled(canPublish()));
        items.add(UItem.asShadow(getString(R.string.UMessageInstagramPublishInfo)));
    }

    private void fillTask(ArrayList<UItem> items) {
        switch (task.state) {
            case UMessageInstagramBusiness.PublishTask.STATE_UPLOADING:
                items.add(UItem.asHeader(LocaleController.formatString(R.string.UMessageInstagramUploading, (int) (task.progress * 100))));
                items.add(UItem.asCustom(progressLine, 4));
                items.add(UItem.asButton(CANCEL, getString(R.string.Cancel)).red());
                items.add(UItem.asShadow(getString(R.string.UMessageInstagramKeepOpen)));
                break;
            case UMessageInstagramBusiness.PublishTask.STATE_PROCESSING:
                items.add(UItem.asHeader(getString(R.string.UMessageInstagramProcessing)));
                items.add(UItem.asShadow(getString(R.string.UMessageInstagramKeepOpen)));
                break;
            case UMessageInstagramBusiness.PublishTask.STATE_PUBLISHED:
                items.add(UItem.asHeader(getString(R.string.UMessageInstagramPublished)));
                if (task.permalink != null) {
                    items.add(UItem.asButton(OPEN, R.drawable.msg_openin, getString(R.string.UMessageInstagramOpenPost)));
                }
                items.add(UItem.asButton(NEW_POST, R.drawable.msg_addphoto, getString(R.string.UMessageInstagramNewPost)).accent());
                items.add(UItem.asShadow(null));
                break;
            default:
                items.add(UItem.asHeader(getString(R.string.UMessageInstagramPublishFailed)));
                items.add(UItem.asShadow(UMessageInstagramBaseActivity.errorText(task.error)));
                items.add(UItem.asButton(NEW_POST, R.drawable.msg_retry, getString(R.string.UMessageInstagramBackToEditor)).accent());
                items.add(UItem.asShadow(null));
                break;
        }
    }

    private CharSequence kindInfo() {
        if (isCarousel()) {
            return LocaleController.formatString(R.string.UMessageInstagramCarouselInfo, UMessageInstagramBusiness.CAROUSEL_MIN, UMessageInstagramBusiness.CAROUSEL_MAX);
        }
        if (isStory()) {
            return getString(R.string.UMessageInstagramStoryInfo);
        }
        return getString(R.string.UMessageInstagramKindInfo);
    }

    private int maxFiles() {
        return isCarousel() ? UMessageInstagramBusiness.CAROUSEL_MAX : 1;
    }

    private boolean canPublish() {
        return isCarousel() ? files.size() >= UMessageInstagramBusiness.CAROUSEL_MIN && files.size() <= UMessageInstagramBusiness.CAROUSEL_MAX : files.size() == 1;
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        final int id = item.id;
        if (id >= FILE_BASE) {
            final int index = id - FILE_BASE;
            if (index < files.size()) {
                files.remove(index);
                fileNames.remove(index);
                listView.adapter.update(true);
            }
        } else if (id >= KIND_BASE) {
            setKind(id - KIND_BASE);
        } else if (id == ADD_MEDIA) {
            pick();
        } else if (id == PUBLISH) {
            if (canPublish()) {
                publish();
            }
        } else if (id == CANCEL) {
            confirmCancel();
        } else if (id == OPEN) {
            if (task != null && task.permalink != null) {
                Browser.openUrl(getParentActivity(), task.permalink);
            }
        } else if (id == NEW_POST) {
            final boolean published = task != null && task.state == UMessageInstagramBusiness.PublishTask.STATE_PUBLISHED;
            api().clearPublishTask(task);
            task = null;
            if (published) {
                files.clear();
                fileNames.clear();
                captionCell.setText("");
            }
            listView.adapter.update(false);
        }
    }

    private void setKind(int newKind) {
        if (newKind == kind) {
            return;
        }
        kind = newKind;
        // what was picked may not fit the new type
        files.clear();
        fileNames.clear();
        listView.adapter.update(true);
    }

    private void pick() {
        final String key = KINDS[kind];
        final boolean videoOnly = UMessageInstagramBusiness.KIND_VIDEO.equals(key) || UMessageInstagramBusiness.KIND_REELS.equals(key);
        final boolean imageOnly = UMessageInstagramBusiness.KIND_IMAGE.equals(key);
        try {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            if (videoOnly) {
                intent.setType("video/*");
            } else if (imageOnly) {
                intent.setType("image/*");
            } else {
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            }
            if (isCarousel()) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            startActivityForResult(Intent.createChooser(intent, getString(R.string.UMessageInstagramPublishAdd)), REQUEST_PICK);
        } catch (Exception e) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.ErrorOccurred)).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        final ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                addFile(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            addFile(data.getData());
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void addFile(Uri uri) {
        if (uri == null || files.size() >= maxFiles() || files.contains(uri)) {
            return;
        }
        files.add(uri);
        fileNames.add(fileName(uri));
    }

    private String fileName(Uri uri) {
        try (Cursor c = getParentActivity().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (Exception ignore) {
        }
        final String last = uri.getLastPathSegment();
        return last == null ? getString(R.string.UMessageInstagramPublishMedia) : last;
    }

    private void publish() {
        final UMessageInstagramBusiness.PublishTask running = api().getPublishTask();
        if (running != null && running.isRunning()) {
            return;
        }
        final String caption = isStory() || captionCell.getText() == null ? null : captionCell.getText().toString().trim();
        progressLine.setProgress(0, false);
        task = api().startPublish(KINDS[kind], files, caption, taskListener);
        listView.adapter.update(false);
    }

    private void confirmCancel() {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.UMessageInstagramCancelTitle))
            .setMessage(getString(R.string.UMessageInstagramCancelText))
            .setPositiveButton(getString(R.string.UMessageInstagramCancelConfirm), (d, w) -> {
                if (task != null) {
                    task.cancel();
                }
            })
            .setNegativeButton(getString(R.string.UMessageInstagramCancelKeep), null)
            .makeRed(AlertDialog.BUTTON_POSITIVE)
            .create());
    }
}
