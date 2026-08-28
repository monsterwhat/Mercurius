package Services.Facturas;


import Models.Detalles.LineaDetalle;
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
public class LineaDetalleService extends GService<LineaDetalle>  {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(LineaDetalleService.class.getName());
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<LineaDetalle> getEntityClass() {
        return LineaDetalle.class;
    }

    @PostConstruct
    public void init() {
    }

@Transactional
    @Override
    public void create(@Nonnull LineaDetalle entity) {
        try {
            em.persist(entity);
            em.flush();
            em.refresh(entity);
                        LOG.info("Successfully created LineaDetalle with ID: " + entity.getId() + " | source=" + "LineaDetalleService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=" + "LineaDetalleService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            throw new RuntimeException("Failed to create LineaDetalle", e);
        }
    }

    @Transactional
    @Override
    public void delete(@Nonnull LineaDetalle entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                                LOG.info("Entity not found for delete" + " | source=" + "LineaDetalleService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "LineaDetalleService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    @Override
    public void update(@Nonnull LineaDetalle entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating entity: " + e.getMessage() + " | source=" + "LineaDetalleService.update()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    @Nullable
    public List<LineaDetalle> listAll() {
        try {
            TypedQuery<LineaDetalle> query = em.createQuery("SELECT d FROM LineaDetalle d", LineaDetalle.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=" + "LineaDetalleService.listAll()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
@Nullable
public List<LineaDetalle> listAllWhereID(@Nonnull Long detalleServicioId) {
        try {
        TypedQuery<LineaDetalle> query = em.createQuery(
                "SELECT ld FROM LineaDetalle ld " +
                "JOIN ld.detalleServicio ds " +
                "WHERE ds.id = :detalleServicioId",
                LineaDetalle.class
            );
            query.setParameter("detalleServicioId", detalleServicioId);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing LineaDetalle by DetalleServicio ID: " + e.getMessage() + " | source=" + "LineaDetalleService.listAllWhereID()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }


    
    @Nullable
    public LineaDetalle findById(@Nonnull Long id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding entity by ID: " + e.getMessage() + " | source=" + "LineaDetalleService.findById()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    @Nullable
    public List<LineaDetalle> ListAllEnabled() {
        try {
            TypedQuery<LineaDetalle> query = em.createQuery("SELECT a FROM LineaDetalle a WHERE a.status = true", LineaDetalle.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all enabled entities: " + e.getMessage() + " | source=" + "LineaDetalleService.ListAllEnabled()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    @Transactional
    @Nullable
    public LineaDetalle createAndReturnEntity(@Nonnull LineaDetalle entity) {
        try {
            em.persist(entity);
            return entity;
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating and returning entity: " + e.getMessage() + " | source=" + "LineaDetalleService.createAndReturnEntity()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }


}
