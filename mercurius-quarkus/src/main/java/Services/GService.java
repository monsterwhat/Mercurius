package Services;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.io.Serializable;
import java.util.List;

/**
 *
 * @author Al
 * @param <T>
 */

@Transactional
public abstract class GService<T> implements Serializable{
    public @PersistenceContext EntityManager em;
    @Inject protected AlertasService alertasService;

    protected abstract Class<T> getEntityClass();

    public List<T> listAll() {
        try {
            TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing " + getEntityClass().getSimpleName() + ": " + e.getMessage(), null, 0, "GService.listAll()", null, e.getMessage());
            return null;
        }
    }

    public void update(T entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "No entity found!", null, 0, "GService.update()", null, e.getMessage());
        }
    }

    public void create(T entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating Entity!", null, 0, "GService.create()", null, e.getMessage());
        }
    }

    public void delete(T entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity);
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "GService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting "+ getEntityClass().getSimpleName() +" : " + e.toString(), null, 0, "GService.delete()", null, e.getMessage());
        }
    }

    public Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "GService.count()", null, e.getMessage());
            return null;
        }
    }
    
    public List<T> listPage(int offset, int pageSize) {
        try {
            TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
            query.setFirstResult(offset);
            query.setMaxResults(pageSize);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing page of " + getEntityClass().getSimpleName() + ": " + e.getMessage(), null, 0, "GService.listPage()", null, e.getMessage());
            return null;
        }
    }
    
    public T find(Object id) {
        try {
            return em.find(getEntityClass(), id);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding " + getEntityClass().getSimpleName() + " with ID " + id + ": " + e.getLocalizedMessage(), null, 0, "GService.find()", null, e.getMessage());
            return null;
        }
    }
}
