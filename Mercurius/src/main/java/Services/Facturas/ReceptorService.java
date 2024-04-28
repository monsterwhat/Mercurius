package Services.Facturas;

import Models.Facturas.Receptor;
import Services.GService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
@Named
public class ReceptorService extends GService<Receptor> {

    @PersistenceContext EntityManager em;

    @Override
    protected Class<Receptor> getEntityClass() {
        return Receptor.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Receptor entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Receptor entity) {
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
    public void update(Receptor entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Receptor> listAll() {
        try {
            TypedQuery<Receptor> query = em.createQuery("SELECT d FROM Receptor d", Receptor.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public Receptor findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public List<Receptor> ListAllEnabled() {
        try {
            TypedQuery<Receptor> query = em.createQuery("SELECT a FROM Receptor a WHERE a.status = true", Receptor.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

}
