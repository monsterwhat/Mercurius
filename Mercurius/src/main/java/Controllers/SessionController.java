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
import jakarta.security.enterprise.AuthenticationStatus;
import static jakarta.security.enterprise.AuthenticationStatus.SEND_CONTINUE;
import static jakarta.security.enterprise.AuthenticationStatus.SEND_FAILURE;
import static jakarta.security.enterprise.AuthenticationStatus.SUCCESS;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
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
    @Inject SecurityContext securityContext;
    @Inject private AlertasService alerta;

    @PostConstruct
    public void init(){
        loginService.init();
    }
    
    public void executeLogin(){
        try {
            switch (processAuthentication()) {
                case SEND_CONTINUE -> facesContext.responseComplete();
                case SEND_FAILURE -> {
                    facesContext.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "El nombre de usuario o contrasena son incorrectos."));
                }
                case SUCCESS -> {
                    if(securityContext.getCallerPrincipal().getName() != null){
                        currentUser = loginService.getSession(securityContext.getCallerPrincipal().getName());
                        alerta.registrarAlerta("Usuario Conectado", "El usuario se conectó", currentUser, 0, "executeLogin()", null, null);
                        redirectToSecuredArea();
                    }
                }   
                case NOT_DONE -> {
                }
                
                default -> throw new AssertionError();
            }
            
        } catch (IOException e) {
            //TODO ALERT OF ERROR
            System.out.println("Error logging in " + e.getLocalizedMessage());
        }
    }
    
    private void redirectToSecuredArea() throws IOException {
        ExternalContext ec = facesContext.getExternalContext();
        ec.redirect(ec.getRequestContextPath() + "/secured/index");
    }
    
    public void logOut() {
        try {
            ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
            ec.redirect(ec.getRequestContextPath() + "/index");
            ec.invalidateSession(); // Invalidate the session
            alerta.registrarAlerta("Usuario Desconectado", "El usuario se desconectó", getCurrentUser(), 0, "logOut()", null, null);
            this.currentUser = null;
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, "Success", "Logout successful!"));
            
        } catch (IOException e) {
            // Handle the IOException
            //TODO ALERT OF ERROR
            e.printStackTrace();
        }
    }

    public boolean isValid(){
        return currentUser != null;
    }
    
    public boolean isFacturacion(){
        return isValid() && currentUser.getGroupName().contains("facturacion") || isAdmin();
    }
    
    public boolean isInventarios(){
        return isValid() && currentUser.getGroupName().contains("inventario") || isAdmin();
    }
    
    public boolean isUsuarios(){
        return isValid() && currentUser.getGroupName().contains("usuario") || isAdmin();
    }
    
    public boolean isTributacion(){
        return isValid() && currentUser.getGroupName().contains("tributacion") || isAdmin();
    }
    
    public boolean isRegistros(){
        return isValid() && currentUser.getGroupName().contains("registro") || isAdmin();
    }
    
    public boolean isAdmin(){
        return isValid() && currentUser.getGroupName().contains("admin");
    }
            
    private AuthenticationStatus processAuthentication(){
        ExternalContext ec = getExternalContext();
        return securityContext.authenticate(
                (HttpServletRequest)ec.getRequest(),
                (HttpServletResponse)ec.getResponse(),
                AuthenticationParameters.withParams().credential(new UsernamePasswordCredential(username,password)));
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
                    alerta.registrarAlerta("Nombre de Usuario Cambiado", "Se cambió el nombre de usuario a: " + newUsername, getCurrentUser(), 0, "changeName()", currentUser.getUsername(), newUsername);
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
                    alerta.registrarAlerta("Correo Electrónico Cambiado", "Se cambió el correo electrónico a: " + newEmail, getCurrentUser(), 0, "changeEmail()", currentUser.getEmail(), newEmail);
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
                    alerta.registrarAlerta("Contraseña Cambiada", "Se cambió la contraseña", getCurrentUser(), 0, "changePassword()", null, null);
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
}
