package Services;

import Models.ComprobantesV44.ComprobantesRecibidos;
import Models.ComprobantesV44.Encabezado.Encabezado;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Stateless; 
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@Named
@Stateless
public class ComprobantesRecibidosService extends GService<ComprobantesRecibidos> {
    
    @Override
    protected Class<ComprobantesRecibidos> getEntityClass() {
        return ComprobantesRecibidos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(ComprobantesRecibidos entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(ComprobantesRecibidos entity) {
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
    public void update(ComprobantesRecibidos entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }
    
    public void softDelete(ComprobantesRecibidos entity) {
        try {
            // Find the item by its ID
            ComprobantesRecibidos existingItem = em.find(getEntityClass(), entity.getId());

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
    
    public void toggle(ComprobantesRecibidos entity){
        try {
            // Find the item by its ID
            ComprobantesRecibidos existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                //Toggle the item from state
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                }else{
                    existingItem.setStatus(true);
                }
                em.merge(existingItem);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error soft deleting entity: " + e.toString());
        }
    }
    
    @Override
    public List<ComprobantesRecibidos> listAll() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH ld.codigosComerciales cc " +
                "LEFT JOIN FETCH ld.descuentos des " +
                "LEFT JOIN FETCH ld.impuestos imp " +
                "LEFT JOIN FETCH d.otrosCargos oc",
                ComprobantesRecibidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public List<ComprobantesRecibidos> ListAllEnabled() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery("SELECT f FROM ComprobantesRecibidos f WHERE f.Status = true", ComprobantesRecibidos.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public boolean findByNumeroConsecutivo(String numeroConsecutivo) {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery("SELECT f FROM ComprobantesRecibidos f WHERE f.encabezado.numeroConsecutivo = :numeroConsecutivo", ComprobantesRecibidos.class);
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            // Attempt to get a single result
            ComprobantesRecibidos factura = query.getSingleResult();
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
    
    public List<ComprobantesRecibidos> findComprobantesAfterDate(Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ComprobantesRecibidos> cq = cb.createQuery(ComprobantesRecibidos.class);
        Root<ComprobantesRecibidos> ComprobantesRecibidos = cq.from(ComprobantesRecibidos.class);
        Join<ComprobantesRecibidos, Encabezado> encabezado = ComprobantesRecibidos.join("encabezado");

        Predicate datePredicate = cb.greaterThan(encabezado.get("fechaEmision"), fecha);
        cq.where(datePredicate);

        TypedQuery<ComprobantesRecibidos> query = em.createQuery(cq);
        return query.getResultList();
    }

   public List<ComprobantesRecibidos> listPendientes() {
        String sql = "SELECT t1.* FROM ComprobantesRecibidos t1 " +
                     "JOIN ENCABEZADO t0 ON t0.ID = t1.ENCABEZADO_ID " +
                     "WHERE DATE_ADD(t0.fecha_emision, INTERVAL t0.plazo_credito DAY) > ?1 " +
                     "AND t1.paid = 0";

        Query query = em.createNativeQuery(sql, ComprobantesRecibidos.class);
        query.setParameter(1, java.sql.Date.valueOf(LocalDate.now()));
        return query.getResultList();
    }

    public List<ComprobantesRecibidos> listVencidas() {
        String sql = "SELECT t1.* FROM ComprobantesRecibidos t1 " +
                     "JOIN ENCABEZADO t0 ON t0.ID = t1.ENCABEZADO_ID " +
                     "WHERE DATE_ADD(t0.fecha_emision, INTERVAL t0.plazo_credito DAY) <= ?1 " +
                     "AND t1.paid = 0";

        Query query = em.createNativeQuery(sql, ComprobantesRecibidos.class);
        query.setParameter(1, java.sql.Date.valueOf(LocalDate.now()));
        return query.getResultList();
    }




}
