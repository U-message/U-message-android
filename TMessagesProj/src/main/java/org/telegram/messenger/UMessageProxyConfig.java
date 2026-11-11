package org.telegram.messenger;

import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Built-in U Message MTProto proxies. Real values come from local.properties. */
public final class UMessageProxyConfig {

    // ---- Built-in proxy: UM_PROXY_1_* in local.properties (never commit real values) ----
    private static final String PROXY_1_IP = BuildConfig.UM_PROXY_1_IP;
    private static final int PROXY_1_PORT = BuildConfig.UM_PROXY_1_PORT;
    private static final String PROXY_1_SECRET = BuildConfig.UM_PROXY_1_SECRET;

    // To add more: declare PROXY_2_* and add a line to BUILT_IN below.
    private static final String[][] BUILT_IN = {
            {PROXY_1_IP, String.valueOf(PROXY_1_PORT), PROXY_1_SECRET},
    };

    // ---- Web (HTTP) proxy for in-app WebViews; MTProto cannot carry web traffic ----
    // Empty host = no web proxy (WebView goes direct).
    public static final String WEB_PROXY_HOST = "";
    public static final int WEB_PROXY_PORT = 0;
    public static final String WEB_PROXY_USER = "";
    public static final String WEB_PROXY_PASS = "";

    public static boolean hasWebProxy() {
        return !WEB_PROXY_HOST.isEmpty() && WEB_PROXY_PORT > 0;
    }

    /** Future source of the list; not fetched yet. Results go to {@link #setRemoteProxies}. */
    public static final String REMOTE_LIST_URL = "https://api.umessage.uz/proxies";

    private static volatile List<ProxySettings> remoteProxies = Collections.emptyList();

    private UMessageProxyConfig() {
    }

    /** Remote list when present, otherwise the built-in one. Invalid/empty entries are skipped. */
    public static List<ProxySettings> getProxies() {
        List<ProxySettings> remote = remoteProxies;
        if (!remote.isEmpty()) {
            return remote;
        }
        ArrayList<ProxySettings> result = new ArrayList<>();
        for (String[] p : BUILT_IN) {
            ProxySettings settings = build(p[0], Utilities.parseInt(p[1]), p[2]);
            if (settings != null) {
                result.add(settings);
            }
        }
        return result;
    }

    public static void setRemoteProxies(List<ProxySettings> proxies) {
        ArrayList<ProxySettings> valid = new ArrayList<>();
        if (proxies != null) {
            for (ProxySettings s : proxies) {
                if (s != null && s.isValid()) {
                    valid.add(s);
                }
            }
        }
        remoteProxies = Collections.unmodifiableList(valid);
    }

    public static ProxySettings build(String ip, int port, String secret) {
        if (ip == null || ip.isEmpty() || secret == null || secret.isEmpty() || port <= 0) {
            return null;
        }
        try {
            ProxySettings settings = ProxySettings.builder()
                    .setType(ProxySettings.Type.MTPROTO)
                    .setAddress(ip)
                    .setPort(port)
                    .setSecret(secret)
                    .build();
            return settings.isValid() ? settings : null;
        } catch (Exception e) {
            return null;
        }
    }
}
