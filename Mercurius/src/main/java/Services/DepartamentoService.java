package Services;

import Models.Departamento;
import Models.Familia;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class DepartamentoService extends GService<Departamento> {

    @Override
    protected Class<Departamento> getEntityClass() {
        return Departamento.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Departamento entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }
    
    public boolean createIfNotExists(Familia entity) {
        try {
            String queryStr = "SELECT COUNT(d) FROM Departamento d WHERE d.nombre = :nombre";
            Long count = em.createQuery(queryStr, Long.class)
                           .setParameter("nombre", entity.getNombre())
                           .getSingleResult();

            if (count > 0) {
                return false;
            } else {
                em.persist(entity);
                return true;
            }

        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
            return false;
        }
    }
    

    @Override
    public void delete(Departamento entity) {
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
    public void update(Departamento entity) {
        try {
            if(!entity.getStatus()){
                entity.setStatus(true);
            }
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Departamento> listAll() {
        try {
            TypedQuery<Departamento> query = em.createQuery("SELECT d FROM Departamento d", Departamento.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public Departamento findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public void updateAndDisable(Departamento entity) {
        try {
            // Find the existing item by its ID
            Departamento existingItem = em.find(getEntityClass(), entity.getId());

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

    public List<Departamento> ListAllEnabled() {
        try {
            TypedQuery<Departamento> query = em.createQuery("SELECT a FROM Departamento a WHERE a.status = true", Departamento.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public void softDelete(Departamento entity) {
        try {
            // Find the item by its ID
            Departamento existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                        FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Se desactivo el departamento!", null));
                }else{
                    existingItem.setStatus(true);
                        FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Se activo el departamento!", null));
                }
                em.merge(existingItem);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error soft deleting entity: " + e.toString());
        }
    }
    
    public Departamento createIfNotExist(Departamento departamento) {
        try {
            TypedQuery<Departamento> query = em.createQuery("SELECT e FROM Departamento e WHERE e.nombre = :nombre", Departamento.class);
            query.setParameter("nombre", departamento.getNombre());
            List<Departamento> existingEmisors = query.getResultList();

            if (existingEmisors.isEmpty()) {
                em.persist(departamento);
                return departamento;
            } else {
                return existingEmisors.get(0);
            }
        } catch (PersistenceException e) {
            // Catch the database constraint violation exception
            System.out.println("Error creating or retrieving Departamento: " + e.toString());
            // Handle the error gracefully, maybe log it or notify the user
            return null;
        }
    }

}
