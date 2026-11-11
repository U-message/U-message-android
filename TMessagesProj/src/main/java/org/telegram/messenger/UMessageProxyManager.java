package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.util.List;

/**
 * "Use U Message Proxy": while on, the connection goes through the built-in MTProto proxies;
 * if the current one stops connecting, the next one is tried (with backoff). The proxy is applied
 * at runtime only (never written to Telegram's proxy prefs), so the user's own proxy settings stay
 * untouched and always win. All work happens on the UI thread.
 */
public class UMessageProxyManager implements NotificationCenter.NotificationCenterDelegate {

    private static final UMessageProxyManager INSTANCE = new UMessageProxyManager();

    private static final long ATTEMPT_TIMEOUT_MS = 20_000;          // no connection for this long = failed
    private static final long ATTEMPT_TIMEOUT_MAX_MS = 5 * 60_000;  // backoff cap per attempt

    private boolean usingProxy;
    private int proxyIndex;
    private int failedRounds;         // full cycles over all proxies that failed, drives backoff
    private boolean failCheckScheduled;

    private final Runnable failCheck = this::onAttemptTimeout;

    public static void init() {
        INSTANCE.initInternal();
    }

    /** Called when the toggle changes. */
    public static void onSettingChanged() {
        AndroidUtilities.runOnUIThread(INSTANCE::onToggle);
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        // Let the connection stack finish starting before switching it to the proxy.
        AndroidUtilities.runOnUIThread(this::evaluate, 500);
    }

    private static boolean isActive() {
        return UMessageConfig.isProxyFallbackEnabled() && !SharedConfig.isProxyEnabled();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged) {
            // User touched Telegram's own proxy settings; those already applied their proxy (or direct).
            usingProxy = false;
            cancelFailCheck();
            evaluate();
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            evaluate();
        }
    }

    private void onToggle() {
        if (!UMessageConfig.isProxyFallbackEnabled() && usingProxy && !SharedConfig.isProxyEnabled()) {
            usingProxy = false;
            apply();
        }
        failedRounds = 0;
        cancelFailCheck();
        evaluate();
    }

    private void evaluate() {
        if (!isActive() || UMessageProxyConfig.getProxies().isEmpty()) {
            cancelFailCheck();
            return;
        }
        if (!usingProxy) {
            usingProxy = true;
            proxyIndex = 0;
            log("using proxy #1");
            apply();
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
            cancelFailCheck();
            failedRounds = 0;
        } else if (state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            // No network is not a proxy failure.
            cancelFailCheck();
        } else {
            scheduleFailCheck();
        }
    }

    private void onAttemptTimeout() {
        failCheckScheduled = false;
        if (!isActive() || !usingProxy) {
            return;
        }
        List<ProxySettings> proxies = UMessageProxyConfig.getProxies();
        if (proxies.isEmpty()) {
            return;
        }
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating
                || state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            return;
        }
        if (ApplicationLoader.mainInterfacePaused || proxies.size() == 1) {
            // Nothing to switch to (or app in background): keep the proxy, check again later.
            if (proxies.size() == 1 && !ApplicationLoader.mainInterfacePaused) {
                failedRounds++;
            }
            scheduleFailCheck();
            return;
        }
        proxyIndex = (proxyIndex + 1) % proxies.size();
        if (proxyIndex == 0) {
            failedRounds++;
        }
        log("proxy failed, using proxy #" + (proxyIndex + 1));
        apply();
        scheduleFailCheck();
    }

    private void apply() {
        try {
            if (usingProxy) {
                List<ProxySettings> proxies = UMessageProxyConfig.getProxies();
                if (proxyIndex < proxies.size()) {
                    ConnectionsManager.setProxySettings(true, proxies.get(proxyIndex));
                    return;
                }
                usingProxy = false;
            }
            ConnectionsManager.setProxySettings(false, null);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private long attemptTimeout() {
        return Math.min(ATTEMPT_TIMEOUT_MS << Math.min(failedRounds, 4), ATTEMPT_TIMEOUT_MAX_MS);
    }

    private void scheduleFailCheck() {
        if (!failCheckScheduled) {
            failCheckScheduled = true;
            AndroidUtilities.runOnUIThread(failCheck, attemptTimeout());
        }
    }

    private void cancelFailCheck() {
        failCheckScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(failCheck);
    }

    private static void log(String message) {
        // Never log addresses or secrets.
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UMessageProxy: " + message);
        }
    }
}
