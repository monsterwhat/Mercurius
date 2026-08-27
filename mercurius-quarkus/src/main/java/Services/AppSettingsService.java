package Services;

import Models.AppSettings;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped; 
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
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "AppSettingsService.disable()", null, null);
            }
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting "+ getEntityClass().getSimpleName() +" : " + e.getMessage(), null, 0, "AppSettingsService.disable()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error: " + e.getMessage(), null, 0, "AppSettingsService.returnCurrent()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error fallback: " + e.getMessage(), null, 0,
                "AppSettingsService.findOrCreateCurrent()", null, e.getMessage());
        }

        current = new AppSettings();
        current.setEstatus(true);
        em.persist(current);
        return current;
    }

}
