package Controllers.Settings;

import Controllers.SessionController;
import Models.AppSettings;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.BackupService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;

@Getter @Setter @ToString @EqualsAndHashCode
@Named("backupController")
@ViewScoped
public class BackupController implements Serializable {

    private static final long serialVersionUID = 1L;

    @Nullable
    private List<BackupFileInfo> backupList;
    @Nullable
    private AppSettings currentSettings;
    @Nullable
    private String lastBackup;
    private boolean backupEnabled;
    @Nullable
    private String selectedBackupFilename;
    @Nullable
    private StreamedContent downloadFile;

    @Inject
    @Nonnull
    private BackupService backupService;

    @Inject
    @Nonnull
    private AppSettingsService appSettingsService;

    @Inject
    @Nonnull
    private AlertasService alertasService;

    @Inject
    @Nonnull
    private SessionController currentSession;

    @PostConstruct
    private void init() {
        if (!currentSession.isAdmin()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Acceso Denegado", "Se requieren permisos de administrador"));
            return;
        }
        loadSettings();
        refreshBackupList();
    }

    private void loadSettings() {
        currentSettings = appSettingsService.findOrCreateCurrent();

        String computedPath = getBackupDirPath();
        if (currentSettings.getBackupRuta() == null || currentSettings.getBackupRuta().isBlank()) {
            currentSettings.setBackupRuta(computedPath);
        }

        backupEnabled = Boolean.TRUE.equals(currentSettings.getBackupHabilitado());
        if (currentSettings.getBackupUltimoEjecutado() != null) {
            lastBackup = currentSettings.getBackupUltimoEjecutado()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        } else {
            lastBackup = "Nunca";
        }
    }

    @Nonnull
    public String getBackupDirPath() {
        String mainDir;
        try {
            javax.swing.filechooser.FileSystemView fsv = javax.swing.filechooser.FileSystemView.getFileSystemView();
            mainDir = fsv.getDefaultDirectory().getAbsolutePath();
        } catch (Exception e) {
            mainDir = System.getProperty("user.home") + File.separator + "Documents";
        }

        String profileName = (currentSettings != null && currentSettings.getNombrePerfil() != null)
            ? currentSettings.getNombrePerfil() : "default";

        return mainDir + File.separator + "Mercurius" + File.separator + profileName + File.separator + "backups";
    }

    public void executeBackupNow() {
        if (!currentSession.isAdmin()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Acceso Denegado", "Se requieren permisos de administrador"));
            return;
        }

        boolean success = backupService.ejecutarBackup();
        if (success) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Backup completado correctamente"));
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El backup falló. Revise las alertas del sistema."));
        }
        loadSettings();
        refreshBackupList();
    }

    public void saveSettings() {
        if (!currentSession.isAdmin()) return;

        if (currentSettings != null) {
            currentSettings.setBackupHabilitado(backupEnabled);
            currentSettings.setEstatus(true);
            backupService.saveSettings(currentSettings);

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Configuración de backup guardada"));

            alertasService.registrarAlerta("Backup Config",
                "Configuración de backup actualizada. Habilitado: " + backupEnabled,
                currentSession.getCurrentUser(), 0, "BackupController.saveSettings()", null, null);
        }
        loadSettings();
    }

    public void refreshBackupList() {
        backupList = new ArrayList<>();
        List<String> rawList = backupService.listarBackups();
        if (rawList != null) {
            for (String entry : rawList) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) {
                    backupList.add(new BackupFileInfo(parts[0], parts[1]));
                }
            }
        }
    }

    public void downloadBackup() {
        if (selectedBackupFilename == null || selectedBackupFilename.isBlank()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se seleccionó ningún archivo"));
            return;
        }

        Path filePath = backupService.getBackupFilePath(selectedBackupFilename);
        if (filePath == null || !Files.exists(filePath)) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Archivo no encontrado: " + selectedBackupFilename));
            return;
        }

        try {
            downloadFile = DefaultStreamedContent.builder()
                .stream(() -> {
                    try {
                        return new FileInputStream(filePath.toFile());
                    } catch (IOException e) {
                        throw new RuntimeException("Error al leer archivo de backup: " + e.getMessage(), e);
                    }
                })
                .name(selectedBackupFilename)
                .contentType("application/gzip")
                .contentLength(filePath.toFile().length())
                .build();
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error preparando descarga: " + e.getMessage(), null, 0,
                "BackupController.downloadBackup()", null, e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al preparar la descarga"));
        }
    }

    @Getter @Setter @ToString @EqualsAndHashCode
    public static class BackupFileInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String filename;
        private final String size;

        public BackupFileInfo(String filename, String size) {
            this.filename = filename;
            this.size = size;
        }
    }
}
