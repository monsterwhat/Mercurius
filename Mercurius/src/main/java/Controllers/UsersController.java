package Controllers;

import Models.Users;
import Services.LoginService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;
/**
 *
 * @author Al
 */

@Data
@Named(value = "UsersController")
@SessionScoped
public class UsersController implements Serializable{
    @Inject private LoginService userService;
    @Inject ViewController viewManager;

    private List<Users> users;
    private Users selectedUser;
    private Users newUser;
    private String generatorOption;
    private String usernameFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    public UsersController() {
    }
    
    @PostConstruct
    public void init(){
        newUser = new Users();
        selectedUser = new Users();
        usersList();
        
        filterBy = new ArrayList<>();        
    }
    
    public List<Users> usersList(){
        if(users == null){
            users = userService.listAll();
        }
        return users;
    }
    
    public long userCount(){
        return userService.count();
    }
    
    public void openNewUser(){
        newUser = new Users();
    }
    
    public void updateUser(){
        userService.update(selectedUser);
        clearSelectedUser();
    }
    
    public void createUser(){
        userService.create(newUser);
        clearSelectedUser();        
    }
    
    public void deleteUser(){
        if(selectedUser != null){
            if(selectedUser.getId()!=null){
                userService.delete(selectedUser);
                clearSelectedUser();
            }
        }
    }
    
    public void clearSelectedUser(){
        users = null;
        newUser = null;
        selectedUser = null;
        viewManager.selectViewUsers();
    }
        
    public List<Users> getFilteredUsers() {
        if (usernameFilter != null && !usernameFilter.isEmpty()) {
            return usersList().stream()
                    .filter(user -> globalFilterFunction(user, usernameFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return usersList();
        }
    }
       
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Users user = (Users) value;
        return user.getUsername().toLowerCase().contains(filterText)
                || user.getGroupName().toLowerCase().contains(filterText);
    }

}
