package Services;

import Models.Users;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
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
public class LoginService extends GService<Users> {

    // Secure BCrypt password hashing with automatic salt generation
    private static final int BCRYPT_COST = 12;

    @Override
    protected Class<Users> getEntityClass() {
        return Users.class;
    }

    @PostConstruct
    @Transactional
    public void init() {
        InsertAdmin();
    }

    public Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage(), null, 0, "LoginService.countActivos()", null, e.getMessage());
            return null;
        }
    }

    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage(), null, 0, "LoginService.countActivos()", null, e.getMessage());
            return null;
        }
    }

    public boolean verifyPassword(String password, String hashedPassword) {
        try {
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), hashedPassword);
            return result.verified;
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Password verification error: " + e.getLocalizedMessage(), null, 0, "LoginService.verifyPassword()", null, e.getMessage());
            return false;
        }
    }

    private String hashPassword(String password) {
        try {
            return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Password hashing error: " + e.getLocalizedMessage(), null, 0, "LoginService.hashPassword()", null, e.getMessage());
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    public Users authenticate(String username, String password) {
        try {
            Users user = getSession(username);
            if (user != null && verifyPassword(password, user.getPassword())) {
                return user;
            }
            return null;
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Authentication error: " + e.getLocalizedMessage(), null, 0, "LoginService.authenticate()", null, e.getMessage());
            return null;
        }
    }

    public Users findByUsername(String username) {
        try {
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username AND u.status = true", Users.class);
            query.setParameter("username", username);

            List<Users> resultList = query.getResultList();

            if (!resultList.isEmpty()) {
                return resultList.get(0);
            } else {
                return null;
            }
        } catch (IllegalStateException | SecurityException e) {
            alertasService.registrarAlerta("Error", "Error in findByUsername: " + (e != null ? e.getMessage() : "null"), null, 0, "LoginService.findByUsername()", null, e != null ? e.getMessage() : null);
            return null;
        }
    }

    public Users getSession(String username) {
        return findByUsername(username);
    }

    @Transactional
    public void InsertAdmin() {
        try {

            String username = "Admin";
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username", Users.class);
            query.setParameter("username", username);
            Users existingUser = null;

            try {
                existingUser = query.getSingleResult();

                // Check if password is plain text (length < 50 characters)
                if (existingUser.getPassword() != null && existingUser.getPassword().length() < 50) {
                    alertasService.registrarAlerta("Info", "=== UPDATING ADMIN PASSWORD FROM PLAIN TEXT TO BCRYPT ===", null, 0, "LoginService.InsertAdmin()", null, null);
                    String defaultPassword = "Mercurius@2024!";
                    existingUser.setPassword(hashPassword(defaultPassword));
                    em.merge(existingUser);
                    alertasService.registrarAlerta("Info", "Username: " + username, null, 0, "LoginService.InsertAdmin()", null, null);
                    alertasService.registrarAlerta("Info", "============================================================", null, 0, "LoginService.InsertAdmin()", null, null);
                } else {
                    alertasService.registrarAlerta("Info", "Admin user already exists with proper BCrypt password", null, 0, "LoginService.InsertAdmin()", null, null);
                }

            } catch (NoResultException e) {
                Users user = new Users();
                user.setUsername(username);
                // Hash the admin password with a secure default
                String defaultPassword = "Mercurius@2024!";
                user.setPassword(hashPassword(defaultPassword));
                user.setGroupName("admin");
                user.setStatus(true);

                em.persist(user);
                alertasService.registrarAlerta("Info", "=== ADMIN USER CREATED ===", null, 0, "LoginService.InsertAdmin()", null, null);
                alertasService.registrarAlerta("Info", "Username: " + username, null, 0, "LoginService.InsertAdmin()", null, null);
                alertasService.registrarAlerta("Info", "=============================", null, 0, "LoginService.InsertAdmin()", null, null);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error in InsertAdmin! Error: " + e.getMessage(), null, 0, "LoginService.InsertAdmin()", null, e.getMessage());
        }
    }

    @Override
    public void create(Users entity) {
        try {
            // Hash the password before storing
            var unHashedPassword = entity.getPassword();
            entity.setPassword(hashPassword(unHashedPassword));
            em.persist(entity);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error creating user: " + e.getMessage(), null, 0, "LoginService.create()", null, e.getMessage());
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
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "LoginService.delete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "LoginService.delete()", null, e.getMessage());
        }
    }

    public void softDelete(Users entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                entity.setStatus(false);
                em.merge(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "LoginService.softDelete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error soft deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "LoginService.softDelete()", null, e.getMessage());
        }
    }

    public boolean usernameExists(String name) {
        try {
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username", Users.class);
            query.setParameter("username", name);
            List<Users> existingUser = query.getResultList();

            return !existingUser.isEmpty();

        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error:" + e.getLocalizedMessage(), null, 0, "LoginService.usernameExists()", null, e.getMessage());
            return true;
        }
    }

    public void updateUsername(Users currentUser, String newUsername) {
        try {
            if (em.contains(currentUser)) {
                currentUser.setUsername(newUsername);
                em.merge(currentUser);
            } else {
                Users foundUser = em.find(getEntityClass(), currentUser.getId());

                if (foundUser != null) {
                    foundUser.setUsername(newUsername);
                    em.merge(foundUser);
                }
            }

        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "LoginService.method()", null, e.getMessage());
        }
    }

    public void updateEmail(Users currentUser, String newEmail) {
        try {
            if (em.contains(currentUser)) {
                currentUser.setEmail(newEmail);
                em.merge(currentUser);
            } else {
                Users foundUser = em.find(getEntityClass(), currentUser.getId());

                if (foundUser != null) {
                    foundUser.setEmail(newEmail);
                    em.merge(foundUser);
                }
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "LoginService.method()", null, e.getMessage());
        }
    }

    public void updatePassword(Users currentUser, String newPassword) {
        try {
            if (em.contains(currentUser)) {
                currentUser.setPassword(hashPassword(newPassword));
                em.merge(currentUser);
            } else {
                Users foundUser = em.find(getEntityClass(), currentUser.getId());

                if (foundUser != null) {
                    foundUser.setPassword(hashPassword(newPassword));
                    em.merge(foundUser);
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "LoginService.method()", null, e.getMessage());
        }
    }

}
