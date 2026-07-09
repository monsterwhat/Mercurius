package Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Base64;

/**
 * AES-256-GCM encryption utility for Hacienda credentials at rest.
 *
 * Key loaded from DB-stored Base64 string (auto-generated on first run).
 * Ciphertext format: Base64( 12-byte IV + AES-GCM output ).
 */
public class EncryptionUtil {

    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;      // 96-bit IV recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;      // 16-byte authentication tag
    private static final int KEY_SIZE_BITS = 256;

    private static final SecureRandom secureRandom = new SecureRandom();

    private EncryptionUtil() {
        // utility class
    }

    /**
     * Generates a random 256-bit AES key and returns it as Base64-encoded string.
     * Used for DB-stored key when no env var is configured.
     */
    @Nonnull
    public static String generateKey() {
        try {
            javax.crypto.KeyGenerator keyGen = javax.crypto.KeyGenerator.getInstance("AES");
            keyGen.init(KEY_SIZE_BITS, secureRandom);
            javax.crypto.SecretKey key = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }

    /**
     * Derives an AES-256 key from a Base64-encoded key string.
     * Accepts both the output of generateKey() (raw Base64 key bytes)
     * and arbitrary-length strings (derived via SHA-256 for backward compat).
     */
    @Nonnull
    public static SecretKeySpec getKeyFromString(@Nonnull String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isEmpty()) {
            throw new IllegalArgumentException("Key material is null or empty");
        }
        // Try to decode as Base64 raw key first
        try {
            byte[] decoded = Base64.getDecoder().decode(keyMaterial);
            if (decoded.length == 32) { // 256 bits = 32 bytes
                return new SecretKeySpec(decoded, "AES");
            }
        } catch (IllegalArgumentException e) {
            // Not Base64 — fall through to SHA-256 derivation
        }
        // Fallback: SHA-256 derivation (backward compat with env var strings)
        return deriveKey(keyMaterial);
    }

    /**
     * Derives a 256-bit AES key from an arbitrary-length string using SHA-256.
     */
    static SecretKeySpec deriveKey(String keyMaterial) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(keyMaterial.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Failed to derive AES key", e);
        }
    }

    /**
     * Encrypts plaintext with AES-256-GCM.
     *
     * @param plaintext raw text to encrypt
     * @param key       AES secret key
     * @return Base64-encoded string containing (12-byte IV + ciphertext + GCM tag)
     */
    @Nonnull
    public static String encrypt(@Nonnull String plaintext, @Nonnull javax.crypto.SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            AlgorithmParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded ciphertext produced by {@link #encrypt}.
     *
     * @param ciphertextB64 Base64-encoded (IV + ciphertext + GCM tag)
     * @param key           AES secret key
     * @return decrypted plaintext
     * @throws javax.crypto.AEADBadTagException if ciphertext has been tampered with
     */
    @Nonnull
    public static String decrypt(@Nonnull String ciphertextB64, @Nonnull javax.crypto.SecretKey key) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertextB64);

            if (combined.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Ciphertext too short — missing IV");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            AlgorithmParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new RuntimeException("Decryption failed — ciphertext was tampered with or key is wrong", e);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Checks whether a string looks like encrypted data (Base64-encoded IV + ciphertext).
     * Used to distinguish encrypted from legacy plaintext values during migration.
     */
    public static boolean isEncrypted(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // Encrypted output is always Base64, minimum length = 12 bytes IV + 16 bytes tag
        // Base64(28 bytes) ≈ 39 chars. But small plaintexts produce small outputs.
        // Heuristic: if it decodes and is long enough to contain IV + tag, treat as encrypted.
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length >= GCM_IV_LENGTH + 1; // IV + at least 1 byte ciphertext
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
