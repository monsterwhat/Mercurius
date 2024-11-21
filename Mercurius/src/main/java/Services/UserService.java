package Services;

import Models.Users;
import jakarta.annotation.PostConstruct; 
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash; 
import java.util.List;

/**
 *
 * @author Al
 */

@Named 
@Stateless
public class UserService extends GService<Users>{
        
    @Inject Pbkdf2PasswordHash passwordHasher;

    @Override
    protected Class<Users> getEntityClass(){
        return Users.class;
    }
     
    @PostConstruct
    public void init() {
        if(this.count() == 0){
            InsertAdmin();
        }
    }
      
    public void InsertAdmin(){  
        try {
            
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
                    System.out.println("Default Admin Saved!");
                } else {
                    System.out.println("User already exists");
                }
            
        } catch (IllegalStateException | SecurityException e) {
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
    
    @Override
    public Long count() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            System.out.println("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage());
            return null;
        }
    }

    
}
