package Services.Facturas;


import Models.ComprobantesV44.Detalles.LineaDetalle;
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
public class LineaDetalleService extends GService<LineaDetalle>  {
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<LineaDetalle> getEntityClass() {
        return LineaDetalle.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(LineaDetalle entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(LineaDetalle entity) {
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
    public void update(LineaDetalle entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<LineaDetalle> listAll() {
        try {
            TypedQuery<LineaDetalle> query = em.createQuery("SELECT d FROM LineaDetalle d", LineaDetalle.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public List<LineaDetalle> listAllWhereID(Long ComprobantesRecibidosId) {
        try {
            TypedQuery<LineaDetalle> query = em.createQuery(
                "SELECT ld FROM LineaDetalle ld " +
                "JOIN ld.detalleServicio ds " +
                "JOIN ds.ComprobantesRecibidos cf " +
                "WHERE cf.id = :ComprobantesRecibidos Id",
                LineaDetalle.class
            );
            query.setParameter("ComprobantesRecibidosId", ComprobantesRecibidosId);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing LineaDetalle by ComprobantesRecibidos ID: " + e.getMessage());
            return null;
        }
    }


    
    public LineaDetalle findById(Long id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public List<LineaDetalle> ListAllEnabled() {
        try {
            TypedQuery<LineaDetalle> query = em.createQuery("SELECT a FROM LineaDetalle a WHERE a.status = true", LineaDetalle.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }
    
    public LineaDetalle createAndReturnEntity(LineaDetalle entity) {
        try {
            em.persist(entity);
            return entity;
        } catch (Exception e) {
            System.out.println("Error creating and returning entity: " + e.toString());
            return null;
        }
    }


}
