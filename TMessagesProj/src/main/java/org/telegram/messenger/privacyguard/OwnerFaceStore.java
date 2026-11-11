package org.telegram.messenger.privacyguard;

import android.annotation.TargetApi;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The owner's face templates (identity embeddings, no photos), encrypted with an AES-256-GCM key that never leaves
 * the Android Keystore. Stored in the no-backup directory, so it is not part of cloud or device-transfer backups,
 * and it is never sent anywhere or logged.
 */
@TargetApi(Build.VERSION_CODES.M)
public final class OwnerFaceStore {

    private static final String KEY_ALIAS = "umessage_privacy_guard_owner_v1";
    private static final String FILE_NAME = "umessage_privacy_guard_owner.bin";
    private static final int MAGIC = 0x55504731; // "UPG1"
    private static final int GCM_TAG_BITS = 128;

    private static float[][] cached;

    private OwnerFaceStore() {
    }

    private static File file() {
        return new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), FILE_NAME);
    }

    public static boolean hasOwner() {
        return PrivacyGuardSettings.isSupported() && file().exists();
    }

    /** Decrypted templates, or null. Kept in memory only while Privacy Guard runs; see {@link #clearCache()}. */
    public static synchronized float[][] load() {
        if (cached != null) {
            return cached;
        }
        if (!hasOwner()) {
            return null;
        }
        try {
            byte[] data = new AtomicFile(file()).readFully();
            ByteBuffer in = ByteBuffer.wrap(data);
            if (in.getInt() != MAGIC) {
                return null;
            }
            byte[] iv = new byte[in.get() & 0xff];
            in.get(iv);
            byte[] encrypted = new byte[in.remaining()];
            in.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(encrypted);
            DataInputStream stream = new DataInputStream(new java.io.ByteArrayInputStream(plain));
            final int count = stream.readInt();
            final int dimension = stream.readInt();
            if (count <= 0 || count > 256 || dimension <= 0 || dimension > 4096) {
                return null;
            }
            float[][] templates = new float[count][dimension];
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < dimension; j++) {
                    templates[i][j] = stream.readFloat();
                }
            }
            Arrays.fill(plain, (byte) 0);
            cached = templates;
            return templates;
        } catch (Exception e) {
            // never log the data itself
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("PrivacyGuard: owner templates unreadable (" + e.getClass().getSimpleName() + ")");
            }
            return null;
        }
    }

    public static synchronized boolean save(float[][] templates) {
        FileOutputStream out = null;
        AtomicFile atomicFile = new AtomicFile(file());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream stream = new DataOutputStream(bytes);
            stream.writeInt(templates.length);
            stream.writeInt(templates[0].length);
            for (float[] t : templates) {
                for (float v : t) {
                    stream.writeFloat(v);
                }
            }
            stream.flush();
            byte[] plain = bytes.toByteArray();

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plain);
            Arrays.fill(plain, (byte) 0);

            ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + iv.length + encrypted.length);
            buffer.putInt(MAGIC);
            buffer.put((byte) iv.length);
            buffer.put(iv);
            buffer.put(encrypted);

            out = atomicFile.startWrite();
            out.write(buffer.array());
            atomicFile.finishWrite(out);
            cached = null;
            return true;
        } catch (Exception e) {
            if (out != null) {
                atomicFile.failWrite(out);
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("PrivacyGuard: could not save owner templates (" + e.getClass().getSimpleName() + ")");
            }
            return false;
        }
    }

    /** Deletes the templates and the key protecting them. */
    public static synchronized void delete() {
        cached = null;
        new AtomicFile(file()).delete();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("PrivacyGuard: could not delete key (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    public static synchronized void clearCache() {
        cached = null;
    }

    private static SecretKey getKey(boolean create) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        if (!create) {
            throw new IllegalStateException("no key");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
