package Services.Correos;

import Models.Correos.ReporteProgramado;
import Services.GService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.Date;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class ReportesProgramadosService extends GService<ReporteProgramado>{
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<ReporteProgramado> getEntityClass() {
        return ReporteProgramado.class;
    }
    
    @Override
    public void create(ReporteProgramado entity) {
        try {
            // Set initial next run time
            if (entity.getNextRunTime() == null && entity.getFrecuencia() != null && !entity.getFrecuencia().isEmpty()) {
                Date nextRun = new Date(); // Start with current time
                entity.setNextRunTime(nextRun);
            }
            em.persist(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating ReporteProgramado: " + e.getMessage(), null, 0, "ReportesProgramadosService.create()", null, e.getMessage());
        }
    }
    
    public boolean findByName(String perfil) {
        String jpql = "SELECT r FROM ReporteProgramado r WHERE r.perfil = :perfil";
        TypedQuery<ReporteProgramado> query = em.createQuery(jpql, ReporteProgramado.class);
        query.setParameter("perfil", perfil);
        return !query.getResultList().isEmpty();
    }
    
    public void updateAndDisable(ReporteProgramado entity) {
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
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "ReportesProgramadosService.updateAndDisable()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ReportesProgramadosService.updateAndDisable()", null, e.getMessage());
        }
    }
    
    public boolean createIfNotExists(ReporteProgramado entity) {
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

        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ReportesProgramadosService.createIfNotExists()", null, e.getMessage());
            return false;
        }
    }
    
    public void delete(ReporteProgramado entity) {
        try {
            ReporteProgramado existingItem = em.find(getEntityClass(), entity.getId());
            if (existingItem != null) {
                em.remove(existingItem);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting entity: " + e.getMessage(), null, 0, "ReportesProgramadosService.delete()", null, e.getMessage());
        }
    }

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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding next scheduled report: " + e.getMessage(), null, 0, "ReportesProgramadosService.findNextScheduledReport()", null, e.getMessage());
            return null;
        }
    }
    
}
