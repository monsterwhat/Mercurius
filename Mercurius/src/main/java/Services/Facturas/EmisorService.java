
package Services.Facturas;

import Models.Comprobantes.Encabezado.Emisor;
import Services.GService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class EmisorService extends GService<Emisor> {
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<Emisor> getEntityClass() {
        return Emisor.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Emisor entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Emisor entity) {
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
    public void update(Emisor entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Emisor> listAll() {
        try {
            TypedQuery<Emisor> query = em.createQuery("SELECT d FROM Emisor d", Emisor.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public Emisor findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public List<Emisor> ListAllEnabled() {
        try {
            TypedQuery<Emisor> query = em.createQuery("SELECT a FROM Emisor", Emisor.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public Emisor createIfNotExist(Emisor emisor) {
        try {
            // Correct query to join identificacion and check the identificacion.numero field
            TypedQuery<Emisor> query = em.createQuery(
                "SELECT e FROM Emisor e JOIN e.identificacion i WHERE i.numero = :identificacionNumero", Emisor.class);
            query.setParameter("identificacionNumero", emisor.getIdentificacion().getNumero());
            List<Emisor> existingEmisors = query.getResultList();

            // If no Emisor with the same identification number exists, create a new one
            if (existingEmisors.isEmpty()) {
                em.persist(emisor);
                return emisor;
            } else {
                // If an Emisor with the same identification number already exists, return it
                return existingEmisors.get(0);
            }
        } catch (PersistenceException e) {
            // Catch the database constraint violation exception
            System.out.println("Error creating or retrieving Emisor: " + e.toString());
            // Handle the error gracefully, maybe log it or notify the user
            return null;
        }
    }



}
