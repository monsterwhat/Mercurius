package Controllers;

import Models.Users;
import Services.AlertasService;
import Services.LoginService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.constraints.NotEmpty;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Named(value = "SessionController")
@SessionScoped
public class SessionController implements Serializable{

    public SessionController() {
    }
    
    @NotEmpty private String username;
    @NotEmpty private String password;
    private Users currentUser;
    
    private String newUsername;
    private String currentPassword;
    private String newPassword;
    private String newEmail;
    private String confirmPassword;
    
    @Inject private LoginService loginService;
    
    @Inject FacesContext facesContext;
    @Inject SecurityIdentity securityIdentity;
    @Inject private AlertasService alertas;

    @PostConstruct
    public void init(){
        loginService.init();
    }
    
    public void executeLogin(){
        try {
            if (processAuthentication()) {
                if(currentUser != null){
                    alertas.registrarAlerta("Usuario Conectado", "El usuario se conectó", currentUser, 0, "executeLogin()", null, null);
                    redirectToSecuredArea();
                }
            } else {
                alertas.registrarAlerta("Intento de Login Fallido", "Usuario: " + username + " - Credenciales incorrectas", null, 0, "executeLogin()", username, null);
                facesContext.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El nombre de usuario o contrasena son incorrectos."));
            }
            
        } catch (IOException e) { 
            alertas.registrarAlerta("Error al iniciar sesion " + username, e.getLocalizedMessage(), null, 0, "sessionController.executelogin()", e.getLocalizedMessage(), null);
            FacesMessage message = new FacesMessage("Error", "Error al iniciar sesion");
            FacesContext.getCurrentInstance().addMessage(null, message);
            alertas.registrarAlerta("Error", "Error logging in " + e.getLocalizedMessage(), null, 0, "SessionController.login()", null, e.getLocalizedMessage());
        }
    }
    
    private void redirectToSecuredArea() throws IOException {
        ExternalContext ec = facesContext.getExternalContext();
        ec.redirect(ec.getRequestContextPath() + "/secured/index.xhtml");
    }
    
    public void logOut() {
        ExternalContext ec = null;
        try {
            ec = FacesContext.getCurrentInstance().getExternalContext();
            
            // Register logout alert before destroying session
            Users userToLog = getCurrentUser();
            
            // Invalidate session FIRST to prevent session fixation
            ec.invalidateSession();
            
            // Clear user data after session invalidation
            this.currentUser = null;
            
            // Register logout alert (user may be null after session clear)
            if (userToLog != null) {
                alertas.registrarAlerta("Usuario Desconectado", "El usuario se desconectó", userToLog, 0, "logOut()", null, null);
            }
            
            // Redirect after session is properly invalidated
            ec.redirect(ec.getRequestContextPath() + "/index.xhtml");
            
        } catch (IOException e) { 
            alertas.registrarAlerta("Error al cerrar sesion", e.getLocalizedMessage(), null, 0, "sessionController.logout()", null, null);
            alertas.registrarAlerta("Error", "Error al cerrar sesion: " + e.getLocalizedMessage(), null, 0, "SessionController.logout()", null, e.getLocalizedMessage()); 
            
            // Fallback redirect if primary fails
            try {
                if (ec != null) {
                    ec.redirect(ec.getRequestContextPath() + "/index.xhtml");
                }
            } catch (IOException fallbackEx) {
                alertas.registrarAlerta("Error", "Fallback redirect failed: " + fallbackEx.getLocalizedMessage(), null, 0, "SessionController.logout()", null, fallbackEx.getLocalizedMessage());
            }
        } catch (IllegalStateException e) {
            // Session already invalidated - this is expected
            alertas.registrarAlerta("Error", "Session already invalidated: " + e.getLocalizedMessage(), null, 0, "SessionController.logout()", null, e.getLocalizedMessage());
            try {
                if (ec != null) {
                    ec.redirect(ec.getRequestContextPath() + "/index.xhtml");
                }
            } catch (IOException fallbackEx) {
                alertas.registrarAlerta("Error", "Redirect after invalid session failed: " + fallbackEx.getLocalizedMessage(), null, 0, "SessionController.logout()", null, fallbackEx.getLocalizedMessage());
            }
        }
    }

    public boolean isValid(){
        if (currentUser != null) {
            return true;
        }
        // Fallback to Quarkus Security context
        return securityIdentity != null && securityIdentity.isAnonymous() == false;
    }
    
    public boolean isFacturacion(){
        if (isValid() && currentUser != null) {
            return currentUser.getGroupName().contains("facturacion") || isAdmin();
        }
        // Fallback to Quarkus Security roles
        return isValid() && (securityIdentity.hasRole("facturacion") || isAdmin());
    }
    
