package Controllers;

import Models.AppSettings;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.EmailService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
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
import javax.swing.filechooser.FileSystemView;
import lombok.Data;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;

/**
 *
 * @author Al
 */

@Data
@Named("SettingsController")
@ViewScoped
public class SettingsController implements Serializable {
    
    private List<AppSettings> currentSettingsList;
    private AppSettings currentSettings;
    private AppSettings newSettings;
    private AppSettings selectedSettings;
    private Boolean hasValidProfile;
    private UploadedFile imagen;
    
    @Inject AppSettingsService settingsService;
    @Inject private ServletContext servletContext;
    @Inject private EmailService emailer;
    @Inject private TipoCambioController tipoCambioController;
    @Inject private AlertasService alertas;
    @Inject private SessionController currentSession;
    
    @PostConstruct
    private void init(){
        currentSettingsList = settingsService.listAll();
        currentSettings = settingsService.returnCurrent();
        if(currentSettings == null){
            currentSettings = new AppSettings();
        }
    }
    
    public void saveUsername(){
        if(currentSettings != null){
            var nombre = currentSettings.getNombrePerfil();
            if(nombre != null){
                if(!nombre.isBlank()){
                    if(currentSettings.getCompletedSteps() == 0){
                        dirInit();
                        createDirectories();
                        currentSettings.setEstatus(true);
                        currentSettings.setCompletedSteps(1);
                        settingsService.create(currentSettings);
                    }else{
                        settingsService.update(currentSettings);
                        alertas.registrarAlerta("Nombre de Usuario Actualizado", "Se actualizó el nombre de usuario a: " + currentSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "saveUsername()", null, currentSettings.getNombrePerfil());
                    }
                    reloadPage();
                    
                        FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito!", "Se registro el nombre de perfil."));
                }
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Sin nombre!", "Digite un nombre antes de continuar"));
            }
        }else{
                FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_FATAL, "Configuracion invalida!", "La configuracion es nula."));
        }
        
    }
    
    public void dirInit(){
        createHomeDir();
    }
    
    public void createDirectories(){
        createProfileDir();
        createXMLDir();
        createPDFDir();
        createFacturasDir();
        createRecibosDir();
        createReportesDir();
    }
    
    public void createHomeDir(){
        createFolder(getMainDirectory(), "Mercurius");
    }
    
    public void createProfileDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius", currentSettings.getNombrePerfil());
    }
    
    public void createReportesDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "reportes");
    }
    
    public void createXMLDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "xml");
    }
    
    public void createPDFDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "pdf");
    }
    
    public void createFacturasDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "facturas");
    }
    
    public void createRecibosDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "recibos");
    }
    
    public void createNewSettings(){
        newSettings = new AppSettings();
    }
    
    public void saveInitSettings(){
        if(currentSettings != null){
            settingsService.create(currentSettings);
        }
    }
    
    public void updateSelectedSettings(){
        var oldSettings = selectedSettings;
        settingsService.update(selectedSettings);
        alertas.registrarAlerta("Configuración Actualizada", "Se actualizó la configuración seleccionada", currentSession.getCurrentUser(), 0, "updateSelectedSettings()", oldSettings.toString(), selectedSettings.toString());
    }
    
    public void disableSelectedSettings(){
        var oldSettings = selectedSettings;
        settingsService.disable(selectedSettings);
        alertas.registrarAlerta("Configuración Deshabilitada", "Se deshabilitó la configuración seleccionada", currentSession.getCurrentUser(), 0, "disableSelectedSettings()", oldSettings.toString(), selectedSettings.toString());
    }
    
    public String getMainDirectory(){
        FileSystemView fsv = FileSystemView.getFileSystemView();
        File docDir = fsv.getDefaultDirectory();
        return docDir.getAbsolutePath();
    }
    
    public void createFolder(String documentsPath, String folderName) {
        File newFolder = new File(documentsPath, folderName);
        if (newFolder.exists()) {
        } else if (newFolder.mkdir()){
            
        }
    }
    
    public String getHomeDirPath() {
        return getMainDirectory() + File.separator + "Mercurius";
    }

    public String getProfileDirPath() {
        return getHomeDirPath() + File.separator + currentSettings.getNombrePerfil();
    }
    
    public String getReportesDirPath() {
        return getProfileDirPath() + File.separator + "reportes";
    }
    
    public String getXMLDirPath() {
        return getProfileDirPath() + File.separator + "xml";
    }
    
    public String getPDFDirPath() {
        return getProfileDirPath() + File.separator + "pdf";
    }
    
    public String getImgDirPath() {
        return File.separator + "resources" + File.separator +  "img";
    }
    
    public String getLogoDirPath() {
        return getImgDirPath() + File.separator + "logo";
    }
    
    public String getFacturasDirPath() {
        return getProfileDirPath() + File.separator + "facturas";
    }
    
    public String getRecibosDirPath() {
        return getProfileDirPath() + File.separator + "recibos";
    }
    
    public void uploadLogo(FileUploadEvent event) {
        imagen = event.getFile();
        if (imagen != null) {
            try (InputStream input = imagen.getInputStream()){
                byte[] logoBytes = input.readAllBytes();
                
                currentSettings.setLogo(logoBytes);
                currentSettings.setLogoMimeType(imagen.getContentType());
                if(currentSettings.getCompletedSteps() == 1){
                    currentSettings.setCompletedSteps(2);
                }
                settingsService.update(currentSettings);
                reloadPage();   
                
            } catch (IOException ex) {
                System.out.println("Error: " + ex.getLocalizedMessage());
            }
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito!", imagen.getFileName() + " se selecciono."));
        }
    }    
    
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

        if (contrasenaCorreo.isBlank()) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Contraseña vacía", "La contraseña no puede estar vacía");
            return;
        }

        try {
            if(currentSettings.getCompletedSteps() == 2){
                    currentSettings.setCompletedSteps(3);
            }
            settingsService.update(currentSettings);
            alertas.registrarAlerta("Correo Actualizado", "Se actualizó el correo electrónico a: " + currentSettings.getCorreoElectronico(), currentSession.getCurrentUser(), 0, "saveCorreo()", correoElectronico, currentSettings.getCorreoElectronico());

            sendWelcomeMail();
            reloadPage();
            addMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Se añadió el correo electrónico");
        } catch (Exception e) {
            System.out.println("Error:" + e.getLocalizedMessage());
            addMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo enviar el correo: " + e.getMessage());
        }
    }
    
    public void sendWelcomeMail(){
        String correoElectronico = currentSettings.getCorreoElectronico();
        String contrasenaCorreo = currentSettings.getContrasenaCorreo();
        String to = currentSettings.getCorreoElectronico();
        String subject = "¡Bienvenido!";
        String body = "¡Se registró con éxito su correo en el sistema Mercurius!";
        emailer.sendEmail(to, subject, body, correoElectronico, contrasenaCorreo, this::handleEmailResult);
    }
    
    public void handleEmailResult(String emailResult) {
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
    
    public void saveTributacion(){
        if(currentSettings.getCompletedSteps() == 3){
            currentSettings.setCompletedSteps(4);
        }
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
        alertas.registrarAlerta("Tributación Actualizada", "Se actualizó la tributación", currentSession.getCurrentUser(), 0, "saveTributacion()", oldSettings.toString(), currentSettings.toString());
        reloadPage();
    }
    
    public void saveCambio(){
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
        alertas.registrarAlerta("Tipo de Cambio Actualizado", "Se actualizó el tipo de cambio", currentSession.getCurrentUser(), 0, "saveCambio()", oldSettings.toString(), currentSettings.toString());
        tipoCambioController.recargar();
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }
    
    public void saveProfile(){
        if(currentSettings.getCompletedSteps() == 4){
            currentSettings.setCompletedSteps(5);
        }
        var oldSettings = currentSettings;
        settingsService.update(currentSettings);
        alertas.registrarAlerta("Perfil Actualizado", "Se actualizó el perfil", currentSession.getCurrentUser(), 0, "saveProfile()", oldSettings.toString(), currentSettings.toString());
        reloadPage();
    }
    
    public void backOneStep(){
        currentSettings.setCompletedSteps(currentSettings.getCompletedSteps() - 1);
        settingsService.update(currentSettings);
    }
    
    public void nextOne(){
        currentSettings.setCompletedSteps(currentSettings.getCompletedSteps() + 1);
        settingsService.update(currentSettings);
    }
    
    public void navigate(int step){
        currentSettings.setCompletedSteps(step);
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
            e.printStackTrace();
        }
    }
    
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
    
}
