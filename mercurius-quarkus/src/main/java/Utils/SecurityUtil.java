package Utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import Controllers.SessionController;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Utility class for security checks
 */
@ApplicationScoped
public class SecurityUtil {
    
    @Inject
    SessionController sessionController;
    
    @Inject
    SecurityIdentity securityIdentity;
    
    /**
     * Check if current user has admin role
     */
    public boolean isAdmin() {
        return sessionController.isAdmin();
    }
    
    /**
     * Check if current user has facturacion role
     */
    public boolean isFacturacion() {
        return sessionController.isFacturacion();
    }
    
    /**
     * Check if current user has inventario role
     */
    public boolean isInventarios() {
        return sessionController.isInventarios();
    }
    
    /**
     * Check if current user has usuario role
     */
    public boolean isUsuarios() {
        return sessionController.isUsuarios();
    }
    
    /**
     * Check if current user has tributacion role
     */
    public boolean isTributacion() {
        return sessionController.isTributacion();
    }
    
    /**
     * Check if current user has registro role
     */
    public boolean isRegistros() {
        return sessionController.isRegistros();
    }
    
    /**
     * Check if current user is authenticated
     */
    public boolean isAuthenticated() {
        return sessionController.isValid();
    }
    
    /**
     * Check if current user can access a specific role
     */
    public boolean hasRole(String role) {
        switch (role.toLowerCase()) {
            case "admin":
                return isAdmin();
            case "facturacion":
                return isFacturacion();
            case "inventario":
                return isInventarios();
            case "usuario":
                return isUsuarios();
            case "tributacion":
                return isTributacion();
            case "registro":
                return isRegistros();
            default:
                return false;
        }
    }
}