package Services;

import Models.Clients;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.List;

@Named
@ApplicationScoped
public class ClientService extends GService<Clients> {

    @Override
    protected @Nonnull Class<Clients> getEntityClass() {
        return Clients.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull Clients entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ClientService.create()", null, e.getMessage());
        }
    }

    @Override
    public void delete(@Nonnull Clients entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCode());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "ClientService.delete()", null, null);
            }
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ClientService.delete()", null, e.getMessage());
        }
    }
    
    public @Nullable List<Clients> searchByName(@Nonnull String name) {
        try {
            TypedQuery<Clients> query = em.createQuery(
                "SELECT c FROM Clients c WHERE LOWER(c.name) LIKE LOWER(:name)", Clients.class);
            query.setParameter("name", "%" + name + "%");
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error searching clients by name: " + e.getMessage(), null, 0, "ClientService.searchByName()", null, e.getMessage());
            return null;
        }
    }

    public boolean checkClientName(@Nonnull String username) {
        try {
            TypedQuery<Clients> query = em.createQuery(
                "SELECT c FROM Clients c WHERE LOWER(c.name) = LOWER(:username)", Clients.class);
            query.setParameter("username", username);

            List<Clients> resultList = query.getResultList();

            return !resultList.isEmpty();
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error getting client by username: " + e.getMessage(), null, 0, "ClientService.checkClientName()", null, e.getMessage());
            return false;
        }
    }

    /**
     * Check if a client with the given tax ID (idNumber) already exists.
     * Case-sensitive match since tax IDs are canonical.
     */
    public boolean checkClientByIdNumber(@Nonnull String idNumber) {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(c) FROM Clients c WHERE c.idNumber = :idNumber", Long.class);
            query.setParameter("idNumber", idNumber);
            return query.getSingleResult() > 0;
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error checking client by ID number: " + e.getMessage(), null, 0, "ClientService.checkClientByIdNumber()", null, e.getMessage());
            return false;
        }
    }
}
