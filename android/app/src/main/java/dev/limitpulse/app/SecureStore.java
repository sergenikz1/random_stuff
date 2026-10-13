package dev.limitpulse.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private AES-GCM ciphertext; the encryption key never leaves Android Keystore. */
final class SecureStore {
    private static final String ALIAS = "limitpulse.account.v1";
    private final SharedPreferences preferences;
    SecureStore(Context context) { preferences = context.getSharedPreferences("secure_account", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    synchronized Credentials load() throws Exception {
        String text = preferences.getString("ciphertext", "");
        if (text.isEmpty()) return null;
        JSONObject json = new JSONObject(text);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)));
        return Credentials.fromJson(new JSONObject(new String(cipher.doFinal(Base64.decode(json.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8)));
    }
    synchronized void save(Credentials credentials) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(credentials.toJson().toString().getBytes(StandardCharsets.UTF_8));
        JSONObject json = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        if (!preferences.edit().putString("ciphertext", json.toString()).commit()) throw new java.io.IOException("Cannot persist credentials");
    }
    // Logout must confirm durable deletion before reporting success.
    @android.annotation.SuppressLint("ApplySharedPref")
    synchronized boolean clear() { return preferences.edit().clear().commit(); }
}
