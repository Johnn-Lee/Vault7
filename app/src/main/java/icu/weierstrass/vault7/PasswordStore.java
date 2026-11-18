package icu.weierstrass.vault7;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PasswordStore {
    private static final String ALIAS = "vault7-password";
    private static final String PAYLOAD = "password_envelope";
    private final SharedPreferences preferences;

    PasswordStore(Context context) {
        preferences = preferences(context);
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("vault7", Context.MODE_PRIVATE);
    }

    boolean isSet() {
        return preferences.contains(PAYLOAD);
    }

    void save(char[] password) throws GeneralSecurityException, IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] plaintext = new byte[encoded.remaining()];
        encoded.get(plaintext);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] envelope = ByteBuffer.allocate(1 + iv.length + ciphertext.length)
                    .put((byte) iv.length).put(iv).put(ciphertext).array();
            if (!preferences.edit().putString(PAYLOAD, Base64.encodeToString(envelope, Base64.NO_WRAP)).commit()) {
                throw new IOException("无法保存全局密码");
            }
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    char[] read() throws GeneralSecurityException, IOException {
        String value = preferences.getString(PAYLOAD, null);
        if (value == null) throw new IOException("请先设置全局密码");
        ByteBuffer envelope = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP));
        int ivSize = envelope.get() & 0xff;
        if (ivSize != 12 || envelope.remaining() < ivSize + 16) throw new IOException("请重新设置全局密码");
        byte[] iv = new byte[ivSize];
        envelope.get(iv);
        byte[] ciphertext = new byte[envelope.remaining()];
        envelope.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plaintext));
        char[] password = new char[decoded.remaining()];
        try {
            decoded.get(password);
            return password;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        }
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(ALIAS, null) instanceof SecretKey key) return key;
        if (!create) throw new GeneralSecurityException("请重新设置全局密码");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
