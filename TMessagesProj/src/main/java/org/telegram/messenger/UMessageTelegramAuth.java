package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proves to U message's server which Telegram account this is, with Telegram's own signature ("Log in with
 * Telegram"): the client authorises the server's domain for the signed-in account (messages.requestUrlAuth /
 * acceptUrlAuth) and Telegram returns the account fields signed with the bot token; the server checks the signature
 * (umessage-premium-server/lib/tg-login.js) and keeps only the id. No message is sent, no chat is opened or hidden.
 * Disclosed on the login screen and in the privacy policy. Needs the bot's domain set in @BotFather (/setdomain).
 */
public final class UMessageTelegramAuth {

    static final String AUTH_URL = UMessagePremiumController.BASE_URL + "/api/tglogin";
    private static final long TIMEOUT_SEC = 15;

    private UMessageTelegramAuth() {
    }

    /** The signed URL, or null (domain not set up, a match code is required, network error). Background thread only. */
    static String fetch(int account) {
        final AtomicReference<String> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final ConnectionsManager cm = ConnectionsManager.getInstance(account);
        final TLRPC.TL_messages_requestUrlAuth request = new TLRPC.TL_messages_requestUrlAuth();
        request.flags |= TLObject.FLAG_2;
        request.url = AUTH_URL;
        cm.sendRequest(request, (response, error) -> {
            if (response instanceof TLRPC.TL_urlAuthResultAccepted) {
                result.set(((TLRPC.TL_urlAuthResultAccepted) response).url);
                done.countDown();
            } else if (response instanceof TLRPC.TL_urlAuthResultRequest && ((TLRPC.TL_urlAuthResultRequest) response).match_codes.isEmpty()) {
                final TLRPC.TL_messages_acceptUrlAuth accept = new TLRPC.TL_messages_acceptUrlAuth();
                accept.flags |= TLObject.FLAG_2;
                accept.url = AUTH_URL;
                accept.write_allowed = false; // the bot may not message the user
                cm.sendRequest(accept, (r2, e2) -> {
                    if (r2 instanceof TLRPC.TL_urlAuthResultAccepted) {
                        result.set(((TLRPC.TL_urlAuthResultAccepted) r2).url);
                    } else {
                        FileLog.d("UMessageTelegramAuth: accept " + (e2 != null ? e2.text : r2));
                    }
                    done.countDown();
                });
            } else {
                // TL_urlAuthResultDefault: the bot's domain is not set; a request with match codes needs the user
                FileLog.d("UMessageTelegramAuth: request " + (error != null ? error.text : response));
                done.countDown();
            }
        });
        try {
            done.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
        return result.get();
    }
}
