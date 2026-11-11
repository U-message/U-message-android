package org.telegram.messenger;

import android.content.Context;
import android.util.Base64;

import com.google.crypto.tink.HybridDecrypt;
import com.google.crypto.tink.HybridEncrypt;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeySign;
import com.google.crypto.tink.PublicKeyVerify;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.hybrid.HpkeParameters;
import com.google.crypto.tink.hybrid.HpkePublicKey;
import com.google.crypto.tink.hybrid.HybridConfig;
import com.google.crypto.tink.integration.android.AndroidKeysetManager;
import com.google.crypto.tink.signature.Ed25519PublicKey;
import com.google.crypto.tink.signature.SignatureConfig;
import com.google.crypto.tink.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Offline chat cryptography, all through Google Tink (no home-made primitives):
 * HPKE (DHKEM X25519 / HKDF-SHA256 / AES-256-GCM, no prefix) seals a message to the recipient's
 * long-term public key; Ed25519 (no prefix) signs it. The two private keysets live in shared prefs,
 * encrypted by an AES-GCM master key in the Android Keystore (AndroidKeysetManager), and never leave the phone.
 */
public final class UMessageMeshCrypto {

    private static final String PREFS = "ummesh_keys";
    private static final String MASTER_KEY_URI = "android-keystore://ummesh_master_key";
    private static final String HPKE_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW";
    private static final String SIG_TEMPLATE = "ED25519_RAW";

    private static boolean registered;
    private static HpkeParameters hpkeParams;
    private static final HashMap<Long, Identity> identities = new HashMap<>();

    private UMessageMeshCrypto() {
    }

    /** One account's long-term offline identity. */
    public static final class Identity {
        public final long uid;
        /** Raw X25519 public key (32 bytes) used for HPKE. */
        public final byte[] x;
        /** Raw Ed25519 public key (32 bytes). */
        public final byte[] ed;
        final HybridDecrypt decrypt;
        final PublicKeySign signer;

        Identity(long uid, byte[] x, byte[] ed, HybridDecrypt decrypt, PublicKeySign signer) {
            this.uid = uid;
            this.x = x;
            this.ed = ed;
            this.decrypt = decrypt;
            this.signer = signer;
        }

        public byte[] sign(byte[] data) throws GeneralSecurityException {
            return signer.sign(data);
        }

        /** Null when the ciphertext is not for this identity (trial decryption). */
        public byte[] open(byte[] ciphertext, byte[] context) {
            try {
                return decrypt.decrypt(ciphertext, context);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    private static synchronized void init() throws GeneralSecurityException {
        if (registered) {
            return;
        }
        HybridConfig.register();
        SignatureConfig.register();
        hpkeParams = HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .build();
        registered = true;
    }

    /** Loads (or creates on first use) the identity of a logged-in account. Slow (Keystore): call off the UI thread. */
    public static synchronized Identity identity(long uid) throws Exception {
        if (uid == 0) {
            throw new GeneralSecurityException("no user");
        }
        Identity cached = identities.get(uid);
        if (cached != null) {
            return cached;
        }
        init();
        Context context = ApplicationLoader.applicationContext;
        KeysetHandle hpke = new AndroidKeysetManager.Builder()
                .withSharedPref(context, "hpke_" + uid, PREFS)
                .withKeyTemplate(KeyTemplates.get(HPKE_TEMPLATE))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .getKeysetHandle();
        KeysetHandle sig = new AndroidKeysetManager.Builder()
                .withSharedPref(context, "sig_" + uid, PREFS)
                .withKeyTemplate(KeyTemplates.get(SIG_TEMPLATE))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .getKeysetHandle();
        byte[] x = ((HpkePublicKey) hpke.getPublicKeysetHandle().getPrimary().getKey()).getPublicKeyBytes().toByteArray();
        byte[] ed = ((Ed25519PublicKey) sig.getPublicKeysetHandle().getPrimary().getKey()).getPublicKeyBytes().toByteArray();
        Identity identity = new Identity(uid, x, ed,
                hpke.getPrimitive(RegistryConfiguration.get(), HybridDecrypt.class),
                sig.getPrimitive(RegistryConfiguration.get(), PublicKeySign.class));
        identities.put(uid, identity);
        return identity;
    }

    /** HPKE-seals to a raw X25519 public key; context binds the outer packet header. */
    public static byte[] seal(byte[] recipientX, byte[] plaintext, byte[] context) throws GeneralSecurityException {
        init();
        HpkePublicKey key = HpkePublicKey.create(hpkeParams, Bytes.copyFrom(recipientX), null);
        KeysetHandle handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build();
        return handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt.class).encrypt(plaintext, context);
    }

    public static boolean verify(byte[] ed, byte[] signature, byte[] data) {
        if (ed == null || ed.length != 32 || signature == null || signature.length != 64) {
            return false;
        }
        try {
            init();
            Ed25519PublicKey key = Ed25519PublicKey.create(Bytes.copyFrom(ed));
            KeysetHandle handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build();
            handle.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify.class).verify(signature, data);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                if (p != null) {
                    md.update(p);
                }
            }
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }

    public static byte[] unb64(String s) {
        try {
            return s == null ? null : Base64.decode(s, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The text the directory bundle signature covers (same as lib/meshkeys.js). */
    public static byte[] bundleMessage(long uid, String x, String e, long ts) {
        return ("ummesh-keys-v1\n" + uid + "\n" + x + "\n" + e + "\n" + ts).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        return b;
    }

    /**
     * Safety number of a pair: 30 digits, the same on both phones (inputs sorted by uid), derived from both
     * users' Ed25519 and X25519 keys with iterated SHA-256. Equal numbers mean nobody swapped a key.
     */
    public static String safetyNumber(long uidA, byte[] edA, byte[] xA, long uidB, byte[] edB, byte[] xB) {
        if (uidA > uidB) {
            return safetyNumber(uidB, edB, xB, uidA, edA, xA);
        }
        byte[] input = sha256("ummesh-safety-v1".getBytes(StandardCharsets.UTF_8), longBytes(uidA), edA, xA, longBytes(uidB), edB, xB);
        byte[] d = input;
        for (int i = 0; i < 1024; i++) {
            d = sha256(d, input);
        }
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 6; g++) {
            long v = 0;
            for (int i = 0; i < 5; i++) {
                v = (v << 8) | (d[g * 5 + i] & 0xff);
            }
            String part = String.valueOf(v % 100000);
            for (int i = part.length(); i < 5; i++) {
                sb.append('0');
            }
            sb.append(part);
            if (g == 2) {
                sb.append('\n');
            } else if (g != 5) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /** Short fingerprint of one key pair for display ("AB12 CD34 ..."). */
    public static String fingerprint(byte[] ed, byte[] x) {
        byte[] d = sha256(ed, x);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0 && i % 2 == 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", d[i] & 0xff));
        }
        return sb.toString();
    }

    public static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    static byte[] copy(byte[] a) {
        return a == null ? null : Arrays.copyOf(a, a.length);
    }
}
