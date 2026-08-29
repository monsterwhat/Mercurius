package Utils;

import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URL;

/**
 * Manages the system tray icon for the Mercurius application
 */
@ApplicationScoped
public class SystemTrayManager {

    private static final Logger LOG = Logger.getLogger(SystemTrayManager.class);

    @Inject @Nonnull
    AppLauncher appLauncher;

    private SystemTray tray;
    private TrayIcon trayIcon;
    private final Object trayLock = new Object();
    private volatile boolean initialized = false;

    public void initializeTray() {
        // Early return if already initialized
        if (initialized) {
            LOG.info("failed to initialize tray");
            return;
        }
        
        synchronized (trayLock) {
            // Double-check after acquiring lock
            if (initialized) {
                LOG.info("failed to initialize tray");
                return;
            }
            
            // Only enable tray on Windows desktop environments
            String os = System.getProperty("os.name").toLowerCase();
            if (!os.contains("win")) {
                LOG.info("failed to initialize tray");
                return;
            }
            
            // Check if running in headless mode
            if (GraphicsEnvironment.isHeadless()) {
                LOG.info("failed to initialize tray");
                return;
            }
            
            if (!SystemTray.isSupported()) {
                LOG.info("failed to initialize tray");
                return;
            }

            try {
                tray = SystemTray.getSystemTray();
                
                // Check if there's already a tray icon in the system tray
                for (TrayIcon existingIcon : tray.getTrayIcons()) {
                    if ("Mercurius".equals(existingIcon.getToolTip())) {
                        LOG.info("failed to initialize tray");
                        initialized = true;
                        return;
                    }
                }
                
                // Load or create tray icon
                Image trayIconImage = createTrayIconImage();
                
                // Create popup menu
                PopupMenu popup = createPopupMenu();
                
                // Create tray icon
                trayIcon = new TrayIcon(trayIconImage, "Mercurius", popup);
                trayIcon.setImageAutoSize(true);
                
                // Add mouse listeners for double-click
                trayIcon.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) {
                            // Double click - open browser
                            appLauncher.openBrowser("http://localhost:8081/Mercurius/index.xhtml");
                        }
                    }
                });
                
                // Add tray icon to system tray
                tray.add(trayIcon);
                initialized = true;
                
                LOG.info("failed to initialize tray");
                
            } catch (AWTException e) {
                LOG.warn("failed to initialize tray", e);
                initialized = false;
            }
        }
    }

    /**
     * Creates or loads the tray icon image
     */
    private Image createTrayIconImage() {
        // Try to load the Mercurius logo first
        URL imageUrl = getClass().getResource("/META-INF/resources/resources/imgs/logo/Mercurius.png");
        if (imageUrl != null) {
            Image image = Toolkit.getDefaultToolkit().createImage(imageUrl);
            // Scale to appropriate size for tray
            return image.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        }
        
        // Fallback: create a simple colored icon
        int size = tray.getTrayIconSize().width;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        
        // Create a simple blue circle with "M" text
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(new Color(52, 152, 219)); // Nice blue color
        g2d.fillOval(0, 0, size, size);
        
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, size / 2));
        FontMetrics fm = g2d.getFontMetrics();
        int x = (size - fm.stringWidth("M")) / 2;
        int y = (size + fm.getAscent()) / 2;
        g2d.drawString("M", x, y);
        
        g2d.dispose();
        return image;
    }

    /**
     * Creates the popup menu for the tray icon
     */
    private PopupMenu createPopupMenu() {
        PopupMenu popup = new PopupMenu();
        
        // Open Browser menu item
        MenuItem openItem = new MenuItem("Open Mercurius");
        openItem.addActionListener(e -> appLauncher.openBrowser("http://localhost:8081/Mercurius/index.xhtml"));
        popup.add(openItem);
        
        popup.addSeparator();
        
        // About menu item
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.addActionListener(e -> showAboutDialog());
        popup.add(aboutItem);
        
        popup.addSeparator();
        
        // Exit menu item
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> {
            try {
                if (tray != null && trayIcon != null) {
                    tray.remove(trayIcon);
                }
                } catch (RuntimeException ex) {
                LOG.warn("failed to create popup menu");
            }
            System.exit(0);
        });
        popup.add(exitItem);
        
        return popup;
    }

    /**
     * Shows an about dialog
     */
    private void showAboutDialog() {
        showSimpleAboutDialog();
    }

    /**
     * Simple about dialog as fallback
     */
    private void showSimpleAboutDialog() {
        // This would require a proper GUI implementation
        // For now, just print to console
        LOG.info("failed to show simple about dialog");
        LOG.info("failed to show simple about dialog");
    }

    /**
     * Shows a notification in the system tray
     */
    public void showNotification(@Nonnull String title, @Nonnull String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    /**
     * Updates the tray icon to show a different state
     */
    public void updateIcon(boolean isRunning) {
        if (trayIcon != null) {
            String tooltip = isRunning ? "Mercurius - Running" : "Mercurius - Stopped";
            trayIcon.setToolTip(tooltip);
        }
    }

    /**
     * Checks if the tray icon is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Removes the tray icon
     */
    public void removeTrayIcon() {
        synchronized (trayLock) {
            if (tray != null && trayIcon != null) {
                try {
                    tray.remove(trayIcon);
                    trayIcon = null;
                    initialized = false;
                    LOG.info("failed to remove tray icon");
                } catch (RuntimeException e) {
                    LOG.warn("failed to remove tray icon", e);
                }
            }
        }
    }
    
    /**
     * Shutdown method to clean up resources
     */
    public void shutdown() {
        synchronized (trayLock) {
            removeTrayIcon();
            tray = null;
            initialized = false;
        }
    }
}