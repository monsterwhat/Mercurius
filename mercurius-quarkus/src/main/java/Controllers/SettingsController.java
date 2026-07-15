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
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import Utils.DiffUtils;
import jakarta.faces.view.ViewScoped;
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
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;

/**
 *
 * @author Al
 */
@Getter @Setter @ToString(exclude = {"tipoCambioController", "currentSession"}) @EqualsAndHashCode(exclude = {"tipoCambioController", "currentSession"})
@Named("SettingsController")
@ViewScoped
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
    private UploadedFile imagen;

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
    @Nonnull
    private SessionController currentSession;
    @Inject
    @Nonnull
    private HaciendaCertificateService haciendaCertificateService;
    
    // Hacienda certificate fields
    @Nullable
    private UploadedFile certificadoFile;
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
            FacesContext.getCurrentInstance().addMessage(null, 
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Access Denied", "Admin access required"));
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

                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito!", "Se registro el nombre de perfil."));
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "Sin nombre!", "Digite un nombre antes de continuar"));
            }
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_FATAL, "Configuracion invalida!", "La configuracion es nula."));
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

    public void uploadLogo(@Nonnull FileUploadEvent event) {
        imagen = event.getFile();
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
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito!", imagen.getFileName() + " se selecciono."));
        }
    }

    private static final Pattern EMAIL_REGEX = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE
    );

    public void saveCorreo() {
        if (currentSettings == null) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Configuración faltante", "Las configuraciones actuales no pueden estar vacías");
            return;
        }

        String correoElectronico = currentSettings.getCorreoElectronico();
        String contrasenaCorreo = currentSettings.getContrasenaCorreo();

        if (correoElectronico.isBlank()) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Correo vacío", "El correo electrónico no puede estar vacío");
            return;
        }

        if (!EMAIL_REGEX.matcher(correoElectronico).matches()) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Correo inválido", "El correo electrónico no tiene un formato válido");
            return;
        }

        if (contrasenaCorreo.isBlank()) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Contraseña vacía", "La contraseña no puede estar vacía");
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
            addMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Se añadió el correo electrónico");
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Error:" + e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "SettingsController.saveCorreo()", null, e.getLocalizedMessage());
            addMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo enviar el correo: " + e.getMessage());
        }
    }

    public void asyncProbarCorreo() {
        String correoElectronico = currentSettings.getCorreoElectronico();
        String contrasenaCorreo = currentSettings.getContrasenaCorreo();
        CompletableFuture.runAsync(() -> {
            probarCorreo(correoElectronico, contrasenaCorreo);
        }).exceptionally(ex -> {
            alertas.registrarAlerta("Error", "Error al probar correo: " + ex.getMessage(), currentSession.getCurrentUser(), 0, "SettingsController.asyncProbarCorreo()", null, ex.getMessage());
            addMessage(FacesMessage.SEVERITY_WARN, 
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
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Email sent successfully!", null));
        } else {
            // Failed to send email
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Failed to send email: " + emailResult, null));
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

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
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
        FacesContext facesContext = FacesContext.getCurrentInstance();
        ExternalContext externalContext = facesContext.getExternalContext();
        String contextPath = externalContext.getRequestContextPath();
        String currentView = facesContext.getViewRoot().getViewId();
        String url = contextPath + currentView;

        try {
            externalContext.redirect(url);
        } catch (IOException e) {
            alertas.registrarAlerta("Error", "Error al redirigir: " + e.getMessage(), currentSession.getCurrentUser(), 0, "SettingsController.reloadPage()", null, e.getMessage());
        }
    }

    @Nullable
    public StreamedContent getLogo() {
        if (currentSettings != null) {
            byte[] logoBytes = currentSettings.getLogo();
            if (logoBytes != null) {
                return DefaultStreamedContent.builder()
                        .stream(() -> new ByteArrayInputStream(logoBytes))
                        .contentType(currentSettings.getLogoMimeType())
                        .build();
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
    
    public void uploadCertificado(@Nonnull FileUploadEvent event) {
        certificadoFile = event.getFile();
        if (certificadoFile != null && certificadoFile.getContent() != null) {
            try {
                byte[] certBytes = certificadoFile.getContent();
                
                // Validate before saving
                if (certificadoPassword == null || certificadoPassword.isBlank()) {
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Ingrese la contraseña del certificado"));
                    return;
                }
                
                boolean isValid = haciendaCertificateService.validateCertificate(certBytes, certificadoPassword);
                if (!isValid) {
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El certificado es inválido o está vencido"));
                    return;
                }
                
                // Save the certificate
                haciendaCertificateService.saveCertificate(certBytes, certificadoPassword);
                
                // Refresh status
                loadHaciendaStatus();
                
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Certificado guardado correctamente"));
                
                alertas.registrarAlerta("Certificado Hacienda", 
                    "Se subió el certificado digital", 
                    currentSession.getCurrentUser(), 0, "uploadCertificado()", null, null);
                    
            } catch (RuntimeException e) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al procesar certificado: " + e.getMessage()));
            }
        }
    }
    
    public void saveApiKey() {
        if (currentSettings != null && haciendaApiKey != null && !haciendaApiKey.isBlank()) {
            haciendaCertificateService.saveApiKey(haciendaApiKey);
            loadHaciendaStatus();
            
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", "API Key guardada correctamente"));
            
            alertas.registrarAlerta("API Key Hacienda", 
                "Se guardó la API Key de Hacienda", 
                currentSession.getCurrentUser(), 0, "saveApiKey()", null, null);
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Ingrese una API Key válida"));
        }
    }
    
    public void saveEnvironment() {
        if (currentSettings != null && haciendaEnvironment != null) {
            currentSettings.setHaciendaEnvironment(haciendaEnvironment);
            settingsService.update(currentSettings);
            
            String envLabel = "production".equals(haciendaEnvironment) ? "Producción" : "Pruebas";
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Entorno configurado: " + envLabel));
            
            alertas.registrarAlerta("Entorno Hacienda", 
                "Se configuró entorno: " + envLabel, 
                currentSession.getCurrentUser(), 0, "saveEnvironment()", null, haciendaEnvironment);
        }
    }
    
    public void clearCertificate() {
        haciendaCertificateService.clearCertificate();
        loadHaciendaStatus();
        
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Certificado eliminado"));
        
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
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito",
                    "Llave de cifrado inicializada. Las credenciales existentes se migrarán automáticamente."));
            alertas.registrarAlerta("Cifrado Hacienda",
                "Se inicializó la llave de cifrado",
                currentSession.getCurrentUser(), 0, "initializeEncryption()", null, null);
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Info",
                    "La llave de cifrado ya existe o no se pudo crear. Verifique que haya configuraciones activas."));
        }
    }

    public boolean isEncryptionKeyInitialized() {
        if (currentSettings == null) return false;
        String key = currentSettings.getHaciendaEncryptionKey();
        return key != null && !key.isEmpty();
    }

    // ============ END HACIENDA METHODS ============
    
}
