package Services.Facturas;

import Models.Facturas.Factura;
import Services.GService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class FacturaService extends GService<Factura> {
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<Factura> getEntityClass() {
        return Factura.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Factura entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Factura entity) {
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
    public void update(Factura entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Factura> listAll() {
        try {
            TypedQuery<Factura> query = em.createQuery("SELECT d FROM Factura d", Factura.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public Factura findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public List<Factura> ListAllEnabled() {
        try {
            TypedQuery<Factura> query = em.createQuery("SELECT a FROM Factura a WHERE a.status = true", Factura.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

}
