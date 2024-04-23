package Services;

import Models.Articulos;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class ArticulosService extends GService<Articulos> {

    @Override
    protected Class<Articulos> getEntityClass() {
        return Articulos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Articulos entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Articulos entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
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
    public void update(Articulos entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }
    
    @Override
    public List<Articulos> listAll() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a", Articulos.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public void updateAndDisable(Articulos entity) {
        try {
            // Find the existing item by its ID
            Articulos existingItem = em.find(getEntityClass(), entity.getCodigo());

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

    public List<Articulos> ListAllEnabled() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.status = true", Articulos.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public void softDelete(Articulos entity) {
        try {
            // Find the item by its ID
            Articulos existingItem = em.find(getEntityClass(), entity.getCodigo());

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
    
    public Articulos findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }

}
