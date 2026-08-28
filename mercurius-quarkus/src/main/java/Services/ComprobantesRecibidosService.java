package Services;

import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import Models.Validacion.PrevalidationResult;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Inject;
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
import java.util.Date;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.logging.Logger;

@Named
@ApplicationScoped
public class ComprobantesRecibidosService extends GService<ComprobantesRecibidos> {
    
    private static final Logger LOG = Logger.getLogger(ComprobantesRecibidosService.class.getName());

    @Inject @Nonnull ComprobantesRecibidosPrevalidationService prevalidationService;

    @Override
    protected Class<ComprobantesRecibidos> getEntityClass() {
        return ComprobantesRecibidos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(ComprobantesRecibidos entity) {
        try {
            em.persist(entity);
            em.flush();
            em.refresh(entity);
            LOG.info("Successfully created ComprobantesRecibidos with ID: " + entity.getId() + " | source=ComprobantesRecibidosService.create()");
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.create() | despues=" + e.getMessage());
            throw new RuntimeException("Failed to create ComprobantesRecibidos", e);
        }
    }
    
    // Method to create ComprobantesRecibidos with pre-persisted related entities
    // Uses proper cascading to ensure atomic transaction - if any entity fails, all rollback
    @Transactional
    @Nonnull
    public PrevalidationResult createWithRelatedEntities(@Nonnull ComprobantesRecibidos entity, @Nonnull Encabezado encabezado, @Nonnull ResumenFactura resumenFactura) {
        PrevalidationResult prevalidation = null;
        try {
            entity.setEncabezado(encabezado);
            entity.setResumen(resumenFactura);

            // Pre-validate — record errors but ALWAYS persist
            prevalidation = prevalidationService.prevalidarCompleto(entity);
            if (prevalidation.hasErrors() || prevalidation.hasWarnings()) {
                List<Models.Validacion.ValidationError> allIssues = prevalidation.getAllIssues();
                String errorSummary = allIssues.stream()
                    .map(e -> e.getField() + ": " + e.getMessage())
                    .collect(java.util.stream.Collectors.joining("; "));
                entity.setPrevalidationErrors(errorSummary);
                LOG.log(java.util.logging.Level.WARNING,
                    "Pre-validation warnings for " + (entity.getEncabezado() != null ? entity.getEncabezado().getNumeroConsecutivo() : "?") +
                    ": " + errorSummary
                    + " | source=ComprobantesRecibidosService.createWithRelatedEntities()"
                    + " | despues=" + errorSummary);
            }

            em.persist(entity);
            em.flush();
            em.refresh(entity);

            String consecutive = entity.getEncabezado() != null ? entity.getEncabezado().getNumeroConsecutivo() : String.valueOf(entity.getId());
            LOG.info("Successfully created ComprobantesRecibidos: " + consecutive + " | source=ComprobantesRecibidosService.createWithRelatedEntities()");
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error creating entity with related entities: " + e.getMessage() + " | source=ComprobantesRecibidosService.createWithRelatedEntities() | despues=" + e.getMessage());
            throw new RuntimeException("Failed to create ComprobantesRecibidos with related entities", e);
        }
        return prevalidation;
    }

    @Override
    @Transactional
    public void delete(ComprobantesRecibidos entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                LOG.info("Entity not found for delete | source=ComprobantesRecibidosService.delete()");
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=ComprobantesRecibidosService.delete() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void update(ComprobantesRecibidos entity) {
        try {
            ComprobantesRecibidos merged = em.merge(entity);
            em.flush();
            em.refresh(merged);
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error updating entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.update() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public void softDelete(@Nonnull ComprobantesRecibidos entity) {
        try {
            // Find the item by its ID
            ComprobantesRecibidos existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            em.flush();
            } else {
                LOG.info("Entity not found for softDelete | source=ComprobantesRecibidosService.softDelete()");
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error soft deleting entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.softDelete() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public void toggle(@Nonnull ComprobantesRecibidos entity){
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
            em.flush();
            } else {
                LOG.info("Entity not found for toggle | source=ComprobantesRecibidosService.toggle()");
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error toggling entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.toggle() | despues=" + e.getMessage());
        }
    }
    
    @Override @Nonnull
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
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=ComprobantesRecibidosService.listAll() | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }
    
    @Nullable
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
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing all enabled entities: " + e.getMessage() + " | source=ComprobantesRecibidosService.ListAllEnabled() | despues=" + e.getMessage());
            return null;
        }
    }

    public boolean findByNumeroConsecutivo(@Nonnull String numeroConsecutivo) {
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
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error finding entity by numeroConsecutivo: " + e.getMessage() + " | source=ComprobantesRecibidosService.findByNumeroConsecutivo() | despues=" + e.getMessage());
            return false;
        }
}
    
    @Transactional
    @Nullable
    public ComprobantesRecibidos findByIdWithDetails(@Nonnull Long id) {
        try {
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
            if (entity.getInformacionReferencia() != null) {
                entity.getInformacionReferencia().size();
            }
            
            return entity;
        } catch (NoResultException e) {
            return null;
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error finding ComprobantesRecibidos by ID with details: " + e.getMessage() + " | source=ComprobantesRecibidosService.findByIdWithDetails() | despues=" + e.getMessage());
            return null;
        }
    }
    
    @Nullable
    public List<ComprobantesRecibidos> listByDateRange(@Nonnull Date start, @Nonnull Date end) {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE e.fechaEmision BETWEEN :start AND :end " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesRecibidos.class
            );
            query.setParameter("start", start.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            query.setParameter("end", end.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing recibidos by date range: " + e.getMessage() + " | source=ComprobantesRecibidosService.listByDateRange() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<ComprobantesRecibidos> findComprobantesAfterDate(@Nonnull Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ComprobantesRecibidos> cq = cb.createQuery(ComprobantesRecibidos.class);
        Root<ComprobantesRecibidos> ComprobantesRecibidos = cq.from(ComprobantesRecibidos.class);
        Join<ComprobantesRecibidos, Encabezado> encabezado = ComprobantesRecibidos.join("encabezado");

        Predicate datePredicate = cb.greaterThan(encabezado.get("fechaEmision"), fecha);
        cq.where(datePredicate);

        TypedQuery<ComprobantesRecibidos> query = em.createQuery(cq);
        return query.getResultList();
    }

   @Nullable
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
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing pendientes: " + e.getMessage() + " | source=ComprobantesRecibidosService.listPendientes() | despues=" + e.getMessage());
            return null;
        }
    }

    @Nonnull
    public List<ComprobantesRecibidos> listVencidas() {
        try {
            // Query unpaid invoices only - avoid JOIN FETCH on collection (lineasDetalle) 
            // as it causes duplicate rows and Hibernate DISTINCT issues
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles " +
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
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing vencidas: " + e.getMessage() + " | source=ComprobantesRecibidosService.listVencidas() | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    @Nullable
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
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error finding pendientes Mensaje Receptor: " + e.getMessage() + " | source=ComprobantesRecibidosService.findPendientesMensajeReceptor() | despues=" + e.getMessage());
            return null;
        }
    }

    @Nonnull
    public List<ComprobantesRecibidos> findProximosVencerMensajeReceptor(int dias) {
        try {
            LocalDate limite = LocalDate.now().plusDays(dias);
            List<ComprobantesRecibidos> todos = findPendientesMensajeReceptor();
            if (todos == null) return java.util.Collections.emptyList();
            
            return todos.stream()
                .filter(f -> f.getMensajeReceptorLimite() != null && !f.getMensajeReceptorLimite().isAfter(limite))
                .collect(java.util.stream.Collectors.toList());
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error finding proximos vencer Mensaje Receptor: " + e.getMessage() + " | source=ComprobantesRecibidosService.findProximosVencerMensajeReceptor() | despues=" + e.getMessage());
            return null;
        }
}



}
