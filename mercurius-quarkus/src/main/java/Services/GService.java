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
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(GService.class.getName());
    public @PersistenceContext @Nonnull EntityManager em;

    protected abstract @Nonnull Class<T> getEntityClass();

    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<T> listAll() {
        try {
            TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing " + getEntityClass().getSimpleName() + ": " + e.getMessage() + " | source=" + "GService.listAll()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    @Transactional
    public void update(@Nonnull T entity) {
        try {
            em.merge(entity);
            em.flush();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "No entity found!" + " | source=" + "GService.update()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    public void create(@Nonnull T entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating Entity!" + " | source=" + "GService.create()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
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
                em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "GService.delete()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting "+ getEntityClass().getSimpleName() +" : " + e.toString() + " | source=" + "GService.delete()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional(TxType.SUPPORTS)
    public @Nonnull Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=" + "GService.count()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.log(java.util.logging.Level.WARNING, "Error listing page of " + getEntityClass().getSimpleName() + ": " + e.getMessage() + " | source=" + "GService.listPage()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }
    
    @Transactional(TxType.SUPPORTS)
    public @Nullable T find(@Nonnull Object id) {
        try {
            try { em.flush(); } catch (Exception ignore) {}
            em.clear();
            return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding " + getEntityClass().getSimpleName() + " with ID " + id + ": " + e.getLocalizedMessage() + " | source=" + "GService.find()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
}
