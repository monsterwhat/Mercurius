package Controllers.Settings;

import Controllers.SessionController;
import Models.AppSettings;
import Services.AppSettingsService;
import Services.EmailService;
import Services.AlertasService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletContext;
import java.io.File;
import Utils.DiffUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.filechooser.FileSystemView;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.model.file.UploadedFile;

/**
 * ORPHAN NOTE (T26 settings VIEW half): legacy JSF helper kept ALIVE because
 * many still-active controllers/services inject it. Referers at deletion
 * review time:
 *
 * <ul>
 *   <li>Controllers.StockAlertController</li>
 *   <li>Controllers.ProfitAnalysisController</li>
 *   <li>Controllers.Correos.CorreosHelper</li>
 *   <li>Controllers.ArticulosController</li>
 *   <li>Services.EmailService</li>
 * </ul>
 *
 * <p>Its {@code updateSelectedSettings()} semantics live on verbatim in
 * Controllers.Api.App.SettingsResource PUT /api/app/settings (DiffUtils
 * snapshot -> update -> audit alert); new code must call the resource, not
 * this bean.</p>
 *
 * @author Al
 */
@Getter @Setter @ToString @EqualsAndHashCode
@Named("SettingsDirController")
@RequestScoped
public class SettingsDirController implements Serializable {

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
    private AlertasService alertasService;
    @Inject
    @Nonnull
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
        String antes = DiffUtils.snapshotEntity(selectedSettings);
        settingsService.update(selectedSettings);

        // Save an alert (log) for updating the selected settings
        alertasService.registrarAlerta("Configuración actualizada", "Se ha actualizado la configuración: " + selectedSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "SettingsDirController.updateSelectedSettings", antes, DiffUtils.snapshotEntity(selectedSettings));
    }

    public void disableSelectedSettings() {
        String antes = DiffUtils.snapshotEntity(selectedSettings);
        settingsService.disable(selectedSettings);

        // Save an alert (log) for disabling the selected settings
        alertasService.registrarAlerta("Configuración deshabilitada", "Se ha deshabilitado la configuración: " + selectedSettings.getNombrePerfil(), currentSession.getCurrentUser(), 0, "SettingsDirController.disableSelectedSettings", antes, DiffUtils.snapshotEntity(selectedSettings));
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
            // Directory already exists
        } else if (newFolder.mkdirs()) {
            // Directory created successfully
        } else {
            // Failed to create directory
            alertasService.registrarAlerta("Error", "Failed to create directory: " + newFolder.getAbsolutePath(), currentSession.getCurrentUser(), 0, "SettingsDirController.createFolder()", null, null);
        }
    }

    @Nonnull
    public String getHomeDirPath() {
        return getMainDirectory() + File.separator + "Mercurius";
    }

    @Nonnull
    public String getProfileDirPath() {
        if (currentSettings == null || currentSettings.getNombrePerfil() == null) {
            return getHomeDirPath() + File.separator + "default";
        }
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
    public String getProductosImgDirPath() {
        return getProfileDirPath() + File.separator + "img" + File.separator + "productos";
    }

    public void createProductosImgDir() {
        createFolder(getProfileDirPath() + File.separator + "img", "productos");
    }

    @Nonnull
    public String getFacturasDirPath() {
        return getProfileDirPath() + File.separator + "facturas";
    }

    @Nonnull
    public String getRecibosDirPath() {
        return getProfileDirPath() + File.separator + "recibos";
    }

    public void saveUploadedFile(@Nonnull UploadedFile uploadedFile, @Nonnull String directoryPath) {
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
