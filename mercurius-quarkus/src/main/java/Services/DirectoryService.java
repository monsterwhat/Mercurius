package Services;

import Models.AppSettings;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import javax.swing.filechooser.FileSystemView;

@ApplicationScoped
public class DirectoryService {

    @Inject @Nonnull
    AppSettingsService appSettingsService;

    @Nonnull
    public String getMainDirectory() {
        FileSystemView fsv = FileSystemView.getFileSystemView();
        return fsv.getDefaultDirectory().getAbsolutePath();
    }

    @Nonnull
    public String getHomeDirPath() {
        return getMainDirectory() + File.separator + "Mercurius";
    }

    @Nonnull
    public String getProfileDirPath(@Nullable String profileName) {
        String name = (profileName != null) ? profileName : "default";
        return getHomeDirPath() + File.separator + name;
    }

    @Nonnull
    public String getCurrentProfileName() {
        AppSettings settings = appSettingsService.returnCurrent();
        return (settings != null && settings.getNombrePerfil() != null)
                ? settings.getNombrePerfil() : "default";
    }

    @Nonnull
    public String getFacturasDirPath() {
        return getFacturasDirPath(getCurrentProfileName());
    }

    @Nonnull
    public String getFacturasDirPath(@Nonnull String profileName) {
        return getProfileDirPath(profileName) + File.separator + "facturas";
    }

    @Nonnull
    public String getPDFDirPath() {
        return getPDFDirPath(getCurrentProfileName());
    }

    @Nonnull
    public String getPDFDirPath(@Nonnull String profileName) {
        return getProfileDirPath(profileName) + File.separator + "pdf";
    }

    public void createPDFDir() {
        createPDFDir(getCurrentProfileName());
    }

    public void createPDFDir(@Nonnull String profileName) {
        createDirectory(getPDFDirPath(profileName));
    }

    public void createFacturasDir() {
        createFacturasDir(getCurrentProfileName());
    }

    public void createFacturasDir(@Nonnull String profileName) {
        createDirectory(getFacturasDirPath(profileName));
    }

    private void createDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
}
