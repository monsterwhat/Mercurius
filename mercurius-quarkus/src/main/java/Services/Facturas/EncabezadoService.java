package Services.Facturas;

import Models.Encabezado.Encabezado;
import Services.GService;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class EncabezadoService extends GService<Encabezado> {

    private static final Logger LOG = Logger.getLogger(EncabezadoService.class);
    
    @PersistenceContext @Nonnull EntityManager em;
    
    @Override
    @Nonnull
    protected Class<Encabezado> getEntityClass() {
        return Encabezado.class;
    }
    
    @Override
    @Transactional
    public void create(@Nonnull Encabezado encabezado) {
        try {
            em.merge(encabezado);
        } catch (PersistenceException e) {
                        LOG.warn("Error creating Entity!" + " | source=" + "EncabezadoService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
    @Nullable
    @Transactional
    public Encabezado createIfNotExists(@Nonnull Encabezado encabezado) {
        try {
            // Check if encabezado with same numeroConsecutivo exists using count query
            if (existsByNumeroConsecutivo(encabezado.getNumeroConsecutivo())) {
                                LOG.info("Using existing encabezado for: " + encabezado.getNumeroConsecutivo() + " | source=" + "EncabezadoService.createIfNotExists()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                return null;
            }
            
            // Create new encabezado if not exists
            em.merge(encabezado);
                        LOG.info("Successfully created encabezado for: " + encabezado.getNumeroConsecutivo() + " | source=" + "EncabezadoService.createIfNotExists()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            return encabezado;
        } catch (PersistenceException e) {
                        LOG.warn("Error creating or finding encabezado: " + e.getMessage() + " | source=" + "EncabezadoService.createIfNotExists()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    public boolean existsByNumeroConsecutivo(@Nonnull String numeroConsecutivo) {
        try {
            // Use simple query to check existence
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(e.id) FROM Encabezado e WHERE e.numeroConsecutivo = :numeroConsecutivo", 
                Long.class
            );
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            Long count = query.getSingleResult();
            return count != null && count > 0;
        } catch (PersistenceException e) {
                        LOG.warn("Error checking encabezado existence: " + e.getMessage() + " | source=" + "EncabezadoService.existsByNumeroConsecutivo()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }
    
    // New method: Check if there's a valid ComprobantesRecibidos with this numeroConsecutivo
    // This ignores orphaned encabezados that have no parent ComprobantesRecibidos
    public boolean existsByNumeroConsecutivoWithValidComprobante(@Nonnull String numeroConsecutivo) {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(c.id) FROM ComprobantesRecibidos c " +
                "WHERE c.encabezado.numeroConsecutivo = :numeroConsecutivo", 
                Long.class
            );
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            Long count = query.getSingleResult();
            return count != null && count > 0;
        } catch (PersistenceException e) {
                        LOG.warn("Error checking valid comprobante existence: " + e.getMessage() + " | source=" + "EncabezadoService.existsByNumeroConsecutivoWithValidComprobante()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }
    
    // New method to find existing encabezado and handle duplicates properly
    @Nullable
    public Encabezado findExistingEncabezado(@Nonnull String numeroConsecutivo) {
        try {
            TypedQuery<Encabezado> query = em.createQuery(
                "SELECT e FROM Encabezado e WHERE e.numeroConsecutivo = :numeroConsecutivo ORDER BY e.id DESC",
                Encabezado.class
            );
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            query.setMaxResults(1);
            List<Encabezado> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
                        LOG.warn("Error finding existing encabezado: " + e.getMessage() + " | source=" + "EncabezadoService.findExistingEncabezado()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
@Nullable
public Encabezado getByNumeroConsecutivo(@Nonnull String numeroConsecutivo) {
        try {
            // Find existing record, handle duplicates gracefully
            return findExistingEncabezado(numeroConsecutivo);
        } catch (PersistenceException e) {
                        LOG.warn("Error getting encabezado by numeroConsecutivo: " + e.getMessage() + " | source=" + "EncabezadoService.getByNumeroConsecutivo()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    // Method to clean up duplicate Encabezado records by numeroConsecutivo
    @Transactional
    public int cleanDuplicateEncabezados(@Nonnull String numeroConsecutivo) {
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
        } catch (PersistenceException e) {
                        LOG.warn("Error cleaning duplicate encabezados: " + e.getMessage() + " | source=" + "EncabezadoService.cleanDuplicateEncabezados()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return 0;
        }
    }
    
}
