package Controllers;

import Models.AppSettings;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.EmailService;
import Services.HaciendaCertificateService;
import Services.HaciendaCertificateService.CertificateInfo;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;



import Utils.DiffUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletContext;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import javax.swing.filechooser.FileSystemView;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;



import jakarta.servlet.http.Part;

/**
 * ORPHAN NOTE (T26 settings VIEW half): scheduled for deletion with the
 * legacy JSF settings surface, but still referenced by live code, so it
 * STAYS until these referers migrate:
 *
 * <ul>
 *   <li>Controllers.Tiquetes.CrearTiqueteController - imports and injects
 *       this bean (field {@code settings});</li>
 *   <li>secured/fragments/userBar.xhtml:112-113 - EL {@code #{SettingsController.logo}};</li>
 *   <li>secured/pages/index.xhtml - the surviving wizard copy reads/writes
 *       this bean via ~48 EL expressions;</li>
 *   <li>META-INF/resources/index.xhtml (root landing) - EL on
 *       {@code currentSettings} and {@code logo}.</li>
 * </ul>
 *
 * <p>The NEW app surface consumes Controllers.Api.App.SettingsResource
 * (/api/app/settings) plus templates/pages/settings/index.html instead of
 * this bean; operational updates no longer route through here.</p>
 *
 * @author Al
 */
@Getter @Setter @ToString(exclude = {"tipoCambioController", "currentSession"}) @EqualsAndHashCode(exclude = {"tipoCambioController", "currentSession"})
@Named("SettingsController")
@ApplicationScoped
public class SettingsController implements Serializable {

    @Nullable
    private List<AppSettings> currentSettingsList;
    @Nullable
    private AppSettings currentSettings;
    @Nullable
    private AppSettings newSettings;
    @Nullable
    private AppSettings selectedSettings;
    @Nullable
    private Boolean hasValidProfile;
    @Nullable
    private Boolean pasoSeleccionNombre, pasoSeleccionLogo, pasoSeleccionEmail, pasoSeleccionTributacion, pasoSeleccionConfirmacion, configuracionActual;
    @Nullable
    private Part imagen;

    @Inject
    @Nonnull
    AppSettingsService settingsService;
    @Inject
    @Nonnull
    private ServletContext servletContext;
    @Inject
    @Nonnull
    private EmailService emailer;
    @Inject
    @Nonnull
    private TipoCambioController tipoCambioController;
    @Inject
    @Nonnull
    private AlertasService alertas;
    @Inject
    HttpServletRequest httpRequest;
    @Inject
    HttpServletResponse httpResponse;
    @Inject
    @Nonnull
    private SessionController currentSession;
    @Inject
    @Nonnull
    private HaciendaCertificateService haciendaCertificateService;
    
    // Hacienda certificate fields
    @Nullable
    private Part certificadoFile;
    @Nullable
    private String certificadoPassword;
    @Nullable
    private String haciendaApiKey;
    @Nullable
    private String haciendaEnvironment;
    @Nullable
    private CertificateInfo certificateInfo;
    private boolean hasCertificate;
    private boolean hasValidCertificate;

    @PostConstruct
    private void init() {
        // Server-side security check for settings access - admin only
        if (!currentSession.isAdmin()) {
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
            return;
        }
        
        currentSettingsList = settingsService.listAll();
        currentSettings = settingsService.returnCurrent();
        if (currentSettings == null) {
            currentSettings = new AppSettings(); 
        }  
        loadHaciendaStatus();
        seleccionar();
    }

    public void seleccionar() {
        resetSeleccion();
        switch (currentSettings.getCompletedSteps()) {
            case 0:
                pasoSeleccionNombre = true;
                break;
            case 1:
                pasoSeleccionLogo = true;
                break;
            case 2:
                pasoSeleccionEmail = true;
                break;
            case 3:
                pasoSeleccionTributacion = true;
                break;
            case 4:
                pasoSeleccionConfirmacion = true;
                break;
            case 5:
                configuracionActual = true;
                break;
            default:
                alertas.registrarAlerta("Info", "No hay pasos?!", currentSession.getCurrentUser(), 0, "SettingsController.seleccionar()", null, null);
                break;
        }
    }

