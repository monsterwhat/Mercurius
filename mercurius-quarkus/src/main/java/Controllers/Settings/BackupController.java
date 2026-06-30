package Controllers.Settings;

import Controllers.SessionController;
import Models.AppSettings;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.BackupService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;

@Data
@Named("backupController")
@ViewScoped
public class BackupController implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<BackupFileInfo> backupList;
    private AppSettings currentSettings;
    private String lastBackup;
    private boolean backupEnabled;
    private String selectedBackupFilename;
    private StreamedContent downloadFile;

    @Inject
    private BackupService backupService;

    @Inject
    private AppSettingsService appSettingsService;

    @Inject
    private AlertasService alertasService;

    @Inject
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
        currentSettings = backupService.getSettings();
        if (currentSettings == null) {
            currentSettings = new AppSettings();
        }
        backupEnabled = Boolean.TRUE.equals(currentSettings.getBackupHabilitado());
        if (currentSettings.getBackupUltimoEjecutado() != null) {
            lastBackup = currentSettings.getBackupUltimoEjecutado()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        } else {
            lastBackup = "Nunca";
        }
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error preparando descarga: " + e.getMessage(), null, 0,
                "BackupController.downloadBackup()", null, e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Error al preparar la descarga"));
        }
    }

    @Data
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
