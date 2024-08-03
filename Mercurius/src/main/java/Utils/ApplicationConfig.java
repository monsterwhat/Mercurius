package Utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.annotation.FacesConfig;
import jakarta.security.enterprise.authentication.mechanism.http.CustomFormAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.LoginToContinue;
import jakarta.security.enterprise.identitystore.DatabaseIdentityStoreDefinition;

/**
 *
 * @author Al
 */

@DatabaseIdentityStoreDefinition(
    dataSourceLookup = "jdbc/Mercurius",
    callerQuery = "SELECT password FROM Users WHERE username = ? AND status = true",
    groupsQuery = "SELECT groupName FROM Users WHERE username = ? AND status = true"
)
@CustomFormAuthenticationMechanismDefinition(
        loginToContinue = @LoginToContinue(
        loginPage = "/index",
        errorPage = "/",
        useForwardToLogin = false)
)
@FacesConfig
@ApplicationScoped
public class ApplicationConfig{
    
    
    
}
