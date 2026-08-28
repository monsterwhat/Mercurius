package Services;

import Models.AppSettings;
import Utils.EncryptionUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Enumeration;
import java.util.logging.Logger;

@Named
@ApplicationScoped
public class HaciendaCertificateService extends GService<AppSettings> {

    private static final Logger LOG = Logger.getLogger(HaciendaCertificateService.class.getName());

    private SecretKey encryptionKey;

    @Override
    protected Class<AppSettings> getEntityClass() {
        return AppSettings.class;
    }

    @PostConstruct
    void initEncryption() {
        try {
            AppSettings settings = getActiveSettings();
            if (settings != null) {
                String dbKey = settings.getHaciendaEncryptionKey();
                if (dbKey != null && !dbKey.isEmpty()) {
                    encryptionKey = EncryptionUtil.getKeyFromString(dbKey);
                    migrateExistingSecrets();
                } else {
                    LOG.warning("No encryption key configured — secrets stored in plaintext. " +
                        "Use Settings UI to initialize the encryption key.");
                }
            } else {
                LOG.warning("No active AppSettings — encryption key not available, secrets stored in plaintext");
            }
        } catch (RuntimeException e) {
            LOG.warning("Failed to initialize encryption key: " + e.getMessage());
        }
    }

    /**
     * Explicitly generates and persists a new encryption key.
     * Call this from the Settings UI when the user clicks "Initialize Encryption Key".
     * Only generates if no key exists yet — does NOT rotate existing keys.
     * @return true if a new key was generated, false if one already existed
     */
    public boolean initializeEncryptionKey() {
        AppSettings settings = getActiveSettings();
        if (settings == null) {
            LOG.warning("Cannot initialize encryption key — no active settings");
            return false;
        }
        String existing = settings.getHaciendaEncryptionKey();
        if (existing != null && !existing.isEmpty()) {
            LOG.info("Encryption key already exists — skipping initialization");
            return false;
        }
        try {
            String newKey = EncryptionUtil.generateKey();
            settings.setHaciendaEncryptionKey(newKey);
            em.merge(settings);
            encryptionKey = EncryptionUtil.getKeyFromString(newKey);
            LOG.info("Encryption key initialized via UI");
                        LOG.info("Encryption key initialized successfully" + " | source=" + "HaciendaCertificateService.initializeEncryptionKey()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            migrateExistingSecrets();
            return true;
        } catch (RuntimeException e) {
            LOG.warning("Failed to initialize encryption key: " + e.getMessage());
                        LOG.log(java.util.logging.Level.WARNING, "Failed to initialize encryption key: " + e.getMessage() + " | source=" + "HaciendaCertificateService.initializeEncryptionKey()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }

    public static class CertificateInfo {
        public String subject;
        public String issuer;
        public Date notBefore;
        public Date notAfter;
        public boolean isExpired;
        public boolean isNotYetValid;
        public String serialNumber;
        
        public CertificateInfo() {}
        
        public CertificateInfo(String subject, String issuer, Date notBefore, Date notAfter, String serialNumber) {
            this.subject = subject;
            this.issuer = issuer;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
            this.serialNumber = serialNumber;
            Date now = new Date();
            this.isExpired = now.after(notAfter);
            this.isNotYetValid = now.before(notBefore);
        }
    }

    private String decryptValue(String encrypted) {
        if (encryptionKey == null) return encrypted; // plaintext fallback
        if (encrypted == null || encrypted.isEmpty()) return encrypted;
        try {
            return EncryptionUtil.decrypt(encrypted, encryptionKey);
        } catch (RuntimeException e) {
            // Not encrypted or wrong key — return as-is for backward compat
            return encrypted;
        }
    }

    private String encryptValue(String plaintext) {
        if (encryptionKey == null) return plaintext; // no key = plaintext
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            return EncryptionUtil.encrypt(plaintext, encryptionKey);
        } catch (RuntimeException e) {
            LOG.warning("Failed to encrypt value: " + e.getMessage());
            return plaintext;
        }
    }

    private void migrateExistingSecrets() {
        if (encryptionKey == null) return;
        try {
            AppSettings settings = getActiveSettings();
            if (settings == null) return;
            boolean changed = false;

            if (settings.getHaciendaApiKey() != null && !settings.getHaciendaApiKey().isEmpty()
                    && !EncryptionUtil.isEncrypted(settings.getHaciendaApiKey())) {
                settings.setHaciendaApiKey(encryptValue(settings.getHaciendaApiKey()));
                changed = true;
                LOG.info("Migrated existing plaintext API key to encrypted storage");
            }

            if (settings.getCertificadoPassword() != null && !settings.getCertificadoPassword().isEmpty()
                    && !EncryptionUtil.isEncrypted(settings.getCertificadoPassword())) {
                settings.setCertificadoPassword(encryptValue(settings.getCertificadoPassword()));
                changed = true;
                LOG.info("Migrated existing plaintext certificate password to encrypted storage");
            }

            if (changed) {
                em.merge(settings);
                                LOG.info("Existing Hacienda credentials encrypted at rest" + " | source=" + "HaciendaCertificateService.migrateExistingSecrets()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            }
        } catch (RuntimeException e) {
            LOG.warning("Secret migration failed (non-blocking): " + e.getMessage());
        }
    }

    public AppSettings getActiveSettings() {
        try {
            var list = em.createQuery("SELECT a FROM AppSettings a WHERE a.estatus = true", AppSettings.class)
                    .getResultList();
            return list != null && !list.isEmpty() ? list.get(0) : null;
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error getting active settings: " + e.getLocalizedMessage() + " | source=" + "HaciendaCertificateService.getActiveSettings()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public String getDecryptedApiKey() {
        AppSettings settings = getActiveSettings();
        if (settings == null || settings.getHaciendaApiKey() == null) return null;
        return decryptValue(settings.getHaciendaApiKey());
    }

    public String getDecryptedCertificadoPassword() {
        AppSettings settings = getActiveSettings();
        if (settings == null || settings.getCertificadoPassword() == null) return null;
        return decryptValue(settings.getCertificadoPassword());
    }

    public boolean hasCertificate() {
        AppSettings settings = getActiveSettings();
        return settings != null && settings.getCertificado() != null && settings.getCertificado().length > 0;
    }

    public boolean hasValidCertificate() {
        try {
            CertificateInfo info = getCertificateInfo();
            return info != null && !info.isExpired && !info.isNotYetValid;
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error checking certificate validity: " + e.getLocalizedMessage() + " | source=" + "HaciendaCertificateService.hasValidCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }

    public CertificateInfo getCertificateInfo() {
        AppSettings settings = getActiveSettings();
        if (settings == null || settings.getCertificado() == null) {
            return null;
        }

        try (InputStream is = new ByteArrayInputStream(settings.getCertificado())) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            String password = getDecryptedCertificadoPassword();
            if (password == null || password.isEmpty()) {
                password = "";
            }
            keyStore.load(is, password.toCharArray());

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) {
                    Certificate cert = keyStore.getCertificate(alias);
                    if (cert instanceof X509Certificate) {
                        X509Certificate x509 = (X509Certificate) cert;
                        return new CertificateInfo(
                            x509.getSubjectX500Principal().getName(),
                            x509.getIssuerX500Principal().getName(),
                            x509.getNotBefore(),
                            x509.getNotAfter(),
                            x509.getSerialNumber().toString()
                        );
                    }
                }
            }
            return null;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error reading certificate info: " + e.getLocalizedMessage() + " | source=" + "HaciendaCertificateService.getCertificateInfo()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public boolean validateCertificate(byte[] certificado, String password) {
        if (certificado == null || certificado.length == 0) {
            return false;
        }
        // password may be plaintext (from UI) or already decrypted — try both
        String resolvedPassword = password;
        if (encryptionKey != null && password != null && EncryptionUtil.isEncrypted(password)) {
            try {
                resolvedPassword = EncryptionUtil.decrypt(password, encryptionKey);
            } catch (RuntimeException e) {
                // not encrypted, use as-is
            }
        }

        try (InputStream is = new ByteArrayInputStream(certificado)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(is, resolvedPassword.toCharArray());

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) {
                    Certificate cert = keyStore.getCertificate(alias);
                    if (cert instanceof X509Certificate) {
                        X509Certificate x509 = (X509Certificate) cert;
                        Date now = new Date();
                        
                        if (now.after(x509.getNotAfter())) {
                                                        LOG.info("Certificate is expired" + " | source=" + "HaciendaCertificateService.validateCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                            return false;
                        }
                        if (now.before(x509.getNotBefore())) {
                                                        LOG.info("Certificate is not yet valid" + " | source=" + "HaciendaCertificateService.validateCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                            return false;
                        }
                        
                        return true;
                    }
                }
            }
                        LOG.info("No valid certificate found in keystore" + " | source=" + "HaciendaCertificateService.validateCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            return false;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Certificate validation error: " + e.getLocalizedMessage() + " | source=" + "HaciendaCertificateService.validateCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }

    public KeyStore loadKeyStore() throws Exception {
        AppSettings settings = getActiveSettings();
        if (settings == null || settings.getCertificado() == null) {
            throw new IllegalStateException("No certificate configured");
        }

        try (InputStream is = new ByteArrayInputStream(settings.getCertificado())) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            String password = getDecryptedCertificadoPassword();
            if (password == null || password.isEmpty()) {
                password = "";
            }
            keyStore.load(is, password.toCharArray());
            return keyStore;
        }
    }

    public void saveCertificate(byte[] certificado, String password) {
        AppSettings settings = getActiveSettings();
        if (settings != null) {
            settings.setCertificado(certificado);
            settings.setCertificadoPassword(encryptValue(password));
            em.merge(settings);
                        LOG.info("Certificate saved successfully" + " | source=" + "HaciendaCertificateService.saveCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        } else {
                        LOG.info("No active settings found to save certificate" + " | source=" + "HaciendaCertificateService.saveCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        }
    }

    public void clearCertificate() {
        AppSettings settings = getActiveSettings();
        if (settings != null) {
            settings.setCertificado(null);
            settings.setCertificadoPassword(null);
            em.merge(settings);
                        LOG.info("Certificate cleared successfully" + " | source=" + "HaciendaCertificateService.clearCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        }
    }

    public boolean hasApiKey() {
        AppSettings settings = getActiveSettings();
        return settings != null && settings.getHaciendaApiKey() != null && !settings.getHaciendaApiKey().isEmpty();
    }

    public void saveApiKey(String apiKey) {
        AppSettings settings = getActiveSettings();
        if (settings != null) {
            settings.setHaciendaApiKey(encryptValue(apiKey));
            em.merge(settings);
                        LOG.info("API Key saved successfully" + " | source=" + "HaciendaCertificateService.saveApiKey()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        }
    }

    public String getEnvironment() {
        AppSettings settings = getActiveSettings();
        return settings != null && settings.getHaciendaEnvironment() != null 
            ? settings.getHaciendaEnvironment() 
            : "sandbox";
    }

    public void setEnvironment(String environment) {
        AppSettings settings = getActiveSettings();
        if (settings != null) {
            settings.setHaciendaEnvironment(environment);
            em.merge(settings);
                        LOG.info("Environment set to: " + environment + " | source=" + "HaciendaCertificateService.setEnvironment()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        }
    }

    public boolean isTokenExpired() {
        AppSettings settings = getActiveSettings();
        if (settings == null || settings.getHaciendaTokenExpiry() == null) {
            return true;
        }
        // Refresh 30s before actual expiry to prevent 401 errors during API calls
        return LocalDateTime.now().plusSeconds(30).isAfter(settings.getHaciendaTokenExpiry());
    }

    public void saveTokenExpiry(LocalDateTime expiry) {
        AppSettings settings = getActiveSettings();
        if (settings != null) {
            settings.setHaciendaTokenExpiry(expiry);
            em.merge(settings);
        }
    }
}
