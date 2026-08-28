package Services.Facturas;

import Models.Detalles.DetalleServicio;
import Services.GService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.List;

@Named 
@ApplicationScoped
public class DetalleServicioService extends GService<DetalleServicio> {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DetalleServicioService.class.getName());
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<DetalleServicio> getEntityClass() {
        return DetalleServicio.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull DetalleServicio entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=" + "DetalleServicioService.create()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    @Transactional
    public void delete(@Nonnull DetalleServicio entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                                LOG.info("Entity not found for delete" + " | source=" + "DetalleServicioService.delete()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "DetalleServicioService.delete()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    @Transactional
    public void update(@Nonnull DetalleServicio entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating entity: " + e.getMessage() + " | source=" + "DetalleServicioService.update()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
    @Override
    @Nullable
    public List<DetalleServicio> listAll() {
        try {
            TypedQuery<DetalleServicio> query = em.createQuery(
                "SELECT d FROM DetalleServicio d " +
                "JOIN FETCH d.lineasDetalle l " +
                "JOIN FETCH l.codigosComerciales " +
                "JOIN FETCH l.descuentos " +
                "JOIN FETCH l.impuestos", 
                DetalleServicio.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=" + "DetalleServicioService.listAll()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
     @Nullable
     public List<DetalleServicio> ListAllEnabled() {
        try {
            TypedQuery<DetalleServicio> query = em.createQuery(
                "SELECT a FROM DetalleServicio a " +
                "JOIN FETCH a.lineasDetalle l " +
                "JOIN FETCH l.codigosComerciales " +
                "JOIN FETCH l.descuentos " +
                "JOIN FETCH l.impuestos " +
                "WHERE a.enabled = true", 
                DetalleServicio.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all enabled entities: " + e.getMessage() + " | source=" + "DetalleServicioService.ListAllEnabled()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

}