    public void seleccionar(@Nonnull String caso) {
        resetSeleccion();
        switch (caso) {
            case "nombre":
                pasoSeleccionNombre = true;
                break;
            case "logo":
                pasoSeleccionLogo = true;
                break;
            case "correo":
                pasoSeleccionEmail = true;
                break;
            case "tributacion":
                pasoSeleccionTributacion = true;
                break;
            case "confirmacion":
                pasoSeleccionConfirmacion = true;
                break;
            case "actual":
                configuracionActual = true;
                break;
            default:
                alertas.registrarAlerta("Info", "No hay pasos?!", currentSession.getCurrentUser(), 0, "SettingsController.seleccionar()", null, null);
                break;
        }
    }

    public void resetSeleccion() {
        pasoSeleccionNombre = false;
        pasoSeleccionLogo = false;
        pasoSeleccionEmail = false;
        pasoSeleccionTributacion = false;
        pasoSeleccionConfirmacion = false;
        configuracionActual = false;
    }

    public void saveUsername() {
        if (currentSettings != null) {
            var nombre = currentSettings.getNombrePerfil();
            if (nombre != null && !nombre.isBlank()) {
                if (currentSettings.getCompletedSteps() == 0) {
                    dirInit();
                    createDirectories();
                    currentSettings.setEstatus(true);
                    currentSettings.setCompletedSteps(1);
                    settingsService.create(currentSettings);
                } else {
                    settingsService.update(currentSettings);
                    alertas.registrarAlerta("Nombre de Usuario Actualizado", "Se actualizó el nombre de usuario a: " + currentSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "saveUsername()", null, currentSettings.getNombrePerfil());
                }
                reloadPage();

                alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
            } else {
                alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
            }
        } else {
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
        }

    }

    public void dirInit() {
        createHomeDir();
    }

    public void createDirectories() {
        createProfileDir();
        createXMLDir();
        createPDFDir();
        createFacturasDir();
        createRecibosDir();
        createReportesDir();
        createBackupsDir();
    }

    public void createHomeDir() {
        createFolder(getMainDirectory(), "Mercurius");
    }

    public void createProfileDir() {
        createFolder(getMainDirectory() + File.separator + "Mercurius", currentSettings.getNombrePerfil());
    }

