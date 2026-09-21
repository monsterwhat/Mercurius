package Utils;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Utility class for security checks
 */
@ApplicationScoped
public class SecurityUtil {
    
    @Inject @Nonnull
    SecurityIdentity securityIdentity;
    
    /**
     * Check if current user has admin role
     */
    public boolean isAdmin() {
        return isAuthenticated() && securityIdentity.hasRole("admin");
    }
    
    /**
     * Check if current user has facturacion role
     */
    public boolean isFacturacion() {
        return isAuthenticated() && (securityIdentity.hasRole("facturacion") || isAdmin());
    }
    
    /**
     * Check if current user has inventario role
     */
    public boolean isInventarios() {
        return isAuthenticated() && (securityIdentity.hasRole("inventario") || isAdmin());
    }
    
    /**
     * Check if current user has usuario role
     */
    public boolean isUsuarios() {
        return isAuthenticated() && (securityIdentity.hasRole("usuario") || isAdmin());
    }
    
    /**
     * Check if current user has tributacion role
     */
    public boolean isTributacion() {
        return isAuthenticated() && (securityIdentity.hasRole("tributacion") || isAdmin());
    }
    
    /**
     * Check if current user has registro role
     */
    public boolean isRegistros() {
        return isAuthenticated() && (securityIdentity.hasRole("registro") || isAdmin());
    }
    
    /**
     * Check if current user is authenticated
     */
    public boolean isAuthenticated() {
        return securityIdentity != null && !securityIdentity.isAnonymous();
    }
    
    /**
     * Check if current user can access a specific role
     */
    public boolean hasRole(@Nonnull String role) {
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