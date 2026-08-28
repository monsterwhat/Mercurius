package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.List;
import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 *
 * @author Al
 */
@Named
@ApplicationScoped
public class UserService extends GService<Users> {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(UserService.class.getName());

    private static final int BCRYPT_COST = 12;

    @Override
    protected Class<Users> getEntityClass() {
        return Users.class;
    }

    @Override
    @Transactional
    public void create(@Nonnull Users entity) {
        try {
            // Hash the password before storing
            if (entity.getPassword() != null && entity.getPassword().length() < 50) {
                entity.setPassword(hashPassword(entity.getPassword()));
            }
            em.persist(entity);
            em.flush();
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating user: " + e.getMessage() + " | source=" + "UserService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    private String hashPassword(String password) {
        try {
            return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Password hashing error: " + e.getLocalizedMessage() + " | source=" + "UserService.hashPassword()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    @Override
    public void update(@Nonnull Users entity) {
        try {
            // Get the existing user to check if password changed
            Users existingUser = em.find(Users.class, entity.getId());
            if (existingUser != null && entity.getPassword() != null) {
                // If password is plain text (length < 50), hash it
                if (entity.getPassword().length() < 50 || !entity.getPassword().startsWith("$2")) {
                    entity.setPassword(hashPassword(entity.getPassword()));
                } else {
                    // Password is already hashed, keep it as-is
                    entity.setPassword(entity.getPassword());
                }
            }
            em.merge(entity);
            em.flush();
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating user: " + e.getMessage() + " | source=" + "UserService.update()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void delete(@Nonnull Users entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "UserService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "UserService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage() + " | source=" + "UserService.count()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public boolean usernameExists(@Nonnull String username) {
        try {
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username", Users.class);
            query.setParameter("username", username);
            List<Users> existingUser = query.getResultList();

            return !existingUser.isEmpty();

        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error checking username: " + e.getLocalizedMessage() + " | source=" + "UserService.usernameExists()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return true;
        }
    }

    @Nullable
    public Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error counting active users: " + e.getLocalizedMessage() + " | source=" + "UserService.countActivos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Nullable
    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error counting inactive users: " + e.getLocalizedMessage() + " | source=" + "UserService.countInactivos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

}
