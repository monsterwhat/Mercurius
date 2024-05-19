package Services;

import Models.Clients;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class ClientService extends GService<Clients> {

    @Override
    protected Class<Clients> getEntityClass() {
        return Clients.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Clients entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Clients entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCode());
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

    public Clients getClientByUsername(String username) {
        try {
            TypedQuery<Clients> query = em.createQuery("SELECT c FROM Clients c WHERE c.username = :username", Clients.class);
            query.setParameter("username", username);

            List<Clients> resultList = query.getResultList();

            if (!resultList.isEmpty()) {
                return resultList.get(0);
            } else {
                return null;
            }
        } catch (Exception e) {
            System.out.println("Error getting client by username: " + e.toString());
            return null;
        }
    }
    
    public boolean checkClientName(String username) {
        try {
            TypedQuery<Clients> query = em.createQuery("SELECT c FROM Clients c WHERE c.name = :username", Clients.class);
            query.setParameter("username", username);

            List<Clients> resultList = query.getResultList();

            return !resultList.isEmpty();
        } catch (Exception e) {
            System.out.println("Error getting client by username: " + e.toString());
            return true;
        }
    }
    
    public void updateAndDisable(Clients entity) {
        try {
            // Find the existing item by its ID
            Clients existingItem = em.find(getEntityClass(), entity.getCode());

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

    public List<Clients> ListAllEnabled() {
        try {
            TypedQuery<Clients> query = em.createQuery("SELECT a FROM Clients a WHERE a.status = true", Clients.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public void softDelete(Clients entity) {
        try {
            // Find the item by its ID
            Clients existingItem = em.find(getEntityClass(), entity.getCode());

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
