package Services;

import Models.ConsecutivoEmitido;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Thread-safe sequential number generator for emitted electronic documents.
 *
 * One sequence per (sucursal, terminal, tipo) — each document type (FE=01, TE=04, NC=02, ND=03, FEE=05, FEC=08, REP=10)
 * at each point of sale gets its own independent sequence starting from 1,
 * matching Hacienda CR requirement.
 *
 * Mirrors {@link ConsecutivoReceptorService} design:
 * - {@code synchronized} method for JVM-level mutual exclusion
 * - {@link LockModeType#PESSIMISTIC_WRITE} for database-level row lock
 * - Automatic counter row creation on first use
 * - Timestamp-based fallback on persistence failure
 */
@Named
@ApplicationScoped
public class ConsecutivoEmitidoService extends GService<ConsecutivoEmitido> {

    private static final Logger LOG = Logger.getLogger(ConsecutivoEmitidoService.class);

    @Override
    @Nonnull
    protected Class<ConsecutivoEmitido> getEntityClass() {
        return ConsecutivoEmitido.class;
    }

    @PostConstruct
    public void init() {
    }

    /**
     * Atomically increments and returns the next sequential number for a given
     * sucursal + terminal + tipo combination.
     * <p>
     * Uses PESSIMISTIC_WRITE lock + synchronized to guarantee uniqueness
     * across both single-instance and multi-instance deployments.
     * <p>
     * Starts at 1 per (sucursal, terminal, tipo). Auto-creates the counter row
     * on first invocation for a new combination.
     */
    @Transactional
    public synchronized long getNextSequential(@Nonnull String sucursal, @Nonnull String terminal, @Nonnull String tipo) {
        try {
            TypedQuery<ConsecutivoEmitido> query = em.createQuery(
                "SELECT c FROM ConsecutivoEmitido c WHERE c.sucursal = :sucursal AND c.terminal = :terminal AND c.tipo = :tipo",
                ConsecutivoEmitido.class);
            query.setParameter("sucursal", sucursal);
            query.setParameter("terminal", terminal);
            query.setParameter("tipo", tipo);
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            query.setMaxResults(1);

            ConsecutivoEmitido counter;
            try {
                counter = query.getSingleResult();
            } catch (NoResultException e) {
                counter = new ConsecutivoEmitido();
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

            return nextVal;
        } catch (PersistenceException e) {
            LOG.warn("Error generating consecutive number: " + e.getMessage()
                + " | source=ConsecutivoEmitidoService.getNextSequential()"
                + " | despues=" + e.getMessage());
            throw new RuntimeException("Cannot generate consecutive number for " + sucursal + "-" + terminal + "-" + tipo + ": " + e.getMessage(), e);
        }
    }
}
