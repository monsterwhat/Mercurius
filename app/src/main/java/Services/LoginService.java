package Services;

import Models.Usuarios;
import org.jboss.logging.Logger;
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
public class LoginService extends GService<Usuarios> {

    private static final Logger LOG = Logger.getLogger(LoginService.class);

    // Secure BCrypt password hashing with automatic salt generation
    private static final int BCRYPT_COST = 12;

    @Override
    protected Class<Usuarios> getEntityClass() {
        return Usuarios.class;
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
                        LOG.warn("Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage() + " | source=" + "LoginService.countActivos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.warn("Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage() + " | source=" + "LoginService.countActivos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public boolean verifyPassword(String password, String hashedPassword) {
        try {
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), hashedPassword);
            return result.verified;
        } catch (RuntimeException e) {
                        LOG.warn("Password verification error: " + e.getLocalizedMessage() + " | source=" + "LoginService.verifyPassword()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }

    private String hashPassword(String password) {
        try {
            return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
        } catch (RuntimeException e) {
                        LOG.warn("Password hashing error: " + e.getLocalizedMessage() + " | source=" + "LoginService.hashPassword()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    public Usuarios authenticate(String username, String password) {
        try {
            Usuarios user = getSession(username);
            if (user != null && verifyPassword(password, user.getPassword())) {
                return user;
            }
            return null;
        } catch (RuntimeException e) {
                        LOG.warn("Authentication error: " + e.getLocalizedMessage() + " | source=" + "LoginService.authenticate()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public Usuarios findByUsername(String username) {
        try {
            TypedQuery<Usuarios> query = em.createQuery("SELECT u FROM Usuarios u WHERE u.username = :username AND u.status = true", Usuarios.class);
            query.setParameter("username", username);

            List<Usuarios> resultList = query.getResultList();

            if (!resultList.isEmpty()) {
                return resultList.get(0);
            } else {
                return null;
            }
        } catch (IllegalStateException | SecurityException e) {
                        LOG.warn("Error in findByUsername: " + (e != null ? e.getMessage() : "null") + " | source=" + "LoginService.findByUsername()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e != null ? e.getMessage() : null));
            return null;
        }
    }

    public Usuarios getSession(String username) {
        return findByUsername(username);
    }

    @Transactional
    public void InsertAdmin() {
        try {

            String username = "Admin";
            TypedQuery<Usuarios> query = em.createQuery("SELECT u FROM Usuarios u WHERE u.username = :username", Usuarios.class);
            query.setParameter("username", username);
            Usuarios existingUser = null;

            try {
                existingUser = query.getSingleResult();

                // Check if password is plain text (length < 50 characters)
                if (existingUser.getPassword() != null && existingUser.getPassword().length() < 50) {
                                        LOG.info("=== UPDATING ADMIN PASSWORD FROM PLAIN TEXT TO BCRYPT ===" + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                    String defaultPassword = "Mercurius@2024!";
                    existingUser.setPassword(hashPassword(defaultPassword));
                    em.merge(existingUser);
                                        LOG.info("Username: " + username + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                                        LOG.info("============================================================" + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                } else {
                                        LOG.info("Admin user already exists with proper BCrypt password" + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                }

            } catch (NoResultException e) {
                Usuarios user = new Usuarios();
                user.setUsername(username);
                // Hash the admin password with a secure default
                String defaultPassword = "Mercurius@2024!";
                user.setPassword(hashPassword(defaultPassword));
                user.setGroupName("admin");
                user.setStatus(true);

                em.persist(user);
                                LOG.info("=== ADMIN USER CREATED ===" + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                                LOG.info("Username: " + username + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                                LOG.info("=============================" + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (RuntimeException e) {
                        LOG.warn("Error in InsertAdmin! Error: " + e.getMessage() + " | source=" + "LoginService.InsertAdmin()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void create(Usuarios entity) {
        try {
            // Hash the password before storing
            var unHashedPassword = entity.getPassword();
            entity.setPassword(hashPassword(unHashedPassword));
            em.persist(entity);
            em.flush();
        } catch (RuntimeException e) {
                        LOG.warn("Error creating user: " + e.getMessage() + " | source=" + "LoginService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void delete(Usuarios entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "LoginService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "LoginService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    public void softDelete(Usuarios entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                entity.setStatus(false);
                em.merge(entity);
                em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "LoginService.softDelete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.warn("Error soft deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "LoginService.softDelete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    public boolean usernameExists(String name) {
        try {
            TypedQuery<Usuarios> query = em.createQuery("SELECT u FROM Usuarios u WHERE u.username = :username", Usuarios.class);
            query.setParameter("username", name);
            List<Usuarios> existingUser = query.getResultList();

            return !existingUser.isEmpty();

        } catch (PersistenceException e) {
                        LOG.warn("Error:" + e.getLocalizedMessage() + " | source=" + "LoginService.usernameExists()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return true;
        }
    }

    public void updateUsername(Usuarios currentUser, String newUsername) {
        try {
            if (em.contains(currentUser)) {
                currentUser.setUsername(newUsername);
                em.merge(currentUser);
            } else {
                Usuarios foundUser = em.find(getEntityClass(), currentUser.getId());

                if (foundUser != null) {
                    foundUser.setUsername(newUsername);
                    em.merge(foundUser);
                }
            }

        } catch (PersistenceException e) {
                        LOG.warn("Error: " + e.getLocalizedMessage() + " | source=" + "LoginService.method()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    public void updateEmail(Usuarios currentUser, String newEmail) {
        try {
            if (em.contains(currentUser)) {
                currentUser.setEmail(newEmail);
                em.merge(currentUser);
            } else {
                Usuarios foundUser = em.find(getEntityClass(), currentUser.getId());

                if (foundUser != null) {
                    foundUser.setEmail(newEmail);
                    em.merge(foundUser);
                }
            }
        } catch (PersistenceException e) {
                        LOG.warn("Error: " + e.getLocalizedMessage() + " | source=" + "LoginService.method()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    public void updatePassword(Usuarios currentUser, String newPassword) {
        try {
            if (em.contains(currentUser)) {
                currentUser.setPassword(hashPassword(newPassword));
                em.merge(currentUser);
            } else {
                Usuarios foundUser = em.find(getEntityClass(), currentUser.getId());

                if (foundUser != null) {
                    foundUser.setPassword(hashPassword(newPassword));
                    em.merge(foundUser);
                }
            }
        } catch (RuntimeException e) {
                        LOG.warn("Error: " + e.getLocalizedMessage() + " | source=" + "LoginService.method()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

}
