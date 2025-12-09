package Services.Correos;

import Models.Correos.ReporteProgramado;
import Services.GService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

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
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
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
            System.out.println("Error creating entity: " + e.toString());
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
            System.out.println("Error deleting entity: " + e.toString());
        }
    }
    
}
