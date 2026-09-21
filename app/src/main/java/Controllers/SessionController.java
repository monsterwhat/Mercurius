package Controllers;

import Models.Users;
import Services.LoginService;
import org.jboss.logging.Logger;
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

    private static final Logger LOG = Logger.getLogger(SessionController.class);

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

    @PostConstruct
    public void init(){
        loginService.init();
    }
    
    public synchronized void executeLogin(){
        try {
            if (processAuthentication()) {
                if(currentUser != null){
                    LOG.info("failed to execute login()");
                    redirectToSecuredArea();
                }
            } else {
                LOG.info("failed to execute login()");
                LOG.warn("failed to login");
            }
            
        } catch (IOException e) { 
            LOG.info("failed to executelogin");
            LOG.warn("failed to login");
            LOG.warn("failed to login");
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
                LOG.info("failed to log out()");
            }
            
            // Redirect after session is properly invalidated
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/login");
            
        } catch (IOException e) { 
            LOG.info("failed to logout");
            LOG.warn("failed to logout"); 
            
            // Fallback redirect if primary fails
            try {
                if (ec != null) {
                    httpResponse.sendRedirect(httpRequest.getContextPath() + "/login");
                }
            } catch (IOException fallbackEx) {
                LOG.warn("failed to logout");
            }
        } catch (IllegalStateException e) {
            // Session already invalidated - this is expected
            LOG.warn("failed to logout");
            try {
                if (ec != null) {
                    httpResponse.sendRedirect(httpRequest.getContextPath() + "/login");
                }
            } catch (IOException fallbackEx) {
                LOG.warn("failed to logout");
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
            LOG.warn("failed to authenticate");
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
        LOG.warn("failed to session controller");
    }
    
    public void infoMessage(@Nonnull String message){
        LOG.info("failed to session controller");
    }
        
    public void warnMessage(@Nonnull String message){
        LOG.info("failed to session controller");
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
                    LOG.info("failed to change name()");
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
                    LOG.info("failed to change email()");
                    loginService.updateEmail(currentUser, newEmail);
                    infoMessage("Se actualizo el correo electronico.");
                    newEmail = null;
                }else{
                    warnMessage("El nuevo correo no puede ser igual");
                }
            }else{
                LOG.warn("failed to session controller");
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
                    LOG.info("failed to change password()");
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
                LOG.info("failed to authorize action()");
                return null;
            }

            if (!loginService.verifyPassword(password, authUser.getPassword())) {
                LOG.info("failed to authorize action()");
                return null;
            }

            return authUser;
        } catch (RuntimeException e) {
            LOG.warn("failed to authorize action()", e);
            return null;
        }
    }
}
