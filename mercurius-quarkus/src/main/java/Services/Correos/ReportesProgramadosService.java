package Services.Correos;

import Models.Correos.ReporteProgramado;
import Services.GService;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.Date;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class ReportesProgramadosService extends GService<ReporteProgramado>{
    
    private static final Logger LOG = Logger.getLogger(ReportesProgramadosService.class);

    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<ReporteProgramado> getEntityClass() {
        return ReporteProgramado.class;
    }
    
    @Override
    @Transactional
    public void create(@Nonnull ReporteProgramado entity) {
        try {
            // Set initial next run time
            if (entity.getNextRunTime() == null && entity.getFrecuencia() != null && !entity.getFrecuencia().isEmpty()) {
                Date nextRun = new Date(); // Start with current time
                entity.setNextRunTime(nextRun);
            }
            em.persist(entity);
        } catch (PersistenceException e) {
            LOG.warn("failed to create", e);
        }
    }
    
    public boolean findByName(@Nonnull String perfil) {
        String jpql = "SELECT r FROM ReporteProgramado r WHERE r.perfil = :perfil";
        TypedQuery<ReporteProgramado> query = em.createQuery(jpql, ReporteProgramado.class);
        query.setParameter("perfil", perfil);
        return !query.getResultList().isEmpty();
    }
    
    @Transactional
    public void updateAndDisable(@Nonnull ReporteProgramado entity) {
        try {
            // Find the existing item by its ID
            ReporteProgramado existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Disable the existing item if enabled
                if(existingItem.isStatus()){
                    existingItem.setStatus(false);
                }
                em.merge(existingItem);
                
                //If we are updating an existing disabled record we need to re-enable it...
                if(!entity.isStatus()){
                    entity.setStatus(true);
                }
                em.persist(entity);
            } else {
                LOG.info("failed to update and disable");
            }
        } catch (PersistenceException e) {
            LOG.warn("failed to update and disable", e);
        }
    }
    
    @Transactional
    public boolean createIfNotExists(@Nonnull ReporteProgramado entity) {
        try {
            String queryStr = "SELECT COUNT(f) FROM ReporteProgramado f WHERE f.perfil = :nombre";
            Long count = em.createQuery(queryStr, Long.class)
                           .setParameter("nombre", entity.getPerfil())
                           .getSingleResult();

            if (count > 0) {
                return false;
            } else {
                em.persist(entity);
                return true;
            }

        } catch (PersistenceException e) {
            LOG.warn("failed to create if not exists", e);
            return false;
        }
    }
    
    @Transactional
    public void delete(@Nonnull ReporteProgramado entity) {
        try {
            ReporteProgramado existingItem = em.find(getEntityClass(), entity.getId());
            if (existingItem != null) {
                em.remove(existingItem);
            }
        } catch (PersistenceException e) {
            LOG.warn("failed to delete", e);
        }
    }

    @Nullable
    public ReporteProgramado findNextScheduledReport() {
        try {
            TypedQuery<ReporteProgramado> query = em.createQuery(
                "SELECT r FROM ReporteProgramado r " +
                "WHERE r.status = true " +
                "AND r.nextRunTime > :now " +
                "ORDER BY r.nextRunTime ASC",
                ReporteProgramado.class
            );
            query.setParameter("now", new java.util.Date());
            query.setMaxResults(1);
            
            java.util.List<ReporteProgramado> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
            LOG.warn("failed to find next scheduled report", e);
            return null;
        }
    }
    
}
