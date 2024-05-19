package Controllers;

import Models.Users;
import Services.LoginService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;
/**
 *
 * @author Al
 */

@Data
@Named(value = "UsersController")
@ViewScoped
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
    private String[] SelectedPuestos;

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
    
    public List<Users> usersListFull(){
        return userService.listAll();
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
        PrimeFaces.current().executeScript("PF('EditarUsuarioDialog').hide();");
    }
    
    public void createUser(){
        if(newUser != null){
            var exists = userService.usernameExists(newUser.getUsername());
            if(!exists){
                newUser.setStatus(true);
                userService.create(newUser);
                clearSelectedUser();
                PrimeFaces.current().executeScript("PF('CrearUsuarioDialog').hide();");
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Ya existe un usuario con ese nombre", null));
            }
        }
        
    }
    
    public void toggleUser(){
        if(selectedUser != null){
            if(selectedUser.getId()!=null){
                if(selectedUser.getStatus()){
                    disableUser();
                }else{
                    enableUser();
                }
                userService.update(selectedUser);
                clearSelectedUser();        
            }
        }
    }
    
    public void enableUser(){
        selectedUser.setStatus(true);
    }
    
    public void disableUser(){
        selectedUser.setStatus(false);
    }
    
    public void clearSelectedUser(){
        users = null;
        newUser = null;
        selectedUser = null;
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
    
    public List<Users> getFilteredUsersDetallado(){
        if (usernameFilter != null && !usernameFilter.isEmpty()) {
            return usersListFull().stream()
                    .filter(user -> globalFilterFunction(user, usernameFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return usersListFull();
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
    
    public void selectedOptionsChanged() {
        String message = "Se selecciono: ";
        if (SelectedPuestos != null) {
            for (int i = 0; i < SelectedPuestos.length; i++) {
                if (i > 0) {
                    message += ", ";
                }
                message += SelectedPuestos[i];
            }
        }

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
        
        newUser.setGroupName(Arrays.toString(SelectedPuestos));
    }
    
    public void selectedOptionsUpdateChanged() {
        String message = "Se selecciono: ";
        if (SelectedPuestos != null) {
            for (int i = 0; i < SelectedPuestos.length; i++) {
                if (i > 0) {
                    message += ", ";
                }
                message += SelectedPuestos[i];
            }
        }

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
        
        selectedUser.setGroupName(Arrays.toString(SelectedPuestos));
    }
    

}
