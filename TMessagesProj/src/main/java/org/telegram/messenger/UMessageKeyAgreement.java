package org.telegram.messenger;

import com.google.crypto.tink.subtle.Hkdf;
import com.google.crypto.tink.subtle.X25519;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * X25519 + HKDF-SHA256 key agreement for the Friend Map (umf2) and Parental control (v2) handshakes.
 * Only public keys travel through Telegram; the shared key never leaves the two phones.
 */
public final class UMessageKeyAgreement {

    public static final int KEY_LEN = 32;

    private UMessageKeyAgreement() {
    }

    public static byte[] newPrivateKey() {
        return X25519.generatePrivateKey();
    }

    public static byte[] publicKey(byte[] privateKey) {
        try {
            return X25519.publicFromPrivate(privateKey);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Master key of a pair: HKDF(X25519(mine, theirs), salt = label, info = initiatorPub || responderPub).
     * Both sides pass the public keys in the same (initiator, responder) order.
     */
    public static byte[] derive(String label, byte[] myPrivate, byte[] peerPublic, byte[] initiatorPub, byte[] responderPub) {
        if (myPrivate == null || peerPublic == null || peerPublic.length != 32 || initiatorPub == null || responderPub == null) return null;
        byte[] shared = null;
        try {
            shared = X25519.computeSharedSecret(myPrivate, peerPublic);
            byte[] info = new byte[initiatorPub.length + responderPub.length];
            System.arraycopy(initiatorPub, 0, info, 0, initiatorPub.length);
            System.arraycopy(responderPub, 0, info, initiatorPub.length, responderPub.length);
            return Hkdf.computeHkdf("HMACSHA256", shared, label.getBytes(StandardCharsets.UTF_8), info, KEY_LEN);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (shared != null) Arrays.fill(shared, (byte) 0);
        }
    }

    /** A sub-key of a master key, e.g. per direction: info "dir:<from>><to>". */
    public static byte[] subKey(byte[] master, String info) {
        try {
            return Hkdf.computeHkdf("HMACSHA256", master, new byte[0], info.getBytes(StandardCharsets.UTF_8), KEY_LEN);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Short code both people can compare out of band: 12 digits in groups of four, the same on both phones. */
    public static String safetyCode(String label, byte[] pubA, byte[] pubB) {
        if (pubA == null || pubB == null) return null;
        try {
            // order independent, so both sides compute the same code
            byte[] first = compare(pubA, pubB) <= 0 ? pubA : pubB, second = first == pubA ? pubB : pubA;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(label.getBytes(StandardCharsets.UTF_8));
            md.update(first);
            md.update(second);
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                long v = ((h[i * 4] & 0xffL) << 24) | ((h[i * 4 + 1] & 0xffL) << 16) | ((h[i * 4 + 2] & 0xffL) << 8) | (h[i * 4 + 3] & 0xffL);
                if (i > 0) sb.append(' ');
                sb.append(String.format(java.util.Locale.US, "%04d", v % 10000));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int d = (a[i] & 0xff) - (b[i] & 0xff);
            if (d != 0) return d;
        }
        return a.length - b.length;
    }
}
