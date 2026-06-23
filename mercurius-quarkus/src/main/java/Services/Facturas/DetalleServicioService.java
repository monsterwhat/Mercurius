package Services.Facturas;

import Models.Detalles.DetalleServicio;
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
public class DetalleServicioService extends GService<DetalleServicio> {
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<DetalleServicio> getEntityClass() {
        return DetalleServicio.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(DetalleServicio entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "DetalleServicioService.create()", null, e.getMessage());
        }
    }

    @Override
    public void delete(DetalleServicio entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "DetalleServicioService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "DetalleServicioService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(DetalleServicio entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "DetalleServicioService.update()", null, e.getMessage());
        }
    }
    
    @Override
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "DetalleServicioService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "DetalleServicioService.ListAllEnabled()", null, e.getMessage());
            return null;
        }
    }

}
