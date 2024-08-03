package Services;

import Models.Comprobantes.ComprobanteFinal;
import Models.Comprobantes.Encabezado.Encabezado;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Named
public class FacturaService extends GService<ComprobanteFinal> {
    
    @Override
    protected Class<ComprobanteFinal> getEntityClass() {
        return ComprobanteFinal.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(ComprobanteFinal entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(ComprobanteFinal entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }

    @Override
    public void update(ComprobanteFinal entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }
    
    public void updateAndDisable(ComprobanteFinal entity) {
        try {
            // Find the existing item by its ID
            ComprobanteFinal existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Disable the existing item
                existingItem.setStatus(false);
                em.merge(existingItem);

                // Create a new item with the updated information
                em.persist(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }
    
    
    public void softDelete(ComprobanteFinal entity) {
        try {
            // Find the item by its ID
            ComprobanteFinal existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error soft deleting entity: " + e.toString());
        }
    }

    public List<ComprobanteFinal> listAllOld() {
        try {
            TypedQuery<ComprobanteFinal> query = em.createQuery("SELECT f FROM ComprobanteFinal f", ComprobanteFinal.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    @Override
    public List<ComprobanteFinal> listAll() {
        try {
            TypedQuery<ComprobanteFinal> query = em.createQuery(
                "SELECT f FROM ComprobanteFinal f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH ld.codigosComerciales cc " +
                "LEFT JOIN FETCH ld.descuentos des " +
                "LEFT JOIN FETCH ld.impuestos imp " +
                "LEFT JOIN FETCH d.otrosCargos oc",
                ComprobanteFinal.class
            );
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public ComprobanteFinal findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public List<ComprobanteFinal> ListAllEnabled() {
        try {
            TypedQuery<ComprobanteFinal> query = em.createQuery("SELECT f FROM ComprobanteFinal f WHERE f.Status = true", ComprobanteFinal.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public boolean findByNumeroConsecutivo(String numeroConsecutivo) {
        try {
            TypedQuery<ComprobanteFinal> query = em.createQuery("SELECT f FROM ComprobanteFinal f WHERE f.encabezado.numeroConsecutivo = :numeroConsecutivo", ComprobanteFinal.class);
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            // Attempt to get a single result
            ComprobanteFinal factura = query.getSingleResult();
            // If a result is found, return true
            return factura != null;
        } catch (NoResultException e) {
            // If no result is found, catch the NoResultException and return false
            return false;
        } catch (Exception e) {
            System.out.println("Error finding entity by numeroConsecutivo: " + e.toString());
            return false;
        }
    }
    
    public List<ComprobanteFinal> findComprobantesAfterDate(Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ComprobanteFinal> cq = cb.createQuery(ComprobanteFinal.class);
        Root<ComprobanteFinal> comprobanteFinal = cq.from(ComprobanteFinal.class);
        Join<ComprobanteFinal, Encabezado> encabezado = comprobanteFinal.join("encabezado");

        Predicate datePredicate = cb.greaterThan(encabezado.get("fechaEmision"), fecha);
        cq.where(datePredicate);

        TypedQuery<ComprobanteFinal> query = em.createQuery(cq);
        return query.getResultList();
    }

   public List<ComprobanteFinal> listPendientes() {
        String sql = "SELECT t1.* FROM COMPROBANTEFINAL t1 " +
                     "JOIN ENCABEZADO t0 ON t0.ID = t1.ENCABEZADO_ID " +
                     "WHERE DATE_ADD(t0.fecha_emision, INTERVAL t0.plazo_credito DAY) > ?1";

        Query query = em.createNativeQuery(sql, ComprobanteFinal.class);
        query.setParameter(1, java.sql.Date.valueOf(LocalDate.now()));
        return query.getResultList();
    }

    public List<ComprobanteFinal> listVencidas() {
        String sql = "SELECT t1.* FROM COMPROBANTEFINAL t1 " +
                     "JOIN ENCABEZADO t0 ON t0.ID = t1.ENCABEZADO_ID " +
                     "WHERE DATE_ADD(t0.fecha_emision, INTERVAL t0.plazo_credito DAY) <= ?1";

        Query query = em.createNativeQuery(sql, ComprobanteFinal.class);
        query.setParameter(1, java.sql.Date.valueOf(LocalDate.now()));
        return query.getResultList();
    }




}
