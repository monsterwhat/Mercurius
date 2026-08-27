package Services;

import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import Models.Users;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.time.LocalDateTime;

@Named
@ApplicationScoped
public class ComprobantesEmitidosService extends GService<ComprobantesEmitidos> {
    
    @Override
    protected @Nonnull Class<ComprobantesEmitidos> getEntityClass() {
        return ComprobantesEmitidos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull ComprobantesEmitidos entity) {
        try {
            em.persist(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.create()", null, e.getMessage());
        }
    }
    
    @Transactional
    public @Nullable ComprobantesEmitidos createAndReturn(@Nonnull ComprobantesEmitidos entity) {
        try {
            em.persist(entity);
            em.flush(); // Ensure the entity is persisted and gets an ID
            em.refresh(entity); // Refresh to get any database-generated values
            return entity;
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.createAndReturn()", null, e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public void delete(@Nonnull ComprobantesEmitidos entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "ComprobantesEmitidosService.delete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ComprobantesEmitidosService.delete()", null, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void update(@Nonnull ComprobantesEmitidos entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.update()", null, e.getMessage());
        }
    }
    
    @Transactional
    public void softDelete(@Nonnull ComprobantesEmitidos entity) {
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
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error soft deleting entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.softDelete()", null, e.getMessage());
        }
    }
    
    @Transactional
    public void toggle(@Nonnull ComprobantesEmitidos entity){
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
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error toggling entity: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.toggle()", null, e.getMessage());
        }
    }
    
    @Override
    public @Nonnull List<ComprobantesEmitidos> listAll() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesEmitidos f LEFT JOIN FETCH f.resumen LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.emisor",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listAll()", null, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }
      
    public @Nullable List<ComprobantesEmitidos> listAllEmitidosBy(@Nonnull Users user) {
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
            query.setParameter("user", user.getUsername());
            List<ComprobantesEmitidos> result = query.getResultList();
            alertasService.registrarAlerta("Info", "Query returned " + result.size() + " invoices for user " + user.getUsername(), null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, null);
            return result;
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing entities for user: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, e.getMessage());
            return null;
        }
    }
    
    public @Nullable List<ComprobantesEmitidos> listAllEmitidosBy(@Nonnull Users user, @Nonnull Date startDate, @Nonnull Date endDate) {
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
            query.setParameter("user", user.getUsername());
            query.setParameter("startDate", startDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            query.setParameter("endDate", endDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            List<ComprobantesEmitidos> result = query.getResultList();
            alertasService.registrarAlerta("Info", "Query returned " + result.size() + " invoices for user " + user.getUsername() + " between " + startDate + " and " + endDate, null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, null);
            return result;
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing entities for user with date range: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listAllEmitidosBy()", null, e.getMessage());
            return null;
        }
    }


    public boolean findByNumeroConsecutivo(@Nonnull String numeroConsecutivo) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery("SELECT f FROM ComprobantesEmitidos f WHERE f.encabezado.numeroConsecutivo = :numeroConsecutivo", ComprobantesEmitidos.class);
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            // Attempt to get a single result
            ComprobantesEmitidos factura = query.getSingleResult();
            // If a result is found, return true
            return factura != null;
        } catch (NoResultException e) {
            // If no result is found, catch the NoResultException and return false
            return false;
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding entity by numeroConsecutivo: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findByNumeroConsecutivo()", null, e.getMessage());
            return false;
        }
    }
    
    public @Nullable ComprobantesEmitidos findLastTransactionByUser(@Nonnull Users user) {
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
            query.setParameter("user", user.getUsername());
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding last transaction for user: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findLastTransactionByUser()", null, e.getMessage());
            return null;
        }
    }
    
    public @Nonnull List<ComprobantesEmitidos> findComprobantesAfterDate(@Nonnull Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ComprobantesEmitidos> cq = cb.createQuery(ComprobantesEmitidos.class);
        Root<ComprobantesEmitidos> comprobanteRoot = cq.from(ComprobantesEmitidos.class);
        Join<ComprobantesEmitidos, Encabezado> encabezado = comprobanteRoot.join("encabezado");

        Predicate datePredicate = cb.greaterThan(encabezado.get("fechaEmision"), fecha);
        cq.where(datePredicate);

        TypedQuery<ComprobantesEmitidos> query = em.createQuery(cq);
        return query.getResultList();
    }

    public @Nullable List<ComprobantesEmitidos> listByDateRange(@Nonnull Date start, @Nonnull Date end) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE e.fechaEmision BETWEEN :start AND :end " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesEmitidos.class
            );
            LocalDateTime startLdt = start.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime endLdt = end.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            query.setParameter("start", startLdt);
            query.setParameter("end", endLdt);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing by date range: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.listByDateRange()", null, e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasPendientes() {
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
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding facturas pendientes: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasPendientes()", null, e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasAceptadas() {
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
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding facturas aceptadas: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasAceptadas()", null, e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasRechazadas() {
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
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding facturas rechazadas: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasRechazadas()", null, e.getMessage());
            return null;
        }
    }

    public @Nonnull Long countFacturasPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                "LEFT JOIN f.encabezado e " +
                "WHERE f.status = true " +
                "AND (e.estado IS NULL OR e.estado = 'PENDIENTE')",
                Long.class
            );
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting facturas pendientes: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.countFacturasPendientes()", null, e.getMessage());
            return 0L;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findByClave(@Nonnull String clave) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f WHERE f.haciendaClave = :clave",
                ComprobantesEmitidos.class
            );
            query.setParameter("clave", clave);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding by clave: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findByClave()", null, e.getMessage());
            return null;
        }
    }

    public @Nonnull List<ComprobantesEmitidos> findFacturasPendientesEnvio() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND f.haciendaEstado = 'PENDIENTE' " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding facturas pendientes de envio: " + e.getMessage(),
                null, 0, "ComprobantesEmitidosService.findFacturasPendientesEnvio()", null, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasParaVerificarEstado() {
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
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding facturas para verificar estado: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasParaVerificarEstado()", null, e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasSinRespuesta3Horas() {
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
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding facturas sin respuesta 3h: " + e.getMessage(), null, 0, "ComprobantesEmitidosService.findFacturasSinRespuesta3Horas()", null, e.getMessage());
            return null;
        }
    }

}
