package Services.Facturas;

import Models.ComprobantesV44.Encabezado.Encabezado;
import Services.GService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.NoResultException;
import java.util.List;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class EncabezadoService extends GService<Encabezado> {
    
    @PersistenceContext EntityManager em;
    
    @Override
    protected Class<Encabezado> getEntityClass() {
        return Encabezado.class;
    }
    
    @Override
    public void create(Encabezado encabezado) {
        try {
            em.merge(encabezado);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
    public Encabezado createIfNotExists(Encabezado encabezado) {
        try {
            // Check if encabezado with same numeroConsecutivo exists using count query
            if (existsByNumeroConsecutivo(encabezado.getNumeroConsecutivo())) {
                System.out.println("Using existing encabezado for: " + encabezado.getNumeroConsecutivo());
                return null; // Return null since it's already processed
            }
            
            // Create new encabezado if not exists
            em.merge(encabezado);
            System.out.println("Successfully created encabezado for: " + encabezado.getNumeroConsecutivo());
            return encabezado;
        } catch (Exception e) {
            System.out.println("Error creating or finding encabezado: " + e.toString());
            return null;
        }
    }
    
    public boolean existsByNumeroConsecutivo(String numeroConsecutivo) {
        try {
            // Use simple query to check existence
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(e.id) FROM Encabezado e WHERE e.numeroConsecutivo = :numeroConsecutivo", 
                Long.class
            );
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            Long count = query.getSingleResult();
            return count != null && count > 0;
        } catch (Exception e) {
            System.out.println("Error checking encabezado existence: " + e.toString());
            return false;
        }
    }
    
    // New method to find existing encabezado and handle duplicates properly
    public Encabezado findExistingEncabezado(String numeroConsecutivo) {
        try {
            jakarta.persistence.Query query = em.createNativeQuery(
                "SELECT e.* FROM Encabezado e WHERE e.numeroConsecutivo = ? ORDER BY e.id DESC LIMIT 1", 
                Encabezado.class
            );
            query.setParameter(1, numeroConsecutivo);
            List<Encabezado> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            System.out.println("Error finding existing encabezado: " + e.toString());
            return null;
        }
    }
    
public Encabezado getByNumeroConsecutivo(String numeroConsecutivo) {
        try {
            // Find existing record, handle duplicates gracefully
            return findExistingEncabezado(numeroConsecutivo);
        } catch (Exception e) {
            System.out.println("Error getting encabezado by numeroConsecutivo: " + e.toString());
            return null;
        }
    }
    
    // Method to clean up duplicate Encabezado records by numeroConsecutivo
    public int cleanDuplicateEncabezados(String numeroConsecutivo) {
        try {
            TypedQuery<Encabezado> query = em.createQuery(
                "SELECT e FROM Encabezado e WHERE e.numeroConsecutivo = :numeroConsecutivo ORDER BY e.id DESC", 
                Encabezado.class
            );
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            List<Encabezado> duplicates = query.getResultList();
            
            if (duplicates.size() > 1) {
                // Keep the latest record, delete the rest
                Encabezado latest = duplicates.get(0);
                for (int i = 1; i < duplicates.size(); i++) {
                    em.remove(duplicates.get(i));
                }
                em.flush();
                return duplicates.size() - 1; // Number of duplicates removed
            }
            return 0;
        } catch (Exception e) {
            System.out.println("Error cleaning duplicate encabezados: " + e.toString());
            return 0;
        }
    }
    
}
