package Services.Facturas;

import Models.Comprobantes.Detalles.DetalleServicio;
import Services.GService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
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
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
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
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }

    @Override
    public void update(DetalleServicio entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
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
            System.out.println("Error listing all entities: " + e.toString());
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
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

}
