package Utils;

import jakarta.annotation.Nonnull;
import Services.AlertasService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application launcher with system tray integration and singleton enforcement
 */
@ApplicationScoped
public class AppLauncher {

    private static final String LOCK_FILE_PATH = System.getProperty("java.io.tmpdir") + "/mercurius.lock";
    private static final String APP_URL = "http://localhost:8081/Mercurius/index.xhtml";
    
    @Nonnull private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    @Inject @Nonnull
    SystemTrayManager trayManager;

    @Inject @Nonnull
    AlertasService alertasService;

    void onStart(@Observes StartupEvent event) {
        try {
            // Check if application is already running
            if (!isSingleInstance()) {
                alertasService.registrarAlerta("Error", "Mercurius is already running!", null, 0, "AppLauncher.onStart()", null, null);
                return; // Don't exit immediately, let Quarkus handle it
            }
            
            isRunning.set(true);
            
            // Initialize system tray (only on Windows) - do this in background with proper error handling
            Thread trayThread = new Thread(() -> {
                try {
                    trayManager.initializeTray();
                } catch (RuntimeException e) {
                    // Silently ignore tray initialization failures on non-Windows systems
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        alertasService.registrarAlerta("Error", "System tray initialization failed: " + e.getMessage(), null, 0, "AppLauncher.onStart()", null, null);
                    } else {
                        alertasService.registrarAlerta("Debug", "System tray not initialized (non-Windows system)", null, 0, "AppLauncher.onStart()", null, null);
                    }
                }
            });
            trayThread.setName("TrayInitializer");
            trayThread.setDaemon(true);
            trayThread.start();

            // Open browser after a short delay to ensure server is ready
            openBrowserAfterDelay(3000);

        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error during application startup: " + e.getMessage(), null, 0, "AppLauncher.onStart()", null, null);
            // Don't crash the app, just continue without these features
        }
    }

    /**
     * Checks if this is the only instance of the application running
     */
    private boolean isSingleInstance() {
        try {
            File lockFile = new File(LOCK_FILE_PATH);
            FileChannel channel = FileChannel.open(lockFile.toPath(), 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            
            FileLock lock = channel.tryLock();
            if (lock == null) {
                // Another instance is running
                try {
                    channel.close();
                } catch (IOException e) {
                    // Ignore close error
                }
                return false;
            }
            
            // Add shutdown hook to release the lock
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (lock != null && lock.isValid()) {
                        lock.release();
                    }
                    if (channel != null && channel.isOpen()) {
                        channel.close();
                    }
                    if (lockFile.exists()) {
                        lockFile.delete();
                    }
                } catch (IOException | RuntimeException e) {
                    // Ignore cleanup errors
                }
            }));

            return true;
        } catch (IOException | RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error checking single instance: " + e.getMessage(), null, 0, "AppLauncher.isSingleInstance()", null, null);
            return true; // Allow startup if we can't check
        }
    }

    /**
     * Opens the default browser to the application URL after a delay
     */
    private void openBrowserAfterDelay(int delayMs) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                openBrowser(APP_URL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Opens the default browser to the specified URL
     */
    void openBrowser(String url) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (IOException | URISyntaxException e) {
                alertasService.registrarAlerta("Error", "Error opening browser: " + e.getMessage(), null, 0, "AppLauncher.openBrowser()", null, e.getMessage());
                // Fallback: try with runtime exec
                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        new ProcessBuilder("cmd", "/c", "start", url).start();
                    } else if (os.contains("mac")) {
                        new ProcessBuilder("open", url).start();
                    } else {
                        new ProcessBuilder("xdg-open", url).start();
                    }
                } catch (IOException ex) {
                    alertasService.registrarAlerta("Error", "Failed to open browser with fallback: " + ex.getMessage(), null, 0, "AppLauncher.openBrowser()", null, ex.getMessage());
                }
            }
        } else {
            alertasService.registrarAlerta("Warn", "Desktop browsing is not supported", null, 0, "AppLauncher.openBrowser()", null, null);
        }
    }

    /**
     * Gracefully shuts down the application
     */
    public void shutdown() {
        if (isRunning.compareAndSet(true, false)) {
            // Clean up tray manager
            try {
                trayManager.shutdown();
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error", "Error cleaning up tray manager: " + e.getMessage(), null, 0, "AppLauncher.shutdown()", null, null);
            }
            
            // Shutdown the application
            System.exit(0);
        }
    }
}