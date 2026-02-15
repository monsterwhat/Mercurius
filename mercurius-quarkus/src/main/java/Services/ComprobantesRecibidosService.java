package Services;

import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
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
            em.flush();
            em.refresh(entity);
            System.out.println("Successfully created ComprobantesRecibidos with ID: " + entity.getId());
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
            throw new RuntimeException("Failed to create ComprobantesRecibidos", e);
        }
    }
    
    // Method to create ComprobantesRecibidos with pre-persisted related entities
    public void createWithRelatedEntities(ComprobantesRecibidos entity, Encabezado encabezado, ResumenFactura resumenFactura) {
        try {
            em.persist(encabezado);
            em.persist(resumenFactura);
            em.flush(); // Generate IDs for related entities
            
            em.persist(entity);
            em.flush();
            em.refresh(entity);
            System.out.println("Successfully created ComprobantesRecibidos with ID: " + entity.getId());
        } catch (Exception e) {
            System.out.println("Error creating entity with related entities: " + e.toString());
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
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen",
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
            System.out.println("Error finding ComprobantesRecibidos by ID with details: " + e.toString());
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
            System.out.println("Error listing pendientes: " + e.toString());
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
            System.out.println("Error listing vencidas: " + e.toString());
            return null;
        }
    }




}