    public void createReportesDir() {
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "reportes");
    }

    public void createXMLDir() {
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "xml");
    }

    public void createPDFDir() {
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "pdf");
    }

    public void createFacturasDir() {
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "facturas");
    }

    public void createRecibosDir() {
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "recibos");
    }

    public void createBackupsDir() {
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "backups");
    }

    public void createNewSettings() {
        newSettings = new AppSettings();
    }

    public void saveInitSettings() {
        if (currentSettings != null) {
            settingsService.create(currentSettings);
        }
    }

    public void updateSelectedSettings() {
        String antes = DiffUtils.snapshotEntity(selectedSettings);
        settingsService.update(selectedSettings);
        alertas.registrarAlerta("Configuración Actualizada", "Se actualizó la configuración seleccionada", currentSession.getCurrentUser(), 0, "updateSelectedSettings()", antes, DiffUtils.snapshotEntity(selectedSettings));
    }

    public void disableSelectedSettings() {
        String antes = DiffUtils.snapshotEntity(selectedSettings);
        settingsService.disable(selectedSettings);
        alertas.registrarAlerta("Configuración Deshabilitada", "Se deshabilitó la configuración seleccionada", currentSession.getCurrentUser(), 0, "disableSelectedSettings()", antes, DiffUtils.snapshotEntity(selectedSettings));
    }

    @Nonnull
    public String getMainDirectory() {
        FileSystemView fsv = FileSystemView.getFileSystemView();
        File docDir = fsv.getDefaultDirectory();
        return docDir.getAbsolutePath();
    }

    public void createFolder(@Nonnull String documentsPath, @Nonnull String folderName) {
        File newFolder = new File(documentsPath, folderName);
        if (newFolder.exists()) {
        } else if (newFolder.mkdir()) {

        }
    }

    @Nonnull
    public String getHomeDirPath() {
        return getMainDirectory() + File.separator + "Mercurius";
    }

    @Nonnull
    public String getProfileDirPath() {
        return getHomeDirPath() + File.separator + currentSettings.getNombrePerfil();
    }

    @Nonnull
    public String getReportesDirPath() {
        return getProfileDirPath() + File.separator + "reportes";
    }

    @Nonnull
    public String getXMLDirPath() {
        return getProfileDirPath() + File.separator + "xml";
    }

    @Nonnull
    public String getPDFDirPath() {
        return getProfileDirPath() + File.separator + "pdf";
    }

    @Nonnull
    public String getImgDirPath() {
        return File.separator + "resources" + File.separator + "img";
    }

    @Nonnull
    public String getLogoDirPath() {
        return getImgDirPath() + File.separator + "logo";
    }

    @Nonnull
    public String getFacturasDirPath() {
        return getProfileDirPath() + File.separator + "facturas";
    }

    @Nonnull
    public String getRecibosDirPath() {
        return getProfileDirPath() + File.separator + "recibos";
    }

    @Nonnull
    public String getBackupsDirPath() {
        return getProfileDirPath() + File.separator + "backups";
    }

    public void uploadLogo(@Nonnull Part event) {
        imagen = event;
        if (imagen != null) {
            try (InputStream input = imagen.getInputStream()) {
                byte[] logoBytes = input.readAllBytes();

                currentSettings.setLogo(logoBytes);
                currentSettings.setLogoMimeType(imagen.getContentType());
                if (currentSettings.getCompletedSteps() == 1) {
                    currentSettings.setCompletedSteps(2);
                }
                settingsService.update(currentSettings);
                reloadPage();

            } catch (IOException ex) {
                alertas.registrarAlerta("Error", "Error: " + ex.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "SettingsController.handleFileUpload()", null, ex.getLocalizedMessage());
            }
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
        }
    }

    private static final Pattern EMAIL_REGEX = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE
    );

    public void saveCorreo() {
        if (currentSettings == null) {
            addMessage(null, "Configuración faltante", "Las configuraciones actuales no pueden estar vacías");
            return;
        }

        String correoElectronico = currentSettings.getCorreoElectronico();
        String contrasenaCorreo = currentSettings.getContrasenaCorreo();

        if (correoElectronico.isBlank()) {
            addMessage(null, "Correo vacío", "El correo electrónico no puede estar vacío");
            return;
        }

        if (!EMAIL_REGEX.matcher(correoElectronico).matches()) {
            addMessage(null, "Correo inválido", "El correo electrónico no tiene un formato válido");
            return;
        }

        if (contrasenaCorreo.isBlank()) {
            addMessage(null, "Contraseña vacía", "La contraseña no puede estar vacía");
            return;
        }

        try {
            if (currentSettings.getCompletedSteps() == 2) {
                currentSettings.setCompletedSteps(3);
            }
            settingsService.update(currentSettings);
            alertas.registrarAlerta("Correo Actualizado", "Se actualizó el correo electrónico a: " + currentSettings.getCorreoElectronico(), currentSession.getCurrentUser(), 0, "saveCorreo()", correoElectronico, currentSettings.getCorreoElectronico());

            asyncProbarCorreo();

            reloadPage();
            addMessage(null, "Éxito", "Se añadió el correo electrónico");
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error:" + e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "SettingsController.saveCorreo()", null, e.getLocalizedMessage());
            addMessage(null, "Error", "No se pudo enviar el correo: " + e.getMessage());
        }
    }

    public void asyncProbarCorreo() {
        String correoElectronico = currentSettings.getCorreoElectronico();
        String contrasenaCorreo = currentSettings.getContrasenaCorreo();
        CompletableFuture.runAsync(() -> {
            probarCorreo(correoElectronico, contrasenaCorreo);
        }).exceptionally(ex -> {
            alertas.registrarAlerta("Error", "Error al probar correo: " + ex.getMessage(), currentSession.getCurrentUser(), 0, "SettingsController.asyncProbarCorreo()", null, ex.getMessage());
            addMessage(null, 
                   "Prueba de correo fallida", 
                   "El correo fue guardado, pero no se pudo enviar el mensaje de prueba."); 
            return null;
        });
    }

    public void probarCorreo(@Nonnull String correoElectronico, @Nonnull String contrasenaCorreo) {
        String to = correoElectronico;
        String subject = "¡Bienvenido!";
        String body = "¡Se registró con éxito su correo en el sistema Mercurius!";
        emailer.sendEmail(to, subject, body, correoElectronico, contrasenaCorreo, this::handleEmailResult);
    }

    public void handleEmailResult(@Nonnull String emailResult) {
        // Handle the result of the email sending operation
        if (emailResult.equals("Sent")) {
            // Email sent successfully
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
        } else {
            // Failed to send email
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
        }
    }

    public void saveTributacion() {
        if (currentSettings.getCompletedSteps() == 3) {
            currentSettings.setCompletedSteps(4);
        }
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
        alertas.registrarAlerta("Tributación Actualizada", "Se actualizó la tributación", currentSession.getCurrentUser(), 0, "saveTributacion()", oldSettings.toString(), currentSettings.toString());
        reloadPage();
    }

    public void saveCambio() {
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
        alertas.registrarAlerta("Tipo de Cambio Actualizado", "Se actualizó el tipo de cambio", currentSession.getCurrentUser(), 0, "saveCambio()", oldSettings.toString(), currentSettings.toString());
        tipoCambioController.recargar();
    }

    private void addMessage(Object severity, String summary, String detail) { alertas.registrarAlerta(summary, detail, currentSession != null && currentSession.getCurrentUser() != null ? (Models.Users) currentSession.getCurrentUser() : null, 0, "SettingsController", null, null);
    }

    public void saveProfile() {
        if (currentSettings.getCompletedSteps() == 4) {
            currentSettings.setCompletedSteps(5);
        }
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
        alertas.registrarAlerta("Perfil Actualizado", "Se actualizó el perfil", currentSession.getCurrentUser(), 0, "saveProfile()", oldSettings.toString(), currentSettings.toString());
        reloadPage();
    }

    public void reloadPage() {
        // stub facesContext
        // stub externalContext
        String contextPath = httpRequest.getContextPath();
        String currentView = "";
        String url = contextPath + currentView;

        try {
            httpResponse.sendRedirect(url);
        } catch (IOException e) {
            alertas.registrarAlerta("Error", "Error al redirigir: " + e.getMessage(), currentSession.getCurrentUser(), 0, "SettingsController.reloadPage()", null, e.getMessage());
        }
    }

    @Nullable
    public byte[] getLogo() {
        if (currentSettings != null) {
            byte[] logoBytes = currentSettings.getLogo();
            if (logoBytes != null) {
                return null;
            }
        }
        return null;
    }
    
    // ============ HACIENDA CERTIFICATE METHODS ============
    
    public void loadHaciendaStatus() {
        hasCertificate = haciendaCertificateService.hasCertificate();
        hasValidCertificate = haciendaCertificateService.hasValidCertificate();
        if (hasCertificate) {
            certificateInfo = haciendaCertificateService.getCertificateInfo();
        }
        if (currentSettings != null) {
            haciendaApiKey = haciendaCertificateService.getDecryptedApiKey();
            haciendaEnvironment = currentSettings.getHaciendaEnvironment();
        }
    }
    
    public void uploadCertificado(@Nonnull Part event) {
        certificadoFile = event;
        if (certificadoFile != null && certificadoFile.getSize() > 0) {
            try {
                byte[] certBytes = certificadoFile.getInputStream().readAllBytes();
                
                // Validate before saving
                if (certificadoPassword == null || certificadoPassword.isBlank()) {
                    alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
                    return;
                }
                
                boolean isValid = haciendaCertificateService.validateCertificate(certBytes, certificadoPassword);
                if (!isValid) {
                    alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
                    return;
                }
                
                // Save the certificate
                haciendaCertificateService.saveCertificate(certBytes, certificadoPassword);
                
                // Refresh status
                loadHaciendaStatus();
                
                alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
                
                alertas.registrarAlerta("Certificado Hacienda", 
                    "Se subió el certificado digital", 
                    currentSession.getCurrentUser(), 0, "uploadCertificado()", null, null);
                    
            } catch (Exception e) {
                alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
            }
        }
    }
    
    public void saveApiKey() {
        if (currentSettings != null && haciendaApiKey != null && !haciendaApiKey.isBlank()) {
            haciendaCertificateService.saveApiKey(haciendaApiKey);
            loadHaciendaStatus();
            
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
            
            alertas.registrarAlerta("API Key Hacienda", 
                "Se guardó la API Key de Hacienda", 
                currentSession.getCurrentUser(), 0, "saveApiKey()", null, null);
        } else {
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
        }
    }
    
    public void saveEnvironment() {
        if (currentSettings != null && haciendaEnvironment != null) {
            currentSettings.setHaciendaEnvironment(haciendaEnvironment);
            settingsService.update(currentSettings);
            
            String envLabel = "production".equals(haciendaEnvironment) ? "Producción" : "Pruebas";
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
            
            alertas.registrarAlerta("Entorno Hacienda", 
                "Se configuró entorno: " + envLabel, 
                currentSession.getCurrentUser(), 0, "saveEnvironment()", null, haciendaEnvironment);
        }
    }
    
    public void clearCertificate() {
        haciendaCertificateService.clearCertificate();
        loadHaciendaStatus();
        
        alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
        
        alertas.registrarAlerta("Certificado Hacienda", 
            "Se eliminó el certificado digital", 
            currentSession.getCurrentUser(), 0, "clearCertificate()", null, null);
    }
    
    @Nonnull
    public String getCertificateStatus() {
        if (!hasCertificate) {
            return "No configurado";
        }
        if (!hasValidCertificate) {
            return "Vencido";
        }
        return "Válido";
    }
    
    @Nonnull
    public String getEnvironmentLabel() {
        return "production".equals(haciendaEnvironment) ? "Producción" : "Pruebas (Sandbox)";
    }
    
    public boolean isSandbox() {
        return "sandbox".equalsIgnoreCase(haciendaEnvironment) || 
               (haciendaEnvironment == null || haciendaEnvironment.isBlank());
    }
    
    public boolean isProduction() {
        return "production".equalsIgnoreCase(haciendaEnvironment);
    }
    
    public void setSandbox() {
        haciendaEnvironment = "sandbox";
        saveEnvironment();
    }
    
    public void setProduction() {
        haciendaEnvironment = "production";
        saveEnvironment();
    }
    
    public void initializeEncryption() {
        boolean created = haciendaCertificateService.initializeEncryptionKey();
        if (created) {
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
            alertas.registrarAlerta("Cifrado Hacienda",
                "Se inicializó la llave de cifrado",
                currentSession.getCurrentUser(), 0, "initializeEncryption()", null, null);
        } else {
            alertas.registrarAlerta("Info", "stub", null, 0, "SettingsController", null, null);
        }
    }

    public boolean isEncryptionKeyInitialized() {
        if (currentSettings == null) return false;
        String key = currentSettings.getHaciendaEncryptionKey();
        return key != null && !key.isEmpty();
    }

    // ============ END HACIENDA METHODS ============
    
}
