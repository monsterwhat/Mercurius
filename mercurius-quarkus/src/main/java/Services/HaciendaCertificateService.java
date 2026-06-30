package Services;

import Models.AppSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
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

@Named
@ApplicationScoped
public class HaciendaCertificateService extends GService<AppSettings> {

    @Override
    protected Class<AppSettings> getEntityClass() {
        return AppSettings.class;
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

    public AppSettings getActiveSettings() {
        try {
            var list = em.createQuery("SELECT a FROM AppSettings a WHERE a.estatus = true", AppSettings.class)
                    .getResultList();
            return list != null && !list.isEmpty() ? list.get(0) : null;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error getting active settings: " + e.getLocalizedMessage(), null, 0, "HaciendaCertificateService.getActiveSettings()", null, e.getMessage());
            return null;
        }
    }

    public boolean hasCertificate() {
        AppSettings settings = getActiveSettings();
        return settings != null && settings.getCertificado() != null && settings.getCertificado().length > 0;
    }

    public boolean hasValidCertificate() {
        try {
            CertificateInfo info = getCertificateInfo();
            return info != null && !info.isExpired && !info.isNotYetValid;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error checking certificate validity: " + e.getLocalizedMessage(), null, 0, "HaciendaCertificateService.hasValidCertificate()", null, e.getMessage());
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
            String password = settings.getCertificadoPassword();
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
            alertasService.registrarAlerta("Error", "Error reading certificate info: " + e.getLocalizedMessage(), null, 0, "HaciendaCertificateService.getCertificateInfo()", null, e.getMessage());
            return null;
        }
    }

    public boolean validateCertificate(byte[] certificado, String password) {
        if (certificado == null || certificado.length == 0) {
            return false;
        }

        try (InputStream is = new ByteArrayInputStream(certificado)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(is, password.toCharArray());

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) {
                    Certificate cert = keyStore.getCertificate(alias);
                    if (cert instanceof X509Certificate) {
                        X509Certificate x509 = (X509Certificate) cert;
                        Date now = new Date();
                        
                        if (now.after(x509.getNotAfter())) {
                            alertasService.registrarAlerta("Info", "Certificate is expired", null, 0, "HaciendaCertificateService.validateCertificate()", null, null);
                            return false;
                        }
                        if (now.before(x509.getNotBefore())) {
                            alertasService.registrarAlerta("Info", "Certificate is not yet valid", null, 0, "HaciendaCertificateService.validateCertificate()", null, null);
                            return false;
                        }
                        
                        return true;
                    }
                }
            }
            alertasService.registrarAlerta("Info", "No valid certificate found in keystore", null, 0, "HaciendaCertificateService.validateCertificate()", null, null);
            return false;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            alertasService.registrarAlerta("Error", "Certificate validation error: " + e.getLocalizedMessage(), null, 0, "HaciendaCertificateService.validateCertificate()", null, e.getMessage());
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
            String password = settings.getCertificadoPassword();
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
            settings.setCertificadoPassword(password);
            em.merge(settings);
            alertasService.registrarAlerta("Info", "Certificate saved successfully", null, 0, "HaciendaCertificateService.saveCertificate()", null, null);
        } else {
            alertasService.registrarAlerta("Info", "No active settings found to save certificate", null, 0, "HaciendaCertificateService.saveCertificate()", null, null);
        }
    }

    public void clearCertificate() {
        AppSettings settings = getActiveSettings();
        if (settings != null) {
            settings.setCertificado(null);
            settings.setCertificadoPassword(null);
            em.merge(settings);
            alertasService.registrarAlerta("Info", "Certificate cleared successfully", null, 0, "HaciendaCertificateService.clearCertificate()", null, null);
        }
    }

    public boolean hasApiKey() {
        AppSettings settings = getActiveSettings();
        return settings != null && settings.getHaciendaApiKey() != null && !settings.getHaciendaApiKey().isEmpty();
    }

    public void saveApiKey(String apiKey) {
        AppSettings settings = getActiveSettings();
        if (settings != null) {
            settings.setHaciendaApiKey(apiKey);
            em.merge(settings);
            alertasService.registrarAlerta("Info", "API Key saved successfully", null, 0, "HaciendaCertificateService.saveApiKey()", null, null);
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
            alertasService.registrarAlerta("Info", "Environment set to: " + environment, null, 0, "HaciendaCertificateService.setEnvironment()", null, null);
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
