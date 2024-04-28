package Services.Facturas;

import Models.Facturas.CodigoComercial;
import Services.GService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class CodigoComercialService extends GService<CodigoComercial> {

    @PersistenceContext EntityManager em;

    @Override
    protected Class<CodigoComercial> getEntityClass() {
        return CodigoComercial.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(CodigoComercial entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(CodigoComercial entity) {
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
    public void update(CodigoComercial entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<CodigoComercial> listAll() {
        try {
            TypedQuery<CodigoComercial> query = em.createQuery("SELECT d FROM CodigoComercial d", CodigoComercial.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public CodigoComercial findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public List<CodigoComercial> ListAllEnabled() {
        try {
            TypedQuery<CodigoComercial> query = em.createQuery("SELECT a FROM CodigoComercial a WHERE a.status = true", CodigoComercial.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

}
