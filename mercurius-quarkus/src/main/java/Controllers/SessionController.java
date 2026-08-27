package Controllers;

import Models.Users;
import Services.AlertasService;
import Services.LoginService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;



import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.constraints.NotEmpty;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 *
 * @author Al
 */

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "SessionController")
@SessionScoped
public class SessionController implements Serializable{

    public SessionController() {
    }
    
    @NotEmpty @Nonnull private String username;
    @NotEmpty @Nonnull private String password;
    @Nullable private Users currentUser;
    
    @Nullable private String newUsername;
    @Nullable private String currentPassword;
    @Nullable private String newPassword;
    @Nullable private String newEmail;
    @Nullable private String confirmPassword;
    
    @Inject @Nonnull private LoginService loginService;
    
    @Inject HttpServletRequest httpRequest;
    @Inject HttpServletResponse httpResponse;
    @Inject @Nonnull SecurityIdentity securityIdentity;
    @Inject @Nonnull private AlertasService alertas;

    @PostConstruct
    public void init(){
        loginService.init();
    }
    
    public synchronized void executeLogin(){
        try {
            if (processAuthentication()) {
                if(currentUser != null){
                    alertas.registrarAlerta("Usuario Conectado", "El usuario se conectó", currentUser, 0, "executeLogin()", null, null);
                    redirectToSecuredArea();
                }
            } else {
                alertas.registrarAlerta("Intento de Login Fallido", "Usuario: " + username + " - Credenciales incorrectas", null, 0, "executeLogin()", username, null);
                alertas.registrarAlerta("Error", "Credenciales invalidas", null, 0, "SessionController.login", null, null);
            }
            
        } catch (IOException e) { 
            alertas.registrarAlerta("Error al iniciar sesion " + username, e.getLocalizedMessage(), null, 0, "sessionController.executelogin()", e.getLocalizedMessage(), null);
            alertas.registrarAlerta("Error", "Error al iniciar sesion", null, 0, "SessionController.login", null, null);
            alertas.registrarAlerta("Error", "Error logging in " + e.getLocalizedMessage(), null, 0, "SessionController.login()", null, e.getLocalizedMessage());
        }
    }
    
    private void redirectToSecuredArea() throws IOException {
        HttpServletRequest ec = httpRequest;
        httpResponse.sendRedirect(httpRequest.getContextPath() + "/app/dashboard");
    }
    
    public synchronized void logOut() {
        HttpServletRequest ec = null;
        try {
            ec = httpRequest;
            
            // Register logout alert before destroying session
            Users userToLog = getCurrentUser();
            
            // Invalidate session FIRST to prevent session fixation
            httpRequest.getSession().invalidate();
            
            // Clear user data after session invalidation
            this.currentUser = null;
            
            // Register logout alert (user may be null after session clear)
            if (userToLog != null) {
                alertas.registrarAlerta("Usuario Desconectado", "El usuario se desconectó", userToLog, 0, "logOut()", null, null);
            }
            
            // Redirect after session is properly invalidated
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/login");
            
        } catch (IOException e) { 
            alertas.registrarAlerta("Error al cerrar sesion", e.getLocalizedMessage(), null, 0, "sessionController.logout()", null, null);
            alertas.registrarAlerta("Error", "Error al cerrar sesion: " + e.getLocalizedMessage(), null, 0, "SessionController.logout()", null, e.getLocalizedMessage()); 
            
            // Fallback redirect if primary fails
            try {
                if (ec != null) {
                    httpResponse.sendRedirect(httpRequest.getContextPath() + "/login");
                }
            } catch (IOException fallbackEx) {
                alertas.registrarAlerta("Error", "Fallback redirect failed: " + fallbackEx.getLocalizedMessage(), null, 0, "SessionController.logout()", null, fallbackEx.getLocalizedMessage());
            }
        } catch (IllegalStateException e) {
            // Session already invalidated - this is expected
            alertas.registrarAlerta("Error", "Session already invalidated: " + e.getLocalizedMessage(), null, 0, "SessionController.logout()", null, e.getLocalizedMessage());
            try {
                if (ec != null) {
                    httpResponse.sendRedirect(httpRequest.getContextPath() + "/login");
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
                // Set the user in the HTTP session so SecurityFilter can verify authentication
                // without relying on container-managed FORM auth (which is not configured in Quarkus)
                getRequest().getSession().setAttribute("authUser", user);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error", "Authentication error: " + e.getLocalizedMessage(), null, 0, "SessionController.authenticate()", null, e.getLocalizedMessage());
            return false;
        }
    }
    
    private HttpServletRequest getRequest() {
        return httpRequest;
    }
    
    private HttpServletRequest getHttpServletRequest(){
     return httpRequest;
    }
    
    public void errorMessage(@Nonnull String message){
        alertas.registrarAlerta("Error", message, null, 0, "SessionController", null, null);
    }
    
    public void infoMessage(@Nonnull String message){
        alertas.registrarAlerta("Info", message, null, 0, "SessionController", null, null);
    }
        
    public void warnMessage(@Nonnull String message){
        alertas.registrarAlerta("Advertencia", message, null, 0, "SessionController", null, null);
    }
    
    @Nullable
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
    
    
    public synchronized void changeName(){
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
    
    public synchronized void changeEmail(){
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
                alertas.registrarAlerta("Error", "El nuevo correo no puede estar vacio.", null, 0, "SessionController", null, null);
            }
        }
    }
    
    public synchronized void changePassword(){
        if(newPassword != null){
            if(!newPassword.isBlank()){
                if(newPassword.equals(confirmPassword)){
                    if(currentPassword == null || currentPassword.isBlank()){
                        errorMessage("La contrasena actual no puede estar vacia.");
                        return;
                    }
                    if(!loginService.verifyPassword(currentPassword, currentUser.getPassword())){
                        errorMessage("La contrasena actual es incorrecta.");
                        return;
                    }
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
    
    @Nullable
    public Users getCurrentUser() {
        return currentUser;
    }

    @Nullable
    public Users authorizeAction(@Nonnull String username, @Nonnull String password) {
        try {
            Users authUser = loginService.findByUsername(username);
            if (authUser == null) {
                alertas.registrarAlerta("Autorización Fallida",
                    "Intento con usuario inexistente: " + username,
                    currentUser, 0, "authorizeAction()", null, null);
                return null;
            }

            if (!loginService.verifyPassword(password, authUser.getPassword())) {
                alertas.registrarAlerta("Autorización Fallida",
                    "Contraseña incorrecta de: " + username,
                    currentUser, 0, "authorizeAction()", null, null);
                return null;
            }

            return authUser;
        } catch (RuntimeException e) {
            alertas.registrarAlerta("Error",
                "Error en authorizeAction: " + e.getMessage(),
                currentUser, 0, "authorizeAction()", null, e.getMessage());
            return null;
        }
    }
}
