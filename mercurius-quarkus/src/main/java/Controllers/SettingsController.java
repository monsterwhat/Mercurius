package Controllers;

import Models.AppSettings;
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

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(SettingsController.class.getName());

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

    @Nonnull
    AppSettingsService settingsService;
    @Nonnull
    private ServletContext servletContext;
    @Nonnull
    private EmailService emailer;
    @Nonnull
    private TipoCambioController tipoCambioController;
        @Inject
    HttpServletRequest httpRequest;
    @Inject
    HttpServletResponse httpResponse;
    @Nonnull
    private SessionController currentSession;
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
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
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
                                LOG.info("No hay pasos?!" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "SettingsController.seleccionar()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
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
                                LOG.info("No hay pasos?!" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "SettingsController.seleccionar()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
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
                                        LOG.info("Se actualizó el nombre de usuario a: " + currentSettings.getNombrePerfil() + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "saveUsername()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(currentSettings.getNombrePerfil()));
                }
                reloadPage();

                                LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            } else {
                                LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            }
        } else {
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
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
                LOG.info("Se actualizó la configuración seleccionada" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "updateSelectedSettings()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(selectedSettings)));
    }

    public void disableSelectedSettings() {
        String antes = DiffUtils.snapshotEntity(selectedSettings);
        settingsService.disable(selectedSettings);
                LOG.info("Se deshabilitó la configuración seleccionada" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "disableSelectedSettings()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(selectedSettings)));
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
                                LOG.log(java.util.logging.Level.WARNING, "Error: " + ex.getLocalizedMessage() + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "SettingsController.handleFileUpload()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(ex.getLocalizedMessage()));
            }
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
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
                        LOG.info("Se actualizó el correo electrónico a: " + currentSettings.getCorreoElectronico() + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "saveCorreo()" + " | antes=" + String.valueOf(correoElectronico) + " | despues=" + String.valueOf(currentSettings.getCorreoElectronico()));

            asyncProbarCorreo();

            reloadPage();
            addMessage(null, "Éxito", "Se añadió el correo electrónico");
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error:" + e.getLocalizedMessage() + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "SettingsController.saveCorreo()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getLocalizedMessage()));
            addMessage(null, "Error", "No se pudo enviar el correo: " + e.getMessage());
        }
    }

    public void asyncProbarCorreo() {
        String correoElectronico = currentSettings.getCorreoElectronico();
        String contrasenaCorreo = currentSettings.getContrasenaCorreo();
        CompletableFuture.runAsync(() -> {
            probarCorreo(correoElectronico, contrasenaCorreo);
        }).exceptionally(ex -> {
                        LOG.log(java.util.logging.Level.WARNING, "Error al probar correo: " + ex.getMessage() + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "SettingsController.asyncProbarCorreo()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(ex.getMessage()));
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
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        } else {
            // Failed to send email
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        }
    }

    public void saveTributacion() {
        if (currentSettings.getCompletedSteps() == 3) {
            currentSettings.setCompletedSteps(4);
        }
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
                LOG.info("Se actualizó la tributación" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "saveTributacion()" + " | antes=" + String.valueOf(oldSettings.toString()) + " | despues=" + String.valueOf(currentSettings.toString()));
        reloadPage();
    }

    public void saveCambio() {
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
                LOG.info("Se actualizó el tipo de cambio" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "saveCambio()" + " | antes=" + String.valueOf(oldSettings.toString()) + " | despues=" + String.valueOf(currentSettings.toString()));
        tipoCambioController.recargar();
    }

    private void addMessage(Object severity, String summary, String detail) {              LOG.log("Error".equalsIgnoreCase(String.valueOf(summary)) ? java.util.logging.Level.WARNING : java.util.logging.Level.INFO, detail + " | user=" + String.valueOf(currentSession != null && currentSession.getCurrentUser() != null ? (Models.Users) currentSession.getCurrentUser() : null) + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
    }

    public void saveProfile() {
        if (currentSettings.getCompletedSteps() == 4) {
            currentSettings.setCompletedSteps(5);
        }
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
                LOG.info("Se actualizó el perfil" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "saveProfile()" + " | antes=" + String.valueOf(oldSettings.toString()) + " | despues=" + String.valueOf(currentSettings.toString()));
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
                        LOG.log(java.util.logging.Level.WARNING, "Error al redirigir: " + e.getMessage() + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "SettingsController.reloadPage()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
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
                                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                    return;
                }
                
                boolean isValid = haciendaCertificateService.validateCertificate(certBytes, certificadoPassword);
                if (!isValid) {
                                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                    return;
                }
                
                // Save the certificate
                haciendaCertificateService.saveCertificate(certBytes, certificadoPassword);
                
                // Refresh status
                loadHaciendaStatus();
                
                                LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                
                                LOG.info("Se subió el certificado digital" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "uploadCertificado()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                    
            } catch (Exception e) {
                                LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            }
        }
    }
    
    public void saveApiKey() {
        if (currentSettings != null && haciendaApiKey != null && !haciendaApiKey.isBlank()) {
            haciendaCertificateService.saveApiKey(haciendaApiKey);
            loadHaciendaStatus();
            
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            
                        LOG.info("Se guardó la API Key de Hacienda" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "saveApiKey()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        } else {
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        }
    }
    
    public void saveEnvironment() {
        if (currentSettings != null && haciendaEnvironment != null) {
            currentSettings.setHaciendaEnvironment(haciendaEnvironment);
            settingsService.update(currentSettings);
            
            String envLabel = "production".equals(haciendaEnvironment) ? "Producción" : "Pruebas";
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            
                        LOG.info("Se configuró entorno: " + envLabel + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "saveEnvironment()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(haciendaEnvironment));
        }
    }
    
    public void clearCertificate() {
        haciendaCertificateService.clearCertificate();
        loadHaciendaStatus();
        
                LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        
                LOG.info("Se eliminó el certificado digital" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "clearCertificate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
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
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                        LOG.info("Se inicializó la llave de cifrado" + " | user=" + String.valueOf(currentSession.getCurrentUser()) + " | source=" + "initializeEncryption()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        } else {
                        LOG.info("stub" + " | source=" + "SettingsController" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        }
    }

    public boolean isEncryptionKeyInitialized() {
        if (currentSettings == null) return false;
        String key = currentSettings.getHaciendaEncryptionKey();
        return key != null && !key.isEmpty();
    }

    // ============ END HACIENDA METHODS ============
    
}
