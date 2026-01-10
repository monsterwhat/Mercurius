package Services;

import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
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
        } catch (Exception e) {
            System.out.println("Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage());
            return null;
        }
    }

    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            System.out.println("Error counting " + getEntityClass().getSimpleName() + " : " + e.getLocalizedMessage());
            return null;
        }
    }

    public boolean verifyPassword(String password, String hashedPassword) {
        try {
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), hashedPassword);
            return result.verified;
        } catch (Exception e) {
            System.out.println("Password verification error: " + e.getLocalizedMessage());
            return false;
        }
    }

    private String hashPassword(String password) {
        try {
            return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
        } catch (Exception e) {
            System.out.println("Password hashing error: " + e.getLocalizedMessage());
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
        } catch (Exception e) {
            System.out.println("Authentication error: " + e.getLocalizedMessage());
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
            System.out.println("Error: ");
            System.out.println(e);
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
                    System.out.println("=== UPDATING ADMIN PASSWORD FROM PLAIN TEXT TO BCRYPT ===");
                    String defaultPassword = "Mercurius@2024!";
                    existingUser.setPassword(hashPassword(defaultPassword));
                    em.merge(existingUser);
                    System.out.println("Username: " + username);
                    System.out.println("============================================================");
                } else {
                    System.out.println("Admin user already exists with proper BCrypt password");
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
                System.out.println("=== ADMIN USER CREATED ===");
                System.out.println("Username: " + username);
                System.out.println("=============================");
            }
        } catch (Exception e) {
            System.out.println("Error in InsertAdmin! Error: " + e.toString());
        }
    }

    @Override
    public void create(Users entity) {
        try {
            // Hash the password before storing
            var unHashedPassword = entity.getPassword();
            entity.setPassword(hashPassword(unHashedPassword));
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating user: " + e.toString());
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
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
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
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error soft deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }

    public boolean usernameExists(String name) {
        try {
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username", Users.class);
            query.setParameter("username", name);
            List<Users> existingUser = query.getResultList();

            return !existingUser.isEmpty();

        } catch (Exception e) {
            System.out.println("Error:" + e.getLocalizedMessage());
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

        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
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
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
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
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
        }
    }

}
