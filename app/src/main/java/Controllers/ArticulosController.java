package Controllers;

import Models.Articulos.Articulos;
import Models.Departamento;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;

/**
 * Minimal stub for legacy JSF ArticulosController — referenced by FacturasController.
 * The real JSF controller was removed during migration; this stub allows
 * compilation and test execution while preserving the service call sites.
 */
@Named
@ApplicationScoped
public class ArticulosController {

    public @Nullable Articulos findArticuloByName(@Nonnull String nombre) {
        // No-op: legacy JSF lookup; not exercised by REST API tests.
        return null;
    }

    public @Nullable Articulos findArticuloByBarCode(@Nonnull String codigoBarra) {
        // No-op: legacy JSF lookup; not exercised by REST API tests.
        return null;
    }

    public void createSimpleArticulo(@Nonnull Articulos articulo) {
        // No-op: legacy JSF create; service layer handles persistence in tests.
    }

    public void updateSimpleArticulo(@Nonnull Articulos articulo) {
        // No-op: legacy JSF update; service layer handles persistence in tests.
    }
}