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
}
