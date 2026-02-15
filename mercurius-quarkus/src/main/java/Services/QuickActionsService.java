package Services;

import Models.UserShortcut;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("quickActionsService")
public class QuickActionsService {

    @Inject
    EntityManager entityManager;

    @Transactional
    public List<UserShortcut> getUserShortcuts(String username) {
        return entityManager.createQuery(
            "SELECT s FROM UserShortcut s WHERE s.username = :username ORDER BY s.displayOrder, s.usageCount DESC",
            UserShortcut.class
        ).setParameter("username", username)
         .getResultList();
    }

    @Transactional
    public List<UserShortcut> getFavoriteActions(String username) {
        return entityManager.createQuery(
            "SELECT s FROM UserShortcut s WHERE s.username = :username AND s.isFavorite = true ORDER BY s.displayOrder",
            UserShortcut.class
        ).setParameter("username", username)
         .getResultList();
    }

    @Transactional
    public List<UserShortcut> getMostUsedActions(String username, int limit) {
        return entityManager.createQuery(
            "SELECT s FROM UserShortcut s WHERE s.username = :username ORDER BY s.usageCount DESC",
            UserShortcut.class
        ).setParameter("username", username)
         .setMaxResults(limit)
         .getResultList();
    }

    @Transactional
    public UserShortcut addShortcut(UserShortcut shortcut) {
        UserShortcut existing = entityManager.createQuery(
            "SELECT s FROM UserShortcut s WHERE s.username = :username AND s.actionKey = :actionKey",
            UserShortcut.class
        ).setParameter("username", shortcut.getUsername())
         .setParameter("actionKey", shortcut.getActionKey())
         .getResultStream().findFirst().orElse(null);

        if (existing != null) {
            existing.setShortcutKey(shortcut.getShortcutKey());
            existing.setDisplayOrder(shortcut.getDisplayOrder());
            entityManager.merge(existing);
            return existing;
        }

        entityManager.persist(shortcut);
        return shortcut;
    }

    @Transactional
    public UserShortcut toggleFavorite(Long shortcutId) {
        UserShortcut shortcut = entityManager.find(UserShortcut.class, shortcutId);
        if (shortcut != null) {
            shortcut.setIsFavorite(!shortcut.getIsFavorite());
            entityManager.merge(shortcut);
        }
        return shortcut;
    }

    @Transactional
    public void incrementUsage(Long shortcutId) {
        UserShortcut shortcut = entityManager.find(UserShortcut.class, shortcutId);
        if (shortcut != null) {
            shortcut.setUsageCount(shortcut.getUsageCount() + 1);
            shortcut.setLastUsed(new Date());
            entityManager.merge(shortcut);
        }
    }

    @Transactional
    public void deleteShortcut(Long shortcutId) {
        UserShortcut shortcut = entityManager.find(UserShortcut.class, shortcutId);
        if (shortcut != null) {
            entityManager.remove(shortcut);
        }
    }

    @Transactional
    public void initializeDefaultShortcuts(String username) {
        String[][] defaults = {
            {"new_sale", "Nueva Venta", "Ctrl+N", "/ventas.xhtml", "pi pi-shopping-cart", "1"},
            {"new_invoice", "Nueva Factura", "Ctrl+F", "/facturas.xhtml", "pi pi-file", "2"},
            {"products", "Productos", "Ctrl+P", "/productos.xhtml", "pi pi-box", "3"},
            {"inventory", "Inventario", "Ctrl+I", "/inventario.xhtml", "pi pi-list", "4"},
            {"clients", "Clientes", "Ctrl+C", "/clientes.xhtml", "pi pi-users", "5"},
            {"reports", "Reportes", "Ctrl+R", "/reportes.xhtml", "pi pi-chart-bar", "6"},
            {"settings", "Configuración", "Ctrl+,", "/settings.xhtml", "pi pi-cog", "7"},
            {"dashboard", "Dashboard", "Ctrl+D", "/dashboard.xhtml", "pi pi-home", "8"}
        };

        for (String[] def : defaults) {
            UserShortcut existing = entityManager.createQuery(
                "SELECT s FROM UserShortcut s WHERE s.username = :username AND s.actionKey = :actionKey",
                UserShortcut.class
            ).setParameter("username", username)
             .setParameter("actionKey", def[0])
             .getResultStream().findFirst().orElse(null);

            if (existing == null) {
                UserShortcut shortcut = new UserShortcut();
                shortcut.setUsername(username);
                shortcut.setActionKey(def[0]);
                shortcut.setActionLabel(def[1]);
                shortcut.setShortcutKey(def[2]);
                shortcut.setActionUrl(def[3]);
                shortcut.setIconClass(def[4]);
                shortcut.setDisplayOrder(Integer.parseInt(def[5]));
                shortcut.setIsFavorite(false);
                shortcut.setUsageCount(0);
                entityManager.persist(shortcut);
            }
        }
    }

    @Transactional
    public void reorderShortcuts(String username, List<Long> shortcutIds) {
        for (int i = 0; i < shortcutIds.size(); i++) {
            UserShortcut shortcut = entityManager.find(UserShortcut.class, shortcutIds.get(i));
            if (shortcut != null && shortcut.getUsername().equals(username)) {
                shortcut.setDisplayOrder(i);
                entityManager.merge(shortcut);
            }
        }
    }

    public List<QuickSearchResult> quickSearch(String query, String username) {
        return getUserShortcuts(username).stream()
            .filter(s -> s.getActionLabel().toLowerCase().contains(query.toLowerCase()) ||
                        s.getActionKey().toLowerCase().contains(query.toLowerCase()))
            .limit(10)
            .map(s -> new QuickSearchResult(
                s.getActionKey(),
                s.getActionLabel(),
                s.getActionUrl(),
                s.getShortcutKey(),
                s.getIconClass()
            ))
            .collect(Collectors.toList());
    }

    public static class QuickSearchResult {
        private String key;
        private String label;
        private String url;
        private String shortcut;
        private String icon;

        public QuickSearchResult(String key, String label, String url, String shortcut, String icon) {
            this.key = key;
            this.label = label;
            this.url = url;
            this.shortcut = shortcut;
            this.icon = icon;
        }

        public String getKey() { return key; }
        public String getLabel() { return label; }
        public String getUrl() { return url; }
        public String getShortcut() { return shortcut; }
        public String getIcon() { return icon; }
    }
}
