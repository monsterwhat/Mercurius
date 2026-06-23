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
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Inject
    AppSettingsService settingsService;
    @Inject
    private ServletContext servletContext;
    @Inject
    private EmailService emailer;
    @Inject
    private AlertasService alertasService;
    @Inject
    private SessionController currentSession;

    @PostConstruct
    private void init() {
        currentSettingsList = settingsService.listAll();
        currentSettings = settingsService.returnCurrent();
        if (currentSettings == null) {
            currentSettings = new AppSettings();
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
    }

    public void createHomeDir() {
        File homeDir = new File(getMainDirectory(), "Mercurius");
        if (!homeDir.exists()) {
            if (homeDir.mkdirs()) {
                alertasService.registrarAlerta("Info", "Created directory: " + homeDir.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createHomeDir()", null, null);
            } else {
                alertasService.registrarAlerta("Error", "Failed to create directory: " + homeDir.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createHomeDir()", null, null);
            }
        }
    }

    public void createProfileDir() {
        String profileName = currentSettings != null && currentSettings.getNombrePerfil() != null 
            ? currentSettings.getNombrePerfil() 
            : "default";
        File profileDir = new File(getMainDirectory() + File.separator + "Mercurius", profileName);
        if (!profileDir.exists()) {
            if (profileDir.mkdirs()) {
                alertasService.registrarAlerta("Info", "Created directory: " + profileDir.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createProfileDir()", null, null);
            } else {
                alertasService.registrarAlerta("Error", "Failed to create directory: " + profileDir.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createProfileDir()", null, null);
            }
        }
    }

    public void createReportesDir() {
        String profilePath = currentSettings != null && currentSettings.getNombrePerfil() != null 
            ? currentSettings.getNombrePerfil() 
            : "default";
        String basePath = getMainDirectory() + File.separator + "Mercurius" + File.separator + profilePath;
        File reportesDir = new File(basePath, "reportes");
        if (!reportesDir.exists()) {
            if (reportesDir.mkdirs()) {
                alertasService.registrarAlerta("Info", "Created directory: " + reportesDir.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createReportesDir()", null, null);
            } else {
                alertasService.registrarAlerta("Error", "Failed to create directory: " + reportesDir.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createReportesDir()", null, null);
            }
        }
    }

    public void createXMLDir() {
        String profilePath = currentSettings != null && currentSettings.getNombrePerfil() != null 
            ? currentSettings.getNombrePerfil() 
            : "default";
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + profilePath, "xml");
    }

    public void createPDFDir() {
        String profilePath = currentSettings != null && currentSettings.getNombrePerfil() != null 
            ? currentSettings.getNombrePerfil() 
            : "default";
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + profilePath, "pdf");
    }

    public void createFacturasDir() {
        String profilePath = currentSettings != null && currentSettings.getNombrePerfil() != null 
            ? currentSettings.getNombrePerfil() 
            : "default";
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + profilePath, "facturas");
    }

    public void createRecibosDir() {
        String profilePath = currentSettings != null && currentSettings.getNombrePerfil() != null 
            ? currentSettings.getNombrePerfil() 
            : "default";
        createFolder(getMainDirectory() + File.separator + "Mercurius" + File.separator + profilePath, "recibos");
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
        var oldSettings = selectedSettings;
        settingsService.update(selectedSettings);

        // Save an alert (log) for updating the selected settings
        alertasService.registrarAlerta("Configuración actualizada", "Se ha actualizado la configuración: " + selectedSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "SettingsDirController.updateSelectedSettings", oldSettings.toString(), selectedSettings.toString());
    }

    public void disableSelectedSettings() {
        var oldSettings = selectedSettings;
        settingsService.disable(selectedSettings);

        // Save an alert (log) for disabling the selected settings
        alertasService.registrarAlerta("Configuración deshabilitada", "Se ha deshabilitado la configuración: " + selectedSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "SettingsDirController.disableSelectedSettings", oldSettings.toString(), selectedSettings.toString());
    }

    public String getMainDirectory() {
        FileSystemView fsv = FileSystemView.getFileSystemView();
        File docDir = fsv.getDefaultDirectory();
        return docDir.getAbsolutePath();
    }

    public void createFolder(String documentsPath, String folderName) {
        File newFolder = new File(documentsPath, folderName);
        if (newFolder.exists()) {
            // Directory already exists
        } else if (newFolder.mkdirs()) {
            // Directory created successfully
        } else {
            // Failed to create directory
            alertasService.registrarAlerta("Error", "Failed to create directory: " + newFolder.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createFolder()", null, null);
        }
    }

    public String getHomeDirPath() {
        return getMainDirectory() + File.separator + "Mercurius";
    }

    public String getProfileDirPath() {
        if (currentSettings == null || currentSettings.getNombrePerfil() == null) {
            return getHomeDirPath() + File.separator + "default";
        }
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
        return File.separator + "resources" + File.separator + "img";
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

    public void saveUploadedFile(UploadedFile uploadedFile, String directoryPath) {
        try {
            // Make sure the directory exists
            Path directory = Path.of(directoryPath);
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            // Resolve target file path
            Path target = directory.resolve(uploadedFile.getFileName());

            // Copy file contents
            try (InputStream input = uploadedFile.getInputStream()) {
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), currentSession.getCurrentUser(), 0, "SettingsDirController.uploadLogo()", null, e.getLocalizedMessage());
        } 
    }

}
