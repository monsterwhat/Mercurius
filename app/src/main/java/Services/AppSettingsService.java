package Services;

import Models.AppSettings;
import Utils.EncryptionUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.enterprise.event.Observes;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class AppSettingsService extends GService<AppSettings> {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AppSettingsService.class);

    @Override
    protected @Nonnull Class<AppSettings> getEntityClass() {
        return AppSettings.class;
    }
         
    public void disable(@Nonnull AppSettings entity) {
        try {
            if (!em.contains(entity)) {
                Object id = em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
                entity = em.find(getEntityClass(), id);
            }

            if (entity != null) {
                entity.setEstatus(false);
                em.merge(entity);
                em.flush();
                LOG.info("app settings disabled");
            } else {
                LOG.warn("app settings disable: entity not found");
            }
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.error("failed to disable app settings", e);
        }
    }
     
    public @Nullable AppSettings returnCurrent() {
        try {
            TypedQuery<AppSettings> query = em.createQuery("SELECT a FROM AppSettings a WHERE a.estatus = true", getEntityClass());
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("failed to load current app settings", e);
            return null;
        }
    }

    /**
     * Returns the current active settings, or falls back to the most recent row
     * by Id if no row has estatus=true. Creates and returns a new settings row
     * only if the table is completely empty.
     */
    @Transactional
    public @Nonnull AppSettings findOrCreateCurrent() {
        AppSettings current = returnCurrent();
        if (current != null) {
            return current;
        }

        try {
            TypedQuery<AppSettings> query = em.createQuery(
                "SELECT a FROM AppSettings a ORDER BY a.Id DESC", getEntityClass());
            query.setMaxResults(1);
            current = query.getSingleResult();
            current.setEstatus(true);
            return em.merge(current);
        } catch (NoResultException e) {
            // table empty — fall through to create
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("failed to find fallback app settings", e);
        }

        current = new AppSettings();
        current.setEstatus(true);
        em.persist(current);
        return current;
    }

    @Transactional
    public String getOrCreateAuthSessionKey() {
        AppSettings s = findOrCreateCurrent();
        if (s.getAuthSessionKey() != null && !s.getAuthSessionKey().isEmpty() && s.getAuthSessionKey().length() >= 32) {
            return s.getAuthSessionKey();
        }
        String newKey = EncryptionUtil.generateKey();
        s.setAuthSessionKey(newKey);
        em.merge(s);
        LOG.info("generated new DB-managed authSessionKey");
        return newKey;
    }

    @Transactional
    public String getOrCreateHaciendaEncryptionKey() {
        AppSettings s = findOrCreateCurrent();
        if (s.getHaciendaEncryptionKey() != null && !s.getHaciendaEncryptionKey().isEmpty() && s.getHaciendaEncryptionKey().length() >= 32) {
            return s.getHaciendaEncryptionKey();
        }
        String newKey = EncryptionUtil.generateKey();
        s.setHaciendaEncryptionKey(newKey);
        em.merge(s);
        LOG.info("generated new DB-managed haciendaEncryptionKey");
        return newKey;
    }

    @Transactional
    public boolean rotateAuthSessionKey() {
        AppSettings s = returnCurrent();
        if (s == null) return false;
        String newKey = EncryptionUtil.generateKey();
        s.setAuthSessionKey(newKey);
        em.merge(s);
        LOG.warn("auth session key rotated, all existing sessions invalidated");
        return true;
    }

    @Transactional
    public boolean rotateHaciendaEncryptionKey() {
        // Rotation requires re-encrypting existing secrets — delegate to HaciendaCertificateService
        return false;
    }

    void onStart(@Observes StartupEvent ev) {
        try {
            getOrCreateAuthSessionKey();
            getOrCreateHaciendaEncryptionKey();
            LOG.info("DB-managed keys ensured on startup");
        } catch (Exception e) {
            LOG.error("failed to ensure DB session keys on startup", e);
        }
    }

}
