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
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ResumenFacturaService.create()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "ResumenFacturaService.delete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ResumenFacturaService.delete()", null, e.getMessage());
        }
    }

    @Transactional
    @Override
    public void update(@Nonnull ResumenFactura entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ResumenFacturaService.update()", null, e.getMessage());
        }
    }

    @Override
    public @Nullable List<ResumenFactura> listAll() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT d FROM ResumenFactura d", ResumenFactura.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ResumenFacturaService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public @Nullable ResumenFactura findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding entity by ID: " + e.getMessage(), null, 0, "ResumenFacturaService.findById()", null, e.getMessage());
            return null;
        }
    }
    
    public @Nullable List<ResumenFactura> ListAllEnabled() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT a FROM ResumenFactura a WHERE a.status = true", ResumenFactura.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "ResumenFacturaService.ListAllEnabled()", null, e.getMessage());
            return null;
        }
    }

}
