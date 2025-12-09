package Services;

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

    protected abstract Class<T> getEntityClass();

    public List<T> listAll() {
        try {
            TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
            return query.getResultList();
        } catch (Exception e) {
            return null;
        }
    }

    public void update(T entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("No entity found!");
        }
    }

    public void create(T entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
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
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting "+ getEntityClass().getSimpleName() +" : " + e.toString());
        }
    }

    public Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            System.out.println("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage());
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
            return null;
        }
    }
    
    public T find(Object id) {
        try {
            return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding " + getEntityClass().getSimpleName() + " with ID " + id + ": " + e.getLocalizedMessage());
            return null;
        }
    }
}
