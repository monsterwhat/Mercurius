package Services;

import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;
import java.util.List;

/**
 *
 * @author Al
 */

@Named
public class LoginService extends GService<Users>{
        
    @Inject Pbkdf2PasswordHash passwordHasher;
    @Resource UserTransaction userTransaction;
    
    @Override
    protected Class<Users> getEntityClass(){
        return Users.class;
    }
     
    @PostConstruct
    public void init() {
        if(count() <= 0){
            InsertAdmin();
        }
    }
    
    public boolean verifyPassword(char[] password, String hashedPassword){
        return passwordHasher.verify(password, hashedPassword);
    }
        
    public Users getSession(String username) {
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
    
    public void InsertAdmin(){  
        try {
            this.userTransaction.begin();
            String username = "Admin";
            
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username", Users.class);
            query.setParameter("username", username);
            List<Users> existingUsers = query.getResultList();

            if (existingUsers.isEmpty()) {
                Users user = new Users();
                user.setUsername(username);
                user.setPassword(passwordHasher.generate("password123".toCharArray()));
                user.setGroupName("admin");
                user.setStatus(true);
                
                em.persist(user);
                this.userTransaction.commit();
                System.out.println("Default Admin Saved!");
            }
        } catch (HeuristicMixedException | HeuristicRollbackException | NotSupportedException | RollbackException | SystemException | IllegalStateException | SecurityException e) {
            System.out.println("Error in InsertAdmin! Error: " + e.toString());
        }
    }
    
    @Override
    public void create(Users entity) {
        try {
            var unHashedPassword = entity.getPassword();
            var HashedPassword = passwordHasher.generate(unHashedPassword.toCharArray());
            entity.setPassword(HashedPassword);
            em.persist(entity);
        } catch (Exception e) {
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
            System.out.println("Error deleting "+ getEntityClass().getSimpleName() +" : " + e.toString());
        }
    }
    
    public List<Users> listAllEnabledUsers() {
        try {
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.status = true", Users.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled users: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging purposes
            return null;
        }
    }
    
    public boolean usernameExists(String name){
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

    
    public void updateUsername(Users currentUser, String newUsername){
        try {
            if (em.contains(currentUser)) {
                currentUser.setUsername(newUsername);
                em.merge(currentUser);
            }else{
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
    
    public void updateEmail(Users currentUser, String newEmail){
        try {
            if (em.contains(currentUser)) {
                currentUser.setEmail(newEmail);
                em.merge(currentUser);
            }else{
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
    
    public void updatePassword(Users currentUser, String newPassword){
        try {
            if (em.contains(currentUser)) {
                var HashedPassword = passwordHasher.generate(newPassword.toCharArray());
                    currentUser.setPassword(HashedPassword);
                    em.merge(currentUser);
            } else {
                Users foundUser = em.find(getEntityClass(), currentUser.getId());
                
                if (foundUser != null) {
                    var HashedPassword = passwordHasher.generate(newPassword.toCharArray());
                    foundUser.setPassword(HashedPassword);
                    em.merge(foundUser);
                }
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
        }
    }
    
    
}
