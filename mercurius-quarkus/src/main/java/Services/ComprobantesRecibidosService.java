package Services;

import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Inject;
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
import java.time.LocalDate;

@Named
@ApplicationScoped
public class ComprobantesRecibidosService extends GService<ComprobantesRecibidos> {
    
    @Inject AlertasService alertasService;

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
            em.flush();
            em.refresh(entity);
            alertasService.registrarAlerta("Info", "Successfully created ComprobantesRecibidos with ID: " + entity.getId(), null, 0, "ComprobantesRecibidosService.create()", null, null);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.create()", null, e.getMessage());
            throw new RuntimeException("Failed to create ComprobantesRecibidos", e);
        }
    }
    
    // Method to create ComprobantesRecibidos with pre-persisted related entities
    // Uses proper cascading to ensure atomic transaction - if any entity fails, all rollback
    public void createWithRelatedEntities(ComprobantesRecibidos entity, Encabezado encabezado, ResumenFactura resumenFactura) {
        try {
            // Set relationships - let JPA handle cascading from the root entity
            entity.setEncabezado(encabezado);
            entity.setResumen(resumenFactura);
            
            // Persist only the root entity - cascade will handle related entities
            // This ensures atomic transaction: if anything fails, everything rolls back
            em.persist(entity);
            em.flush();
            em.refresh(entity);
            
            alertasService.registrarAlerta("Info", "Successfully created ComprobantesRecibidos with ID: " + entity.getId(), null, 0, "ComprobantesRecibidosService.createWithRelatedEntities()", null, null);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity with related entities: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.createWithRelatedEntities()", null, e.getMessage());
            throw new RuntimeException("Failed to create ComprobantesRecibidos with related entities", e);
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
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "ComprobantesRecibidosService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ComprobantesRecibidosService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(ComprobantesRecibidos entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.update()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found for softDelete", null, 0, "ComprobantesRecibidosService.softDelete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error soft deleting entity: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.softDelete()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found for toggle", null, 0, "ComprobantesRecibidosService.toggle()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error toggling entity: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.toggle()", null, e.getMessage());
        }
    }
    
    @Override
    public List<ComprobantesRecibidos> listAll() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen",
                ComprobantesRecibidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.listAll()", null, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }
    
    public List<ComprobantesRecibidos> ListAllEnabled() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.status = true",
                ComprobantesRecibidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.ListAllEnabled()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error finding entity by numeroConsecutivo: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.findByNumeroConsecutivo()", null, e.getMessage());
            return false;
        }
}
    
    public ComprobantesRecibidos findByIdWithDetails(Long id) {
        try {
            // First fetch the main entity with basic relationships
            ComprobantesRecibidos entity = em.find(ComprobantesRecibidos.class, id);
            if (entity == null) {
                return null;
            }
            
            // Initialize the detalles collection if it exists
            if (entity.getDetalles() != null) {
                em.refresh(entity.getDetalles());
                
                // Initialize lineasDetalle collection separately
                if (entity.getDetalles().getLineasDetalle() != null) {
                    entity.getDetalles().getLineasDetalle().size(); // Force initialization
                    
                    // Initialize nested collections for each line
                    for (LineaDetalle linea : entity.getDetalles().getLineasDetalle()) {
                        if (linea.getCodigosComerciales() != null) {
                            linea.getCodigosComerciales().size();
                        }
                        if (linea.getDescuentos() != null) {
                            linea.getDescuentos().size();
                        }
                        if (linea.getImpuestos() != null) {
                            linea.getImpuestos().size();
                        }
                    }
                }
            }
            
            // Initialize other relationships
            if (entity.getEncabezado() != null) {
                em.refresh(entity.getEncabezado());
            }
            if (entity.getResumen() != null) {
                em.refresh(entity.getResumen());
            }
            
            return entity;
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding ComprobantesRecibidos by ID with details: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.findByIdWithDetails()", null, e.getMessage());
            return null;
        }
    }
    
    public List<ComprobantesRecibidos> listByDateRange(Date start, Date end) {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE e.fechaEmision BETWEEN :start AND :end " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesRecibidos.class
            );
            query.setParameter("start", start);
            query.setParameter("end", end);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing recibidos by date range: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.listByDateRange()", null, e.getMessage());
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

   public List<ComprobantesRecibidos> listPendientes() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.paid = false",
                ComprobantesRecibidos.class
            );
            List<ComprobantesRecibidos> results = query.getResultList();
            java.time.LocalDate currentDate = java.time.LocalDate.now();
            
            // Filter in Java - date due date is in the future
            return results.stream()
                .filter(f -> {
                    if (f.getEncabezado() != null && f.getEncabezado().getPlazoCredito() != null && f.getEncabezado().getFechaEmision() != null) {
                        try {
                            int plazoCredito = Integer.parseInt(f.getEncabezado().getPlazoCredito());
                            java.time.LocalDate dueDate = f.getEncabezado().getFechaEmision().toLocalDate().plusDays(plazoCredito);
                            return dueDate.isAfter(currentDate);
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
                })
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing pendientes: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.listPendientes()", null, e.getMessage());
            return null;
        }
    }

    public List<ComprobantesRecibidos> listVencidas() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.paid = false",
                ComprobantesRecibidos.class
            );
            List<ComprobantesRecibidos> results = query.getResultList();
            java.time.LocalDate currentDate = java.time.LocalDate.now();
            
            // Filter in Java - due date is today or in the past
            return results.stream()
                .filter(f -> {
                    if (f.getEncabezado() != null && f.getEncabezado().getPlazoCredito() != null && f.getEncabezado().getFechaEmision() != null) {
                        try {
                            int plazoCredito = Integer.parseInt(f.getEncabezado().getPlazoCredito());
                            java.time.LocalDate dueDate = f.getEncabezado().getFechaEmision().toLocalDate().plusDays(plazoCredito);
                            return dueDate.isBefore(currentDate) || dueDate.isEqual(currentDate);
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
                })
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing vencidas: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.listVencidas()", null, e.getMessage());
            return null;
    }

    public List<ComprobantesRecibidos> findPendientesMensajeReceptor() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.status = true " +
                "AND (f.haciendaMensajeReceptorEstado IS NULL OR f.haciendaMensajeReceptorEstado = 'PENDIENTE') " +
                "ORDER BY f.encabezado.fechaEmision DESC",
                ComprobantesRecibidos.class
            );
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding pendientes Mensaje Receptor: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.findPendientesMensajeReceptor()", null, e.getMessage());
            return null;
        }
    }

    public List<ComprobantesRecibidos> findProximosVencerMensajeReceptor(int dias) {
        try {
            LocalDate limite = LocalDate.now().plusDays(dias);
            List<ComprobantesRecibidos> todos = findPendientesMensajeReceptor();
            if (todos == null) return java.util.Collections.emptyList();
            
            return todos.stream()
                .filter(f -> f.getMensajeReceptorLimite() != null && !f.getMensajeReceptorLimite().isAfter(limite))
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding proximos vencer Mensaje Receptor: " + e.getMessage(), null, 0, "ComprobantesRecibidosService.findProximosVencerMensajeReceptor()", null, e.getMessage());
            return null;
        }
    }

}


}
