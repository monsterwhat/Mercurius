package Services.Facturas;

import Models.Encabezado.Receptor;
import Services.GService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
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
            em.merge(entity);
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
            TypedQuery<Receptor> query = em.createQuery("SELECT a FROM Receptor", Receptor.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public Receptor createIfNotExist(Receptor receptor) {
        try {
            // Check if a Receptor with the same identification number already exists
            TypedQuery<Receptor> query = em.createQuery(
                "SELECT r FROM Receptor r JOIN r.identificacion i WHERE i.numero = :identificacionNumero", 
                Receptor.class
            );
            query.setParameter("identificacionNumero", receptor.getIdentificacion().getNumero());
            List<Receptor> existingReceptors = query.getResultList();

// If no Receptor with the same identification number exists, create a new one
            if (existingReceptors.isEmpty()) {
                em.persist(receptor);
                em.flush(); // Ensure the entity gets an ID
                em.refresh(receptor); // Refresh to get the generated ID
                return receptor;
            } else {
                // If a Receptor with the same identification number already exists, return it
                return existingReceptors.get(0);
            }
        } catch (PersistenceException e) {
            // Catch the database constraint violation exception
            System.out.println("Error creating or retrieving Receptor: " + e.toString());
            // Handle the error gracefully, maybe log it or notify the user
            return null;
        }
    }



}
