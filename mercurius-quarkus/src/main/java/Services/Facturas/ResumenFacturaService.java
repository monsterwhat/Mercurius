package Services.Facturas;

import Models.Resumen.ResumenFactura;
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
public class ResumenFacturaService extends GService<ResumenFactura>  {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ResumenFacturaService.class.getName());
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    protected @Nonnull Class<ResumenFactura> getEntityClass() {
        return ResumenFactura.class;
    }

    @PostConstruct
    public void init() {
    }

    @Transactional
    @Override
    public void create(@Nonnull ResumenFactura entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=" + "ResumenFacturaService.create()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    @Override
    public void delete(@Nonnull ResumenFactura entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                                LOG.info("Entity not found for delete" + " | source=" + "ResumenFacturaService.delete()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "ResumenFacturaService.delete()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    @Override
    public void update(@Nonnull ResumenFactura entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating entity: " + e.getMessage() + " | source=" + "ResumenFacturaService.update()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public @Nullable List<ResumenFactura> listAll() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT d FROM ResumenFactura d", ResumenFactura.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=" + "ResumenFacturaService.listAll()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    public @Nullable ResumenFactura findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding entity by ID: " + e.getMessage() + " | source=" + "ResumenFacturaService.findById()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    public @Nullable List<ResumenFactura> ListAllEnabled() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT a FROM ResumenFactura a WHERE a.status = true", ResumenFactura.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all enabled entities: " + e.getMessage() + " | source=" + "ResumenFacturaService.ListAllEnabled()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

}
