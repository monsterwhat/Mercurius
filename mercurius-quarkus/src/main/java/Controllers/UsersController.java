package Controllers;

import Models.Users;
import Services.AlertasService;
import Services.LoginService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import Utils.DiffUtils;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;
/**
 *
 * @author Al
 */

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "UsersController")
@ViewScoped
public class UsersController implements Serializable{
    
    @Inject @Nonnull private AlertasService alertas;
    @Inject @Nonnull private LoginService userService;
    @Inject @Nonnull private SessionController currentSession;

    @Nullable
    private List<Users> users;
    @Nullable
    private Users selectedUser;
    @Nullable
    private Users newUser;
    @Nullable
    private String generatorOption;
    @Nullable
    private String usernameFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    @Nullable
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
    
    @Nullable
    public List<Users> usersList(){
        if(users == null){
            users = userService.listAll();
        }
        return users;
    }
    
    @Nullable
    public List<Users> usersListFull(){
        return userService.listAll();
    }
    
    public long userCount(){
        return userService.count();
    }
    
    public long usuariosActivosCount(){
        return userService.countActivos();
    }
    
    public long usuariosInactivosCount(){
        return userService.countInactivos();
    }
    
    public void openNewUser(){
        newUser = new Users();
    }
    
    public void updateUser(){
        // Server-side security check - admin or usuario role required
        if (!currentSession.isUsuarios() && !currentSession.isAdmin()) {
            alertas.registrarAlerta("Acceso Denegado", "Usuario: " + currentSession.getCurrentUsername() + " intento actualizar usuario sin permisos", currentSession.getCurrentUser(), 0, "updateUser()", null, "Intento de acceso no autorizado");
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Access Denied", "User management access required"));
            return;
        }
        
        String antes = DiffUtils.snapshotEntity(selectedUser);
        userService.update(selectedUser);
        alertas.registrarAlerta("Usuario Actualizado", "Se actualizo el usuario: " + selectedUser.getUsername(), currentSession.getCurrentUser(), 0, "updateUser()", antes, DiffUtils.snapshotEntity(selectedUser));
        clearSelectedUser();
        PrimeFaces.current().executeScript("PF('EditarUsuarioDialog').hide();");
    }
    
    public void createUser(){
        // Server-side security check - admin or usuario role required
        if (!currentSession.isUsuarios() && !currentSession.isAdmin()) {
            alertas.registrarAlerta("Acceso Denegado", "Usuario: " + currentSession.getCurrentUsername() + " intento crear usuario sin permisos", currentSession.getCurrentUser(), 0, "createUser()", null, "Intento de acceso no autorizado");
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Access Denied", "User management access required"));
            return;
        }
        
        if(newUser != null){
            var exists = userService.usernameExists(newUser.getUsername());
            if(!exists){
                newUser.setStatus(true);
                userService.create(newUser);
                alertas.registrarAlerta("Usuario Creado", "Se creo el usuario: " + newUser.getUsername(), currentSession.getCurrentUser(), 0, "createUser()", null, newUser.toString());
                clearSelectedUser();
                PrimeFaces.current().executeScript("PF('CrearUsuarioDialog').hide();");
            }else{
                FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Ya existe un usuario con ese nombre", null));
            }
        }
        
    }
    
    public void toggleUser(){
        // Server-side security check - admin or usuario role required
        if (!currentSession.isUsuarios() && !currentSession.isAdmin()) {
            alertas.registrarAlerta("Acceso Denegado", "Usuario: " + currentSession.getCurrentUsername() + " intento cambiar estado de usuario sin permisos", currentSession.getCurrentUser(), 0, "toggleUser()", null, "Intento de acceso no autorizado");
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Access Denied", "User management access required"));
            return;
        }
        
        if(selectedUser != null){
            if(selectedUser.getId()!=null){
                String antes = DiffUtils.snapshotEntity(selectedUser);
                if(selectedUser.getStatus()){
                    disableUser();
                }else{
                    enableUser();
                }
                userService.update(selectedUser);
                alertas.registrarAlerta("Estado de Usuario Cambiado", "Se cambio el estado del usuario: " + selectedUser.getUsername(), currentSession.getCurrentUser(), 0, "toggleUser()", antes, DiffUtils.snapshotEntity(selectedUser));
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
        
    @Nullable
    public List<Users> getFilteredUsers() {
        if (usernameFilter != null && !usernameFilter.isEmpty()) {
            return usersList().stream()
                    .filter(user -> globalFilterFunction(user, usernameFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return usersList();
        }
    }
    
    @Nullable
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
