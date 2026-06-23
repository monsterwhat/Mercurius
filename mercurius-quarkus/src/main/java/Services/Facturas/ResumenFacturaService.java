package Services.Facturas;

import Models.Resumen.ResumenFactura;
import Services.GService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
public class ResumenFacturaService extends GService<ResumenFactura>  {
    @PersistenceContext EntityManager em;

    @Override
    protected Class<ResumenFactura> getEntityClass() {
        return ResumenFactura.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(ResumenFactura entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ResumenFacturaService.create()", null, e.getMessage());
        }
    }

    @Override
    public void delete(ResumenFactura entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "ResumenFacturaService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ResumenFacturaService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(ResumenFactura entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ResumenFacturaService.update()", null, e.getMessage());
        }
    }

    @Override
    public List<ResumenFactura> listAll() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT d FROM ResumenFactura d", ResumenFactura.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ResumenFacturaService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public ResumenFactura findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding entity by ID: " + e.getMessage(), null, 0, "ResumenFacturaService.findById()", null, e.getMessage());
            return null;
        }
    }
    
    public List<ResumenFactura> ListAllEnabled() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT a FROM ResumenFactura a WHERE a.status = true", ResumenFactura.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "ResumenFacturaService.ListAllEnabled()", null, e.getMessage());
            return null;
        }
    }

}
