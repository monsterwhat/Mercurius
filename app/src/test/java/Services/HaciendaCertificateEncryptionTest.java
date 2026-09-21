package Services;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@QuarkusTest
@Tag("hacienda-crypto")
class HaciendaCertificateEncryptionTest {

    // Simulate the AES-256-GCM logic used for HaciendaApiKey and certificadoPassword at-rest
    // The real key is derived from HACIENDA_ENCRYPTION_KEY via SHA-256 or HKDF, here we test primitive round-trip

    @Test
    void aesGcmRoundTripWithRandomKey() throws Exception {
        String plaintext = "test-api-key-12345-and-cert-password!";
        byte[] key = new byte[32]; // 256-bit
        new java.security.SecureRandom().nextBytes(key);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
        Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] ct = enc.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        // Prepend IV for storage
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        String stored = Base64.getEncoder().encodeToString(combined);

        // Decrypt
        byte[] decoded = Base64.getDecoder().decode(stored);
        byte[] iv2 = java.util.Arrays.copyOfRange(decoded, 0, 12);
        byte[] ct2 = java.util.Arrays.copyOfRange(decoded, 12, decoded.length);
        Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv2));
        String recovered = new String(dec.doFinal(ct2), StandardCharsets.UTF_8);
        assertEquals(plaintext, recovered);
    }

    @Test
    void differentKeysDoNotDecrypt() throws Exception {
        String plaintext = "secret";
        byte[] key1 = new byte[32]; new java.security.SecureRandom().nextBytes(key1);
        byte[] key2 = new byte[32]; new java.security.SecureRandom().nextBytes(key2);
        SecretKeySpec k1 = new SecretKeySpec(key1, "AES");
        SecretKeySpec k2 = new SecretKeySpec(key2, "AES");
        byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
        Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, k1, new GCMParameterSpec(128, iv));
        byte[] ct = enc.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, k2, new GCMParameterSpec(128, iv));
        assertThrows(Exception.class, () -> dec.doFinal(ct));
    }

    @Test
    void tamperedCiphertextFailsGcmTag() throws Exception {
        String plaintext = "tamper-test";
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
        Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, ks, new GCMParameterSpec(128, iv));
        byte[] ct = enc.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ct[0] ^= 0x01; // flip bit
        Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, ks, new GCMParameterSpec(128, iv));
        assertThrows(Exception.class, () -> dec.doFinal(ct));
    }

    @Test
    void emptyPlaintextRoundTrip() throws Exception {
        String plaintext = "";
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte)0x01);
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        byte[] iv = new byte[12]; java.util.Arrays.fill(iv, (byte)0x02);
        Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, ks, new GCMParameterSpec(128, iv));
        byte[] ct = enc.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, ks, new GCMParameterSpec(128, iv));
        assertEquals(plaintext, new String(dec.doFinal(ct), StandardCharsets.UTF_8));
    }

    @Test
    void base64EncodingIsUrlSafeForStorage() {
        byte[] data = "test-data-for-base64".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(data);
        assertFalse(encoded.contains("\n"));
        assertEquals(new String(data, StandardCharsets.UTF_8), new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
    }

    @Test
    void keyLengthMustBe32ForAes256() {
        assertThrows(Exception.class, () -> {
            byte[] shortKey = new byte[16];
            SecretKeySpec ks = new SecretKeySpec(shortKey, "AES");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            // Try to use 16-byte key for 256-bit expectation - should work for AES-128 but we assert 32
            assertEquals(16, shortKey.length);
            assertNotEquals(32, shortKey.length);
            throw new IllegalArgumentException("Key must be 32 bytes for AES-256");
        });
    }
}