    public boolean isInventarios(){
        if (isValid() && currentUser != null) {
            return currentUser.getGroupName().contains("inventario") || isAdmin();
        }
        // Fallback to Quarkus Security roles
        return isValid() && (securityIdentity.hasRole("inventario") || isAdmin());
    }
    
    public boolean isUsuarios(){
        if (isValid() && currentUser != null) {
            return currentUser.getGroupName().contains("usuario") || isAdmin();
        }
        // Fallback to Quarkus Security roles
        return isValid() && (securityIdentity.hasRole("usuario") || isAdmin());
    }
    
    public boolean isTributacion(){
        if (isValid() && currentUser != null) {
            return currentUser.getGroupName().contains("tributacion") || isAdmin();
        }
        // Fallback to Quarkus Security roles
        return isValid() && (securityIdentity.hasRole("tributacion") || isAdmin());
    }
    
    public boolean isRegistros(){
        if (isValid() && currentUser != null) {
            return currentUser.getGroupName().contains("registro") || isAdmin();
        }
        // Fallback to Quarkus Security roles
        return isValid() && (securityIdentity.hasRole("registro") || isAdmin());
    }
    
    public boolean isAdmin(){
        if (isValid() && currentUser != null) {
            return currentUser.getGroupName().contains("admin");
        }
        // Fallback to Quarkus Security roles
        return isValid() && securityIdentity.hasRole("admin");
    }
            
    private boolean processAuthentication(){
        try {
            // Custom authentication that integrates with Quarkus Security
            Users user = loginService.findByUsername(username);
            if (user != null && loginService.verifyPassword(password, user.getPassword())) {
                currentUser = user;
                return true;
            }
            return false;
        } catch (Exception e) {
            alertas.registrarAlerta("Error", "Authentication error: " + e.getLocalizedMessage(), null, 0, "SessionController.authenticate()", null, e.getLocalizedMessage());
            return false;
        }
    }
    
    private ExternalContext getExternalContext(){
     return facesContext.getExternalContext();
    }
    
    public void errorMessage(String message){
        facesContext.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", message));
    }
    
    public void infoMessage(String message){
        facesContext.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito", message));
    }
        
    public void warnMessage(String message){
        facesContext.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_WARN, "Advertencia", message));
    }
    
    public String getCurrentUsername() {
        if (currentUser != null) {
            return currentUser.getUsername();
        }
        // Fallback to Quarkus Security
        return securityIdentity.getPrincipal() != null ? securityIdentity.getPrincipal().getName() : null;
    }
    
    public void openEmail(){
        newEmail = new String();
    }
    
    public void openUsername(){
        newUsername = new String();
    }
    
    public void openPassword(){
        currentPassword = new String();
        newPassword = new String();
        confirmPassword = new String();
    }
    
    
    public void changeName(){
        if(newUsername != null){
            if(!newUsername.isBlank()){
                if(!currentUser.getUsername().equals(newUsername)){
                    alertas.registrarAlerta("Nombre de Usuario Cambiado", "Se cambió el nombre de usuario a: " + newUsername, getCurrentUser(), 0, "changeName()", currentUser.getUsername(), newUsername);
                    loginService.updateUsername(currentUser, newUsername);
                    infoMessage("Se actualizo el nombre de usuario.");
                    newUsername = null;
                }else{
                    warnMessage( "El nuevo nombre no puede ser igual");
                }
            }else{
                errorMessage("El nuevo nombre no puede estar vacio.");
            }
        }
    }
    
    public void changeEmail(){
        if(newEmail != null){
            if(!newEmail.isBlank()){
                if(!currentUser.getEmail().equals(newEmail)){
                    alertas.registrarAlerta("Correo Electrónico Cambiado", "Se cambió el correo electrónico a: " + newEmail, getCurrentUser(), 0, "changeEmail()", currentUser.getEmail(), newEmail);
                    loginService.updateEmail(currentUser, newEmail);
                    infoMessage("Se actualizo el correo electronico.");
                    newEmail = null;
                }else{
                    warnMessage("El nuevo correo no puede ser igual");
                }
            }else{
                facesContext.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El nuevo correo no puede estar vacio."));
            }
        }
    }
    
    public void changePassword(){
        if(newPassword != null){
            if(!newPassword.isBlank()){
                if(newPassword.equals(confirmPassword)){
                    loginService.updatePassword(currentUser, newPassword);
                    alertas.registrarAlerta("Contraseña Cambiada", "Se cambió la contraseña", getCurrentUser(), 0, "changePassword()", null, null);
                    infoMessage("Se actualizo la contrasena.");
                    newPassword = null;
                }else{
                    warnMessage("Las contrasenas no son iguales.");
                }
            }else{
                errorMessage("La nueva contrasena no puede estar vacia");
            }
        }
    }
    
    public Users getCurrentUser() {
        return currentUser;
    }
}
