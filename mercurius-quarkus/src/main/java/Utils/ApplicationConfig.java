package Utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.annotation.FacesConfig;

/**
 *
 * @author Al
 */

// Jakarta Security configuration removed - now handled by Quarkus Security in application.properties
@FacesConfig
@ApplicationScoped
public class ApplicationConfig{
    
    // Security configuration moved to application.properties:
    // quarkus.security.jdbc.enabled=true
    // quarkus.security.jdbc.principal-query.sql=SELECT password FROM Users WHERE username = ? AND status = true
    // quarkus.security.jdbc.principal-query.clear-password-mapper.enabled=true
    // quarkus.security.jdbc.principal-query.groups-query.sql=SELECT groupName FROM Users WHERE username = ? AND status = true
    
}
