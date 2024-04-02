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
import jakarta.transaction.Transactional;
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
        InsertAdmin();
    }
    
    public boolean verifyPassword(char[] password, String hashedPassword){
        return passwordHasher.verify(password, hashedPassword);
    }
        
    public Users getSession(String username) {
        try {
            TypedQuery<Users> query = em.createQuery("SELECT u FROM Users u WHERE u.username = :username", Users.class);
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
    
    @Transactional
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
                
                em.persist(user);
                this.userTransaction.commit();
                System.out.println("Default Admin Saved!");
            } else {
                System.out.println("User already exists");
            }
        } catch (Exception e) {
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
    
    
}
