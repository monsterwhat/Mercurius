package Services;

import Models.Clients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
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
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ClientService.create()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "ClientService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ClientService.delete()", null, e.getMessage());
        }
    }
    
    public List<Clients> searchByName(String name) {
        try {
            TypedQuery<Clients> query = em.createQuery(
                "SELECT c FROM Clients c WHERE LOWER(c.name) LIKE LOWER(:name)", Clients.class);
            query.setParameter("name", "%" + name + "%");
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error searching clients by name: " + e.getMessage(), null, 0, "ClientService.searchByName()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error getting client by username: " + e.getMessage(), null, 0, "ClientService.checkClientName()", null, e.getMessage());
            return true;
        }
    }
}
