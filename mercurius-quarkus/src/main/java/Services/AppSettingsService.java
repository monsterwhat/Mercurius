package Services;

import Models.AppSettings;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;

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
                entity = em.find(getEntityClass(), entity);
            }

            if (entity != null) {
                entity.setEstatus(false);
                em.merge(entity);
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


}
