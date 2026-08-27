package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Al
 * @param <T>
 */

public abstract class GService<T> implements Serializable{
    public @PersistenceContext @Nonnull EntityManager em;
    @Inject protected @Nonnull AlertasService alertasService;

    protected abstract @Nonnull Class<T> getEntityClass();

    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<T> listAll() {
        try {
            TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing " + getEntityClass().getSimpleName() + ": " + e.getMessage(), null, 0, "GService.listAll()", null, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional
    public void update(@Nonnull T entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "No entity found!", null, 0, "GService.update()", null, e.getMessage());
        }
    }

    @Transactional
    public void create(@Nonnull T entity) {
        try {
            em.persist(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating Entity!", null, 0, "GService.create()", null, e.getMessage());
        }
    }

    @Transactional
    public void delete(@Nonnull T entity) {
        try {
            if (!em.contains(entity)) {
                Object id = em.getEntityManagerFactory()
                        .getPersistenceUnitUtil().getIdentifier(entity);
                entity = em.find(getEntityClass(), id);
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "GService.delete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting "+ getEntityClass().getSimpleName() +" : " + e.toString(), null, 0, "GService.delete()", null, e.getMessage());
        }
    }

    @Transactional(TxType.SUPPORTS)
    public @Nonnull Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "GService.count()", null, e.getMessage());
            return 0L;
        }
    }
    
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<T> listPage(int offset, int pageSize) {
        try {
            TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
            query.setFirstResult(offset);
            query.setMaxResults(pageSize);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing page of " + getEntityClass().getSimpleName() + ": " + e.getMessage(), null, 0, "GService.listPage()", null, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    @Transactional(TxType.SUPPORTS)
    public @Nullable T find(@Nonnull Object id) {
        try {
            em.clear();
            return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding " + getEntityClass().getSimpleName() + " with ID " + id + ": " + e.getLocalizedMessage(), null, 0, "GService.find()", null, e.getMessage());
            return null;
        }
    }
}
