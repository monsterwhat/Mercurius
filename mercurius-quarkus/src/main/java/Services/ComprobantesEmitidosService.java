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
import java.time.LocalDateTime;

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
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.create()", null, e.getMessage());
        }
    }
    
    public ComprobantesEmitidos createAndReturn(ComprobantesEmitidos entity) {
        try {
            em.persist(entity);
            em.flush(); // Ensure the entity is persisted and gets an ID
            em.refresh(entity); // Refresh to get any database-generated values
            return entity;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.createAndReturn()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "ComprobantesEmitidosService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ComprobantesEmitidosService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(ComprobantesEmitidos entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.update()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found for softDelete", null, 0, "ComprobantesEmitidosService.softDelete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error soft deleting entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.softDelete()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found for toggle", null, 0, "ComprobantesEmitidosService.toggle()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error toggling entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.toggle()", null, e.getMessage());
        }
    }
    
    @Override
    public List<ComprobantesEmitidos> listAll() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f LEFT JOIN FETCH f.resumen",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listAll()", null, e.getMessage());
            return java.util.Collections.emptyList();
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
            alertasService.registrarAlerta("Info", "Query returned " + result.size() + " invoices for user " + user.getUsername(), null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, null);
            return result;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing entities for user: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, e.getMessage());
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
            alertasService.registrarAlerta("Info", "Query returned " + result.size() + " invoices for user " + user.getUsername() + " between " + startDate + " and " + endDate, null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, null);
            return result;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing entities for user with date range: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error finding entity by numeroConsecutivo: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findByNumeroConsecutivo()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error finding last transaction for user: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findLastTransactionByUser()", null, e.getMessage());
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

    public List<ComprobantesEmitidos> listByDateRange(Date start, Date end) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE e.fechaEmision BETWEEN :start AND :end " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesEmitidos.class
            );
            query.setParameter("start", start);
            query.setParameter("end", end);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing by date range: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listByDateRange()", null, e.getMessage());
            return null;
        }
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
            alertasService.registrarAlerta("Error", "Error finding facturas pendientes: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasPendientes()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error finding facturas aceptadas: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasAceptadas()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error finding facturas rechazadas: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasRechazadas()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error counting facturas pendientes: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.countFacturasPendientes()", null, e.getMessage());
            return 0L;
        }
    }

    public List<ComprobantesEmitidos> findByClave(String clave) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f WHERE f.haciendaClave = :clave",
                ComprobantesEmitidos.class
            );
            query.setParameter("clave", clave);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding by clave: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findByClave()", null, e.getMessage());
            return null;
        }
    }

    public List<ComprobantesEmitidos> findFacturasParaVerificarEstado() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE f.status = true " +
                "AND f.haciendaEstado = 'ENVIADO' " +
                "AND (f.haciendaFechaRespuesta IS NULL " +
                "     OR f.haciendaFechaEnvio IS NULL " +
                "     OR f.haciendaFechaRespuesta < f.haciendaFechaEnvio) " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding facturas para verificar estado: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasParaVerificarEstado()", null, e.getMessage());
            return null;
        }
    }

    public List<ComprobantesEmitidos> findFacturasSinRespuesta3Horas() {
        try {
            LocalDateTime hace3Horas = LocalDateTime.now().minusHours(3);
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE f.status = true " +
                "AND f.haciendaEstado = 'ENVIADO' " +
                "AND f.haciendaFechaEnvio IS NOT NULL " +
                "AND f.haciendaFechaEnvio < :hace3Horas " +
                "AND (f.haciendaFechaRespuesta IS NULL OR f.haciendaFechaRespuesta < f.haciendaFechaEnvio) " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            query.setParameter("hace3Horas", hace3Horas);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding facturas sin respuesta 3h: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasSinRespuesta3Horas()", null, e.getMessage());
            return null;
        }
    }

}
