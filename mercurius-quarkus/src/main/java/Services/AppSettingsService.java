package Services;

import Models.AppSettings;
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
    protected Class<AppSettings> getEntityClass() {
        return AppSettings.class;
    }
        
    public void disable(AppSettings entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity);
            }

            if (entity != null) {
                entity.setEstatus(false);
                em.merge(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting "+ getEntityClass().getSimpleName() +" : " + e.toString());
        }
    }
     
    public AppSettings returnCurrent() {
        try {
            TypedQuery<AppSettings> query = em.createQuery("SELECT a FROM AppSettings a WHERE a.estatus = true", getEntityClass());
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
    }


}
