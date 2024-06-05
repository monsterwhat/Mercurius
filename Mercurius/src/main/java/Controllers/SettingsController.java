package Controllers;

import Models.AppSettings;
import Services.AppSettingsService;
import Services.EmailService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.List;
import javax.swing.filechooser.FileSystemView;
import lombok.Data;
import org.apache.commons.compress.utils.IOUtils;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;

/**
 *
 * @author Al
 */

@Data
@Named("SettingsController")
@RequestScoped
public class SettingsController implements Serializable {
    
    private List<AppSettings> currentSettingsList;
    private AppSettings currentSettings;
    private AppSettings newSettings;
    private AppSettings selectedSettings;
    private Boolean hasValidProfile;
    
    @Inject AppSettingsService settingsService;
    @Inject private ServletContext servletContext;
    @Inject private EmailService emailer;
    
    @PostConstruct
    private void init(){
        currentSettingsList = settingsService.listAll();
        currentSettings = settingsService.returnCurrent();
        if(currentSettings == null){
            hasValidProfile = false;
            currentSettings = new AppSettings();
        }else{
            hasValidProfile = true;
        }
    }
    
    public void saveUsername(){
        if(!hasValidProfile){
            if(currentSettings!=null){
                var nombre = currentSettings.getNombrePerfil();
                if(nombre != null){
                    if(!nombre.isBlank()){
                        dirInit();
                        currentSettings.setEstatus(true);
                        currentSettings.setCompletedSteps(1);
                        settingsService.create(currentSettings);
                        createDirectories();
                    }
                }else{
                    FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sin Nombre", ""));
                }
            }
        }
    }
    
    public void dirInit(){
        createHomeDir();
    }
    
    public void createDirectories(){
        createProfileDir();   // Creates Profile directory inside Mercurius
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
        settingsService.update(selectedSettings);
    }
    
    public void disableSelectedSettings(){
        settingsService.disable(selectedSettings);
    }
    
    public String getMainDirectory(){
        FileSystemView fsv = FileSystemView.getFileSystemView();
        File docDir = fsv.getDefaultDirectory();
        return docDir.getAbsolutePath();
    }
    
    public void createFolder(String documentsPath, String folderName) {
        File newFolder = new File(documentsPath, folderName);
        String message;
        if (newFolder.exists()) {
            message = "Ya existe el folder!";
        } else if (newFolder.mkdir()) {
            message = "Se creo exitosamente el folder!";
        } else {
            message = "Error al crear el folder!";
        }
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, message, ""));
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
        return servletContext.getRealPath("/") + "resources/img";
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
    
    public void saveLogo(FileUploadEvent event) {
        UploadedFile file = event.getFile();
        if (file != null) {
            try (InputStream input = file.getInputStream()) {
                File logoDir = new File(getLogoDirPath());
                if (!logoDir.exists()) {
                    logoDir.mkdirs();
                }

                File outputFile = new File(logoDir, file.getFileName());
                try (FileOutputStream output = new FileOutputStream(outputFile)) {
                    IOUtils.copy(input, output);

                    currentSettings.setLogo("imgs/logo/" + file.getFileName());
                    currentSettings.setCompletedSteps(2);
                    settingsService.update(currentSettings);
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Successful", file.getFileName() + " is uploaded."));

                } catch (IOException e) {

                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Upload failed", e.getMessage()));
                }
            } catch (IOException e) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Upload failed", e.getMessage()));
            }
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
            currentSettings.setCompletedSteps(3);
            settingsService.update(currentSettings);

            emailer.sendEmail(correoElectronico, "¡Bienvenido!", "¡Se registró con éxito su correo en el sistema Mercurius!");

            addMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Se añadió el correo electrónico");
        } catch (Exception e) {
            System.out.println("Error:" + e.getLocalizedMessage());
            addMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudo enviar el correo: " + e.getMessage());
        }
    }
    
    public void saveTributacion(){
        currentSettings.setCompletedSteps(4);
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }
    
    public String getLogoImagePath(){
        if(currentSettings != null && currentSettings.getLogo() != null){
            return "/resources/" + currentSettings.getLogo();
        }
        return null;
    }
    
    public void saveProfile(){
        currentSettings.setCompletedSteps(5);
        settingsService.update(currentSettings);
        
    }
    
    public void reset(){
        currentSettings.setCompletedSteps(0);
        settingsService.update(currentSettings);
    }
    
    public void backOneStep(){
        currentSettings.setCompletedSteps(currentSettings.getCompletedSteps() - 1);
        settingsService.update(currentSettings);
    }
    
}
