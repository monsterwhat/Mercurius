package Services;

import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;
import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 *
 * @author Al
 */
@Named
@ApplicationScoped
public class UserService extends GService<Users> {

    private static final int BCRYPT_COST = 12;

    @Override
    protected Class<Users> getEntityClass() {
        return Users.class;
    }

    @Override
    public void create(Users entity) {
        try {
            // Hash the password before storing
            if (entity.getPassword() != null && entity.getPassword().length() < 50) {
                entity.setPassword(hashPassword(entity.getPassword()));
            }
            em.persist(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating user: " + e.getMessage(), null, 0, "UserService.create()", null, e.getMessage());
        }
    }

    private String hashPassword(String password) {
        try {
            return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Password hashing error: " + e.getLocalizedMessage(), null, 0, "UserService.hashPassword()", null, e.getMessage());
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    @Override
    public void update(Users entity) {
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating user: " + e.getMessage(), null, 0, "UserService.update()", null, e.getMessage());
        }
    }

    @Override
    public void delete(Users entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "UserService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "UserService.delete()", null, e.getMessage());
        }
    }

    @Override
    public Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage(), null, 0, "UserService.count()", null, e.getMessage());
            return null;
        }
    }

    public boolean usernameExists(String username) {
        try {
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username", Users.class);
            query.setParameter("username", username);
            List<Users> existingUser = query.getResultList();

            return !existingUser.isEmpty();

        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error checking username: " + e.getLocalizedMessage(), null, 0, "UserService.usernameExists()", null, e.getMessage());
            return true;
        }
    }

    public Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting active users: " + e.getLocalizedMessage(), null, 0, "UserService.countActivos()", null, e.getMessage());
            return null;
        }
    }

    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting inactive users: " + e.getLocalizedMessage(), null, 0, "UserService.countInactivos()", null, e.getMessage());
            return null;
        }
    }

}
