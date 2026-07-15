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
            alertasService.registrarAlerta("Info", "Successfully created LineaDetalle with ID: " + entity.getId(), null, 0, "LineaDetalleService.create()", null, null);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "LineaDetalleService.create()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "LineaDetalleService.delete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "LineaDetalleService.delete()", null, e.getMessage());
        }
    }

    @Transactional
    @Override
    public void update(@Nonnull LineaDetalle entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "LineaDetalleService.update()", null, e.getMessage());
        }
    }

    @Override
    @Nullable
    public List<LineaDetalle> listAll() {
        try {
            TypedQuery<LineaDetalle> query = em.createQuery("SELECT d FROM LineaDetalle d", LineaDetalle.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "LineaDetalleService.listAll()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error listing LineaDetalle by DetalleServicio ID: " + e.getMessage(), null, 0, "LineaDetalleService.listAllWhereID()", null, e.getMessage());
            return null;
        }
    }


    
    @Nullable
    public LineaDetalle findById(@Nonnull Long id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding entity by ID: " + e.getMessage(), null, 0, "LineaDetalleService.findById()", null, e.getMessage());
            return null;
        }
    }
    
    @Nullable
    public List<LineaDetalle> ListAllEnabled() {
        try {
            TypedQuery<LineaDetalle> query = em.createQuery("SELECT a FROM LineaDetalle a WHERE a.status = true", LineaDetalle.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "LineaDetalleService.ListAllEnabled()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error creating and returning entity: " + e.getMessage(), null, 0, "LineaDetalleService.createAndReturnEntity()", null, e.getMessage());
            return null;
        }
    }


}
