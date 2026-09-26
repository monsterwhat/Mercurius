package Services;

import Models.Clientes;
import org.jboss.logging.Logger;
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
public class ClientService extends GService<Clientes> {

    private static final Logger LOG = Logger.getLogger(ClientService.class);

    @Override
    protected @Nonnull Class<Clientes> getEntityClass() {
        return Clientes.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull Clientes entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error creating entity: " + e.getMessage() + " | source=" + "ClientService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void delete(@Nonnull Clientes entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCode());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "ClientService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "ClientService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
    public @Nullable List<Clientes> searchByName(@Nonnull String name) {
        try {
            TypedQuery<Clientes> query = em.createQuery(
                "SELECT c FROM Clientes c WHERE LOWER(c.name) LIKE LOWER(:name)", Clientes.class);
            query.setParameter("name", "%" + name + "%");
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error searching clients by name: " + e.getMessage() + " | source=" + "ClientService.searchByName()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public boolean checkClientName(@Nonnull String username) {
        try {
            TypedQuery<Clientes> query = em.createQuery(
                "SELECT c FROM Clientes c WHERE LOWER(c.name) = LOWER(:username)", Clientes.class);
            query.setParameter("username", username);

            List<Clientes> resultList = query.getResultList();

            return !resultList.isEmpty();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error getting client by username: " + e.getMessage() + " | source=" + "ClientService.checkClientName()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                "SELECT COUNT(c) FROM Clientes c WHERE c.idNumber = :idNumber", Long.class);
            query.setParameter("idNumber", idNumber);
            return query.getSingleResult() > 0;
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error checking client by ID number: " + e.getMessage() + " | source=" + "ClientService.checkClientByIdNumber()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }
}
