package Services;

import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Models.Encabezado.Encabezado;
import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Date;
import java.util.List;

@Named
@ApplicationScoped
public class ComprobantesEmitidosService extends GService<ComprobantesEmitidos> {
    
    @Override
    protected Class<ComprobantesEmitidos> getEntityClass() {
        return ComprobantesEmitidos.class;
    }

    @PostConstruct
    public void init() {
    }

@Override
    public void create(ComprobantesEmitidos entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }
    
    public ComprobantesEmitidos createAndReturn(ComprobantesEmitidos entity) {
        try {
            em.persist(entity);
            em.flush(); // Ensure the entity is persisted and gets an ID
            em.refresh(entity); // Refresh to get any database-generated values
            return entity;
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
            return null;
        }
    }

    @Override
    public void delete(ComprobantesEmitidos entity) {
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
    public void update(ComprobantesEmitidos entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }
    
    public void softDelete(ComprobantesEmitidos entity) {
        try {
            // Find the item by its ID
            ComprobantesEmitidos existingItem = em.find(getEntityClass(), entity.getId());

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
    
    public void toggle(ComprobantesEmitidos entity){
        try {
            // Find the item by its ID
            ComprobantesEmitidos existingItem = em.find(getEntityClass(), entity.getId());

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
    public List<ComprobantesEmitidos> listAll() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH ld.codigosComerciales cc " +
                "LEFT JOIN FETCH ld.descuentos des " +
                "LEFT JOIN FETCH ld.impuestos imp " +
                "LEFT JOIN FETCH d.otrosCargos oc",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
     
    public List<ComprobantesEmitidos> listAllEmitidosBy(Users user) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH ld.codigosComerciales cc " +
                "LEFT JOIN FETCH ld.descuentos des " +
                "LEFT JOIN FETCH ld.impuestos imp " +
                "LEFT JOIN FETCH d.otrosCargos oc " +
                "WHERE f.user = :user",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user);
            List<ComprobantesEmitidos> result = query.getResultList();
            System.out.println("Query returned " + (result != null ? result.size() : "null") + " invoices for user " + user.getUsername());
            return result;
        } catch (Exception e) {
            System.out.println("Error listing entities for user: " + e.toString());
            return null;
        }
    }
    
    public List<ComprobantesEmitidos> listAllEmitidosBy(Users user, Date startDate, Date endDate) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH ld.codigosComerciales cc " +
                "LEFT JOIN FETCH ld.descuentos des " +
                "LEFT JOIN FETCH ld.impuestos imp " +
                "LEFT JOIN FETCH d.otrosCargos oc " +
                "WHERE f.user = :user " +
                "AND f.encabezado.fechaEmision BETWEEN :startDate AND :endDate",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user);
            query.setParameter("startDate", startDate);
            query.setParameter("endDate", endDate);
            List<ComprobantesEmitidos> result = query.getResultList();
            System.out.println("Query returned " + (result != null ? result.size() : "null") + " invoices for user " + user.getUsername() + " between " + startDate + " and " + endDate);
            return result;
        } catch (Exception e) {
            System.out.println("Error listing entities for user with date range: " + e.toString());
            return null;
        }
    }


    public boolean findByNumeroConsecutivo(String numeroConsecutivo) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery("SELECT f FROM ComprobantesRecibidos f WHERE f.encabezado.numeroConsecutivo = :numeroConsecutivo", ComprobantesEmitidos.class);
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            // Attempt to get a single result
            ComprobantesEmitidos factura = query.getSingleResult();
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
    
    public ComprobantesEmitidos findLastTransactionByUser(Users user) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.detalles d " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user);
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            System.out.println("Error finding last transaction for user: " + e.toString());
            return null;
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

    public List<ComprobantesEmitidos> findFacturasPendientes() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND (e.estado IS NULL OR e.estado = 'PENDIENTE') " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error finding facturas pendientes: " + e.toString());
            return null;
        }
    }

    public List<ComprobantesEmitidos> findFacturasAceptadas() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND e.estado = 'ACEPTADO' " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error finding facturas aceptadas: " + e.toString());
            return null;
        }
    }

    public List<ComprobantesEmitidos> findFacturasRechazadas() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND e.estado = 'RECHAZADO' " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error finding facturas rechazadas: " + e.toString());
            return null;
        }
    }

    public Long countFacturasPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                "LEFT JOIN f.encabezado e " +
                "WHERE f.status = true " +
                "AND (e.estado IS NULL OR e.estado = 'PENDIENTE')",
                Long.class
            );
            return query.getSingleResult();
        } catch (Exception e) {
            System.out.println("Error counting facturas pendientes: " + e.toString());
            return 0L;
        }
    }

}
