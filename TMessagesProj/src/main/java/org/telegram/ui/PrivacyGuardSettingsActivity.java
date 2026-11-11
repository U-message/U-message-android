package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.privacyguard.OwnerFaceStore;
import org.telegram.messenger.privacyguard.PrivacyGuardController;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageModernList;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** Settings -> Privacy -> Privacy Guard. */
public class PrivacyGuardSettingsActivity extends BaseFragment {

    private static final int REQUEST_CAMERA = 3772;

    private static final int ENABLED = 1;
    private static final int REGISTER = 2;
    private static final int DELETE = 3;
    private static final int PASSCODE = 4;
    private static final int PASSCODE_REMOVE = 5;
    private static final int MODE_SEGMENT = 10;
    private static final int SENSITIVITY_SEGMENT = 11;
    private static final int ACTION_SEGMENT = 20;
    private static final int OWNER_AWAY = 30;
    private static final int PROTECT_MEDIA = 31;
    private static final int PROTECT_COMPOSER = 32;
    private static final int PROTECT_SCREENSHOTS = 33;

    private UniversalRecyclerView listView;
    /** What to do once the camera permission is granted. */
    private Runnable afterPermission;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PrivacyGuard));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        UMessageModernList.setup(listView);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = contentView;
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        fillRows(items);
        UMessageModernList.apply(items, PrivacyGuardSettingsActivity::tileFor);
    }

    private static int[] tileFor(UItem item) {
        switch (item.id) {
            case ENABLED: return UMessageModernList.tile(R.drawable.menu_privacy, UMessageModernList.GREEN);
            case REGISTER: return UMessageModernList.tile(R.drawable.msg2_permissions, UMessageModernList.BLUE);
            case DELETE: return UMessageModernList.tile(R.drawable.msg_delete, UMessageModernList.RED);
            case PASSCODE: return UMessageModernList.tile(R.drawable.msg_pin_code, UMessageModernList.GRAPHITE);
            case PASSCODE_REMOVE: return UMessageModernList.tile(R.drawable.msg_delete, UMessageModernList.RED);
            case OWNER_AWAY: return UMessageModernList.tile(R.drawable.msg_stories_stealth, UMessageModernList.INDIGO);
            case PROTECT_MEDIA: return UMessageModernList.tile(R.drawable.msg_photos, UMessageModernList.ORANGE);
            case PROTECT_COMPOSER: return UMessageModernList.tile(R.drawable.msg_edit, UMessageModernList.TEAL);
            case PROTECT_SCREENSHOTS: return UMessageModernList.tile(R.drawable.msg_photo_blur, UMessageModernList.PURPLE);
        }
        return null;
    }

    @Override
    public ArrayList<org.telegram.ui.ActionBar.ThemeDescription> getThemeDescriptions() {
        return UMessageModernList.themeDescriptions(fragmentView, listView);
    }

    /*
     * Most important first:
     *   1. master switch, what it does in the footer
     *   2. "When someone looks": blur / lock + hide when I look away   (only while on)
     *   3. "Detection": who triggers it + sensitivity                  (only while on)
     *   4. "Also protect": media, composer, screenshots               (only while on)
     *   5. "Face and passcode": enrollment + fallback passcode        (always: needed to turn on and to delete data)
     */
    private void fillRows(ArrayList<UItem> items) {
        final boolean supported = PrivacyGuardSettings.isSupported();
        final boolean enrolled = OwnerFaceStore.hasOwner();
        final boolean enabled = PrivacyGuardSettings.isEnabled() && enrolled;

        items.add(UItem.asCheck(ENABLED, LocaleController.getString(R.string.PrivacyGuard)).setChecked(enabled).setEnabled(supported));
        items.add(UItem.asShadow(LocaleController.getString(supported ? R.string.PrivacyGuardAbout : R.string.PrivacyGuardUnsupported)));
        if (!supported) {
            return;
        }

        if (enabled) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardWhenNoticed)));
            items.add(UMessageModernList.SegmentedFactory.of(ACTION_SEGMENT, new String[]{
                    LocaleController.getString(R.string.PrivacyGuardActionBlur),
                    LocaleController.getString(R.string.PrivacyGuardActionLock)
            }, PrivacyGuardSettings.getAction() == PrivacyGuardSettings.ACTION_LOCK ? 1 : 0, index ->
                    PrivacyGuardSettings.setAction(index == 1 ? PrivacyGuardSettings.ACTION_LOCK : PrivacyGuardSettings.ACTION_BLUR)));
            items.add(UItem.asButtonCheck(OWNER_AWAY, LocaleController.getString(R.string.PrivacyGuardHideOwnerAway), null)
                    .setChecked(PrivacyGuardSettings.isHideWhenOwnerAway()));
            items.add(UItem.asShadow(null));

            items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardDetection)));
            items.add(UMessageModernList.SegmentedFactory.of(MODE_SEGMENT, new String[]{
                    LocaleController.getString(R.string.PrivacyGuardModeLookingShort),
                    LocaleController.getString(R.string.PrivacyGuardModeAnyFaceShort)
            }, PrivacyGuardSettings.getMode() == PrivacyGuardSettings.MODE_ANY_FACE ? 1 : 0, index ->
                    PrivacyGuardSettings.setMode(index == 1 ? PrivacyGuardSettings.MODE_ANY_FACE : PrivacyGuardSettings.MODE_LOOKING)));
            items.add(UMessageModernList.SegmentedFactory.of(SENSITIVITY_SEGMENT, new String[]{
                    LocaleController.getString(R.string.PrivacyGuardSensitivityLow),
                    LocaleController.getString(R.string.PrivacyGuardSensitivityBalanced),
                    LocaleController.getString(R.string.PrivacyGuardSensitivityHigh)
            }, PrivacyGuardSettings.getSensitivity(), PrivacyGuardSettings::setSensitivity));
            items.add(UItem.asShadow(LocaleController.getString(R.string.PrivacyGuardDetectionInfo)));

            items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardAlsoProtect)));
            items.add(UItem.asButtonCheck(PROTECT_MEDIA, LocaleController.getString(R.string.PrivacyGuardProtectMedia), null)
                    .setChecked(PrivacyGuardSettings.isProtectMedia()));
            items.add(UItem.asButtonCheck(PROTECT_COMPOSER, LocaleController.getString(R.string.PrivacyGuardProtectComposer), null)
                    .setChecked(PrivacyGuardSettings.isProtectComposer()));
            items.add(UItem.asButtonCheck(PROTECT_SCREENSHOTS, LocaleController.getString(R.string.PrivacyGuardProtectScreenshots), null)
                    .setChecked(PrivacyGuardSettings.isProtectScreenshots()));
            items.add(UItem.asShadow(null));
        }

        final boolean hasPasscode = PrivacyGuardSettings.hasGuardPasscode();
        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardFaceAndPasscode)));
        items.add(UItem.asButton(REGISTER, R.drawable.msg2_permissions,
                LocaleController.getString(enrolled ? R.string.PrivacyGuardReregisterFace : R.string.PrivacyGuardRegisterFace),
                LocaleController.getString(enrolled ? R.string.PrivacyGuardFaceConfigured : R.string.PrivacyGuardFaceNotConfigured)));
        items.add(UItem.asButton(PASSCODE, R.drawable.msg_pin_code,
                LocaleController.getString(R.string.PrivacyGuardSetPasscode),
                LocaleController.getString(hasPasscode ? R.string.PrivacyGuardPasscodeSet : R.string.PrivacyGuardFaceNotConfigured)));
        if (enrolled) {
            items.add(UItem.asButton(DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.PrivacyGuardDeleteFace)).red());
        }
        if (hasPasscode) {
            items.add(UItem.asButton(PASSCODE_REMOVE, R.drawable.msg_delete, LocaleController.getString(R.string.PrivacyGuardRemovePasscode)).red());
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PrivacyGuardFaceInfo) + "\n\n" + LocaleController.getString(R.string.PrivacyGuardSetPasscodeInfo)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ENABLED:
                if (!PrivacyGuardSettings.isSupported()) {
                    return;
                }
                if (PrivacyGuardSettings.isEnabled() && OwnerFaceStore.hasOwner()) {
                    PrivacyGuardSettings.setEnabled(false);
                    update();
                } else {
                    withCameraPermission(() -> {
                        if (OwnerFaceStore.hasOwner()) {
                            PrivacyGuardSettings.setEnabled(true);
                            update();
                        } else {
                            presentFragment(new PrivacyGuardEnrollActivity(true));
                        }
                    });
                }
                break;
            case REGISTER:
                withCameraPermission(() -> presentFragment(new PrivacyGuardEnrollActivity(!OwnerFaceStore.hasOwner())));
                break;
            case DELETE:
                confirmDelete();
                break;
            case PASSCODE:
                showPasscodeSetup();
                break;
            case PASSCODE_REMOVE:
                PrivacyGuardSettings.clearGuardPasscode();
                update();
                break;
            case OWNER_AWAY:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_OWNER_AWAY, R.string.PrivacyGuardHideOwnerAway, R.string.PrivacyGuardHideOwnerAwayInfo);
                break;
            case PROTECT_MEDIA:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_PROTECT_MEDIA, R.string.PrivacyGuardProtectMedia, R.string.PrivacyGuardProtectMediaInfo);
                break;
            case PROTECT_COMPOSER:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_PROTECT_COMPOSER, R.string.PrivacyGuardProtectComposer, R.string.PrivacyGuardProtectComposerInfo);
                break;
            case PROTECT_SCREENSHOTS:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_PROTECT_SCREENSHOTS, R.string.PrivacyGuardProtectScreenshots, R.string.PrivacyGuardProtectScreenshotsInfo);
                break;
        }
    }

    private void openOrToggle(View view, float x, String key, int titleRes, int infoRes) {
        if (UMessageSettingsActivity.isSwitchClick(view, x)) {
            toggle(key);
        } else {
            presentFragment(new UMessageFeatureActivity(UMessageFeatureActivity.privacyGuard(key, titleRes, infoRes)));
        }
    }

    private void toggle(String key) {
        PrivacyGuardSettings.set(key, !PrivacyGuardSettings.get(key));
        update();
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.PrivacyGuardDeleteFace));
        builder.setMessage(LocaleController.getString(R.string.PrivacyGuardDeleteFaceConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            PrivacyGuardSettings.setEnabled(false);
            OwnerFaceStore.delete();
            update();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.redPositive();
    }

    private void showPasscodeSetup() {
        if (!(fragmentView instanceof FrameLayout)) {
            return;
        }
        final FrameLayout root = (FrameLayout) fragmentView;
        final org.telegram.ui.Components.PrivacyGuardUnlockView[] view = new org.telegram.ui.Components.PrivacyGuardUnlockView[1];
        view[0] = new org.telegram.ui.Components.PrivacyGuardUnlockView(getParentActivity(), getResourceProvider(),
                org.telegram.ui.Components.PrivacyGuardUnlockView.MODE_CREATE, false,
                new org.telegram.ui.Components.PrivacyGuardUnlockView.Callback() {
            @Override
            public void onUnlocked() {
                if (view[0] != null) root.removeView(view[0]);
                update();
            }

            @Override
            public void onCancel() {
                if (view[0] != null) root.removeView(view[0]);
            }

            @Override
            public void onBiometric() {
            }
        });
        root.addView(view[0], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        view[0].bringToFront();
        view[0].requestFocus();
    }

    private void withCameraPermission(Runnable action) {
        if (PrivacyGuardController.hasCameraPermission()) {
            action.run();
            return;
        }
        final Activity activity = getParentActivity();
        if (activity == null || Build.VERSION.SDK_INT < 23) {
            return;
        }
        afterPermission = action;
        activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        final Runnable action = afterPermission;
        afterPermission = null;
        if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) {
                action.run();
            }
        } else if (getParentActivity() != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            builder.setTitle(LocaleController.getString(R.string.PrivacyGuard));
            builder.setMessage(LocaleController.getString(R.string.PrivacyGuardCameraPermission));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(builder.create());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }
}
