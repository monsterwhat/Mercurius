package Controllers;

import Models.AppSettings;
import Services.AppSettingsService;
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
import org.primefaces.event.FlowEvent;
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
    private String mainDirectory;
    private Boolean hasValidProfile;
    private int initSteps = 0;
    private boolean skip;
    
    @Inject AppSettingsService settingsService;
    @Inject private ServletContext servletContext;
    
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
    
    public void defaultConfig(){
        if(!hasValidProfile){
            if(currentSettings!=null){
                var nombre = currentSettings.getNombrePerfil();
                if(nombre != null){
                    if(!nombre.isBlank()){
                        dirInit();
                        currentSettings.setEstatus(true);
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
        createImgDir();
        createFacturasDir();
        createRecibosDir();
        createReportesDir();
        createLogoDir();
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
    
    public void createImgDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil(), "img");
    }
    
    public void createLogoDir(){
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + currentSettings.getNombrePerfil() + File.separator + "img", "logo");
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
        return getProfileDirPath() + File.separator + "img";
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
    
    public void handleLogoUpload(FileUploadEvent event) {
        UploadedFile file = event.getFile();
        if (file != null) {
            try (InputStream input = file.getInputStream();
                FileOutputStream output = new FileOutputStream(new File(getLogoDirPath(), file.getFileName()))) {
                
                IOUtils.copy(input, output);
                
                System.out.println(getLogoDirPath() + File.separator + file.getFileName());
                
                currentSettings.setLogo(getLogoDirPath() + File.separator + file.getFileName());
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Successful", file.getFileName() + " is uploaded."));
                
            } catch (IOException e) {
                System.out.println("Error Uploading Logo: " + e.getLocalizedMessage());
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Upload failed", e.getMessage()));
            }
        }
    }
    
    public String onFlowProcess(FlowEvent event) {
        if (skip) {
            skip = false; //reset in case user goes back
            return "confirm";
        }
        else {
            return event.getNewStep();
        }
    }
    
}
