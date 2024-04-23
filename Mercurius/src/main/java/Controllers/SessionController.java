package Controllers;

import Models.Users;
import Services.LoginService;
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
    
    @Inject private LoginService loginService;
    @Inject FacesContext facesContext;
    @Inject SecurityContext securityContext;

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
                        redirectToSecuredArea();
                    }
                }   
                case NOT_DONE -> {
                }
                
                default -> throw new AssertionError();
            }
            
        } catch (IOException e) {
            System.out.println("Error logging in " + e.getLocalizedMessage());
        }
    }
    
    private void redirectToSecuredArea() throws IOException {
        ExternalContext ec = facesContext.getExternalContext();
        ec.redirect(ec.getRequestContextPath() + "/secured/index.xhtml");
    }
    
    public void logOut() {
        try {
            ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
            ec.redirect(ec.getRequestContextPath() + "/index.xhtml");
            ec.invalidateSession(); // Invalidate the session
            this.currentUser = null;
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, "Success", "Logout successful!"));
        } catch (IOException e) {
            // Handle the IOException
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
            
}
