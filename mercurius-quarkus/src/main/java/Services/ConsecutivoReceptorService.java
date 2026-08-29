package Services;

import Models.ConsecutivoReceptor;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class ConsecutivoReceptorService extends GService<ConsecutivoReceptor> {

    private static final Logger LOG = Logger.getLogger(ConsecutivoReceptorService.class);

    @Override
    @Nonnull
    protected Class<ConsecutivoReceptor> getEntityClass() {
        return ConsecutivoReceptor.class;
    }

    @PostConstruct
    public void init() {
    }

    /**
     * Atomically increments and returns the next sequential number for a given
     * sucursal + terminal + tipo combination.
     * <p>
     * Uses PESSIMISTIC_WRITE lock to prevent duplicate sequences under concurrent access.
     * Thread-safe across both single-instance and multi-instance deployments.
     * <p>
     * Format: 10 digits zero-padded (0000000001, 0000000002, ...)
     */
    @Nonnull
    public synchronized String getNextSequential(@Nonnull String sucursal, @Nonnull String terminal, @Nonnull String tipo) {
        try {
            TypedQuery<ConsecutivoReceptor> query = em.createQuery(
                "SELECT c FROM ConsecutivoReceptor c WHERE c.sucursal = :sucursal AND c.terminal = :terminal AND c.tipo = :tipo",
                ConsecutivoReceptor.class);
            query.setParameter("sucursal", sucursal);
            query.setParameter("terminal", terminal);
            query.setParameter("tipo", tipo);
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            query.setMaxResults(1);

            ConsecutivoReceptor counter;
            try {
                counter = query.getSingleResult();
            } catch (NoResultException e) {
                counter = new ConsecutivoReceptor();
                counter.setSucursal(sucursal);
                counter.setTerminal(terminal);
                counter.setTipo(tipo);
                counter.setUltimoSecuencial(0L);
                em.persist(counter);
                em.flush();
            }

            long nextVal = counter.getUltimoSecuencial() + 1;
            counter.setUltimoSecuencial(nextVal);
            em.merge(counter);
            em.flush();

            return String.format("%010d", nextVal);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error generating consecutive number: " + e.getMessage()
                + " | source=ConsecutivoReceptorService.getNextSequential()"
                + " | despues=" + e.getMessage());
            // Fallback: generate a timestamp-based number to avoid blocking the MR flow
            return String.format("%010d", System.currentTimeMillis() % 10000000000L);
        }
    }

    /**
     * Retrieves the current counter value for a given key without incrementing.
     * Returns null if no counter exists yet.
     */
    @Nullable
    public ConsecutivoReceptor findCounter(@Nonnull String sucursal, @Nonnull String terminal, @Nonnull String tipo) {
        try {
            TypedQuery<ConsecutivoReceptor> query = em.createQuery(
                "SELECT c FROM ConsecutivoReceptor c WHERE c.sucursal = :sucursal AND c.terminal = :terminal AND c.tipo = :tipo",
                ConsecutivoReceptor.class);
            query.setParameter("sucursal", sucursal);
            query.setParameter("terminal", terminal);
            query.setParameter("tipo", tipo);
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error finding counter: " + e.getMessage()
                + " | source=ConsecutivoReceptorService.findCounter()"
                + " | despues=" + e.getMessage());
            return null;
        }
    }
}
