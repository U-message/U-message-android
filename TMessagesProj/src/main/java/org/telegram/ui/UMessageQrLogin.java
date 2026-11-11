package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.HashMap;

/**
 * Login by QR code (auth.exportLoginToken): the user scans it with Telegram on another device
 * (Settings → Devices → Link Desktop Device), so no SMS / code is needed. Used where Telegram
 * doesn't deliver login codes to U message (e.g. UAE).
 */
public class UMessageQrLogin {

    public interface Delegate {
        void onSuccess(TLRPC.TL_auth_authorization auth);

        /** The account has 2-step verification: continue with the password screen. */
        void onPasswordNeeded();
    }

    /** Retry delay after a network error. */
    private static final int RETRY_MS = 3000;

    private static UMessageQrLogin active;

    /** updateLoginToken: the QR was scanned — export again to receive the authorization. */
    public static void onLoginTokenUpdate(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            org.telegram.messenger.UMessageLoginLog.event(account, "updateLoginToken", "QR scanned on another device");
            UMessageQrLogin qr = active;
            if (qr != null && qr.account == account) {
                AndroidUtilities.cancelRunOnUIThread(qr.poll);
                qr.export();
            }
        });
    }

    private final int account;
    private final Delegate delegate;
    private final ImageView qrView;
    private final TextView statusView;
    private AlertDialog dialog;
    private boolean finished;
    private boolean requesting;
    private String shownUrl;
    private int reqId;
    private int failures; // consecutive errors, for the retry backoff

    private final Runnable poll = this::export;

    public UMessageQrLogin(Context context, int account, Delegate delegate) {
        this.account = account;
        this.delegate = delegate;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        qrView = new ImageView(context);
        qrView.setBackgroundColor(0xffffffff);
        qrView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        layout.addView(qrView, LayoutHelper.createLinear(220, 220, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 12));

        TextView hint = new TextView(context);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        hint.setTextSize(15);
        hint.setText(LocaleController.getString(R.string.UMessageQrLoginHint));
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 4));

        statusView = new TextView(context);
        statusView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        statusView.setTextSize(13);
        statusView.setText(LocaleController.getString(R.string.Loading));
        layout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.UMessageQrLoginTitle))
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnDismissListener(d -> stop());
    }

    public void show() {
        active = this;
        dialog.show();
        export();
    }

    private void stop() {
        finished = true;
        if (active == this) {
            active = null;
        }
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (reqId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(reqId, false);
            reqId = 0;
        }
    }

    private void finish() {
        stop();
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /**
     * Each export invalidates the previous token, so it's called only when the token expires or after
     * updateLoginToken (the scan) — re-exporting every few seconds broke slow scans (AUTH_TOKEN_INVALID2).
     */
    private void export() {
        if (finished || requesting) {
            return;
        }
        requesting = true;
        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (a != account && config.isClientActivated()) {
                req.except_ids.add(config.getClientUserId());
            }
        }
        reqId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            requesting = false;
            onResult(response, error, 0);
        }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void onResult(TLObject response, TLRPC.TL_error error, int dcId) {
        if (finished) {
            return;
        }
        if (error != null) {
            if (error.text != null && error.text.contains("SESSION_PASSWORD_NEEDED")) {
                finish();
                delegate.onPasswordNeeded();
                return;
            }
            long delay;
            if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                int seconds = Utilities.parseInt(error.text);
                delay = Math.max(1, seconds) * 1000L;
                statusView.setText(LocaleController.getString(R.string.FloodWait));
            } else {
                // 3 s, 6 s, 12 s … up to a minute
                delay = Math.min(60_000L, (long) RETRY_MS << Math.min(failures, 5));
                statusView.setText(LocaleController.getString(R.string.ErrorOccurred));
            }
            failures++;
            AndroidUtilities.runOnUIThread(poll, delay);
            return;
        }
        failures = 0;
        if (response instanceof TLRPC.TL_auth_loginToken) {
            TLRPC.TL_auth_loginToken t = (TLRPC.TL_auth_loginToken) response;
            showToken(t.token);
            statusView.setText(LocaleController.getString(R.string.UMessageQrLoginWaiting));
            // refresh right when it expires (also a fallback in case the scan update is missed)
            int left = t.expires - ConnectionsManager.getInstance(account).getCurrentTime();
            AndroidUtilities.runOnUIThread(poll, Math.max(5, Math.min(left, 60)) * 1000L);
        } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
            // the account lives on another DC: import the token there and make it the default DC
            TLRPC.TL_auth_loginTokenMigrateTo migrate = (TLRPC.TL_auth_loginTokenMigrateTo) response;
            TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
            req.token = migrate.token;
            final int dc = migrate.dc_id;
            ConnectionsManager.getInstance(account).setDefaultDatacenterId(dc);
            reqId = ConnectionsManager.getInstance(account).sendRequest(req, (r, e) -> AndroidUtilities.runOnUIThread(() -> {
                reqId = 0;
                onResult(r, e, dc);
            }), null, null, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors, dc, ConnectionsManager.ConnectionTypeGeneric, true);
        } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
            TLRPC.auth_Authorization auth = ((TLRPC.TL_auth_loginTokenSuccess) response).authorization;
            if (auth instanceof TLRPC.TL_auth_authorization) {
                finish();
                delegate.onSuccess((TLRPC.TL_auth_authorization) auth);
            } else {
                statusView.setText(LocaleController.getString(R.string.ErrorOccurred));
            }
        }
    }

    private void showToken(byte[] token) {
        final String url = "tg://login?token=" + Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (url.equals(shownUrl)) {
            return;
        }
        shownUrl = url;
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            Bitmap bitmap = new TelegramQRCodeWriter().encode(url, 768, 768, hints, null);
            qrView.setImageBitmap(bitmap);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
