package Utils;

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
    
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    @Inject
    SystemTrayManager trayManager;

    void onStart(@Observes StartupEvent event) {
        try {
            // Check if application is already running
            if (!isSingleInstance()) {
                System.err.println("Mercurius is already running!");
                return; // Don't exit immediately, let Quarkus handle it
            }
            
            isRunning.set(true);
            
            // Initialize system tray (only on Windows) - do this in background with proper error handling
            Thread trayThread = new Thread(() -> {
                try {
                    trayManager.initializeTray();
                } catch (Exception e) {
                    // Silently ignore tray initialization failures on non-Windows systems
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        System.err.println("System tray initialization failed: " + e.getMessage());
                    } else {
                        System.out.println("System tray not initialized (non-Windows system)");
                    }
                }
            });
            trayThread.setName("TrayInitializer");
            trayThread.setDaemon(true);
            trayThread.start();
            
            // Open browser after a short delay to ensure server is ready
            openBrowserAfterDelay(3000);
            
        } catch (Exception e) {
            System.err.println("Error during application startup: " + e.getMessage());
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
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }));
            
            return true;
        } catch (Exception e) {
            System.err.println("Error checking single instance: " + e.getMessage());
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
    public static void openBrowser(String url) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (IOException | URISyntaxException e) {
                System.err.println("Error opening browser: " + e.getMessage());
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
                    System.err.println("Failed to open browser with fallback method: " + ex.getMessage());
                }
            }
        } else {
            System.err.println("Desktop browsing is not supported");
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
            } catch (Exception e) {
                System.err.println("Error cleaning up tray manager: " + e.getMessage());
            }
            
            // Shutdown the application
            System.exit(0);
        }
    }
}