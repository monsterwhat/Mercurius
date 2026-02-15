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
            System.out.println("Error creating entity: " + e.toString());
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
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }

    @Override
    public void update(ResumenFactura entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<ResumenFactura> listAll() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT d FROM ResumenFactura d", ResumenFactura.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public ResumenFactura findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public List<ResumenFactura> ListAllEnabled() {
        try {
            TypedQuery<ResumenFactura> query = em.createQuery("SELECT a FROM ResumenFactura a WHERE a.status = true", ResumenFactura.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

}
