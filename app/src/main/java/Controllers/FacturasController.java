package Controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.Serializable;

/**
 * Legacy JSF bean stub - decommissioned per T39.
 * Original ViewScoped/PrimeFaces logic migrated to API resources.
 * Kept as @Named bean for CDI compatibility until full removal.
 */
@Named("facturasController")
@ApplicationScoped
public class FacturasController implements Serializable {
    private static final long serialVersionUID = 1L;
}