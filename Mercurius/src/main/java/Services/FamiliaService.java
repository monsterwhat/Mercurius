package Services;

import Models.Familia;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class FamiliaService extends GService<Familia> {

    @Override
    protected Class<Familia> getEntityClass() {
        return Familia.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Familia entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Familia entity) {
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
    public void update(Familia entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Familia> listAll() {
        try {
            TypedQuery<Familia> query = em.createQuery("SELECT f FROM Familia f", Familia.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }

    public Familia findById(Integer id) {
    try {
            return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public void updateAndDisable(Familia entity) {
        try {
            // Find the existing item by its ID
            Familia existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Disable the existing item
                existingItem.setStatus(false);
                em.merge(existingItem);

                // Create a new item with the updated information
                em.persist(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    public List<Familia> ListAllEnabled() {
        try {
            TypedQuery<Familia> query = em.createQuery("SELECT a FROM Familia a WHERE a.status = true", Familia.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public void softDelete(Familia entity) {
        try {
            // Find the item by its ID
            Familia existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error soft deleting entity: " + e.toString());
        }
    }
}
