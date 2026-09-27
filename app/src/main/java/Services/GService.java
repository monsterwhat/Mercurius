package Services;

import org.jboss.logging.Logger;
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
    private static final Logger LOG = Logger.getLogger(GService.class);
    public @PersistenceContext @Nonnull EntityManager em;

    protected abstract @Nonnull Class<T> getEntityClass();

    /**
     * Best-effort listing: every row of {@code T}, or an EMPTY list if the query fails.
     *
     * <p><strong>The empty result is ambiguous on purpose and must be treated as such.</strong>
     * A caller cannot tell "this table has no rows" apart from "the query blew up", so any
     * caller that reads emptiness as authoritative (rendering it to a UI, counting it, or
     * looking up a row that is expected to exist) is silently wrong on a transient
     * PersistenceException. That is not hypothetical: it hid a failed re-read of a
     * just-inserted invoice row behind an empty stream and made the lookup "fail".
     *
     * <p>This leniency is kept so the existing {@code listAll()} callers (page/report/table
     * renderers, {@code @PostConstruct} initialisers) keep their current behaviour. Failures
     * are now logged at ERROR with the throwable, so they are never silent again.
     *
     * <p>Callers that cannot treat empty as authoritative MUST use
     * {@link #listAllOrThrow()} instead.
     *
     * @return every row of {@code T}, or an empty list if the query failed
     * @see #listAllOrThrow()
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<T> listAll() {
        try {
            return queryAll();
        } catch (PersistenceException e) {
            LOG.error("Error listing " + getEntityClass().getSimpleName() + " in GService.listAll()"
                    + "; returning an EMPTY list, so callers cannot distinguish 'no rows' from 'query failed'"
                    + " - use listAllOrThrow() where that distinction matters"
                    + " | source=GService.listAll() | despues=" + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Fail-fast listing: same query as {@link #listAll()}, but a failed query is NOT
     * flattened into an empty list - the {@link PersistenceException} propagates to the
     * caller.
     *
     * <p>Use this whenever an empty result would be wrong: re-reading a row that must
     * exist, feeding a calculation, or serving data where "nothing configured" and "the
     * query failed" must not look identical. Either let it propagate (Quarkus maps it to a
     * 5xx) or catch it and produce a deliberate fallback.
     *
     * @return every row of {@code T}; never empty because of a failure
     * @throws jakarta.persistence.PersistenceException if the query fails
     * @see #listAll()
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<T> listAllOrThrow() {
        return queryAll();
    }

    /**
     * Shared JPQL execution for {@link #listAll()} / {@link #listAllOrThrow()}.
     *
     * <p>The entity name is still resolved from {@code getEntityClass().getSimpleName()}.
     * It is left as-is deliberately: the metamodel-based alternative
     * ({@code em.getMetamodel().entity(getEntityClass()).getName()}) throws
     * {@link IllegalArgumentException} - not {@link PersistenceException} - for a class
     * that is not a managed entity, so moving it in front of the {@code catch} would turn a
     * lenient call into a hard failure and change the contract of every existing caller.
     */
    private @Nonnull List<T> queryAll() {
        TypedQuery<T> query = em.createQuery("SELECT e FROM " + getEntityClass().getSimpleName() + " e", getEntityClass());
        return query.getResultList();
    }

    @Transactional
    public void update(@Nonnull T entity) {
        try {
            em.merge(entity);
            em.flush();
        } catch (PersistenceException e) {
                        LOG.warn("No entity found!" + " | source=" + "GService.update()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    public void create(@Nonnull T entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
                        LOG.warn("Error creating Entity!" + " | source=" + "GService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                                LOG.info("Entity not found" + " | source=" + "GService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.warn("Error deleting "+ getEntityClass().getSimpleName() +" : " + e.toString() + " | source=" + "GService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional(TxType.SUPPORTS)
    public @Nonnull Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=" + "GService.count()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error listing page of " + getEntityClass().getSimpleName() + ": " + e.getMessage() + " | source=" + "GService.listPage()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error finding " + getEntityClass().getSimpleName() + " with ID " + id + ": " + e.getLocalizedMessage() + " | source=" + "GService.find()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
}
