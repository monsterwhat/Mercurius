package Utils;

import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
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

    private static final Logger LOG = Logger.getLogger(AppLauncher.class);

    private static final String LOCK_FILE_PATH = System.getProperty("java.io.tmpdir") + "/mercurius.lock";
    private static final String APP_URL = "http://localhost:8081/Mercurius/index.xhtml";
    
    @Nonnull private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    @Inject @Nonnull
    SystemTrayManager trayManager;

    void onStart(@Observes StartupEvent event) {
        try {
            // Check if application is already running
            if (!isSingleInstance()) {
                LOG.warn("failed to on start");
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
                        LOG.warn("failed to on start", e);
                    } else {
                        LOG.info("failed to on start");
                    }
                }
            });
            trayThread.setName("TrayInitializer");
            trayThread.setDaemon(true);
            trayThread.start();

            // Open browser after a short delay to ensure server is ready
            openBrowserAfterDelay(3000);

        } catch (RuntimeException e) {
            LOG.warn("failed to on start", e);
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
            LOG.warn("failed to is single instance", e);
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
                LOG.warn("failed to open browser", e);
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
                    LOG.warn("failed to open browser");
                }
            }
        } else {
            LOG.info("failed to open browser");
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
                LOG.warn("failed to shutdown", e);
            }
            
            // Shutdown the application
            System.exit(0);
        }
    }
}