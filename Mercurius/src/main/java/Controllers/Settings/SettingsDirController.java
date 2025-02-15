package Controllers.Settings;

import Controllers.SessionController;
import Models.AppSettings;
import Services.AppSettingsService;
import Services.EmailService;
import Services.AlertasService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.Serializable;
import java.util.List;
import javax.swing.filechooser.FileSystemView;
import lombok.Data;
import org.primefaces.model.file.UploadedFile;

/**
 *
 * @author Al
 */

@Data
@Named("SettingsDirController")
@RequestScoped
public class SettingsDirController implements Serializable {
    
    private List<AppSettings> currentSettingsList;
    private AppSettings currentSettings;
    private AppSettings newSettings;
    private AppSettings selectedSettings;
    private Boolean hasValidProfile;
    private UploadedFile imagen;
    
    @Inject AppSettingsService settingsService;
    @Inject private ServletContext servletContext;
    @Inject private EmailService emailer;
    @Inject private AlertasService alertasService;
    @Inject private SessionController currentSession;
    
    @PostConstruct
    private void init(){
        currentSettingsList = settingsService.listAll();
        currentSettings = settingsService.returnCurrent();
        if(currentSettings == null){
            currentSettings = new AppSettings();
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
        
        // Save an alert (log) for updating the selected settings
        alertasService.registrarAlerta("Configuración actualizada", "Se ha actualizado la configuración: " + selectedSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "SettingsDirController.updateSelectedSettings", oldSettings.toString(), selectedSettings.toString());
    }
    
    public void disableSelectedSettings(){
        var oldSettings = selectedSettings;
        settingsService.disable(selectedSettings);
        
        // Save an alert (log) for disabling the selected settings
        alertasService.registrarAlerta("Configuración deshabilitada", "Se ha deshabilitado la configuración: " + selectedSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "SettingsDirController.disableSelectedSettings", oldSettings.toString(), selectedSettings.toString());
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
    
}
