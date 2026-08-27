package Controllers;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import Services.AlertasService;

/**
 * Minimal stub for legacy JSF CabysController — referenced by CabysService.
 * The real JSF controller was removed during migration; this stub allows
 * compilation and test execution while preserving the service call sites.
 */
@Named
@ApplicationScoped
public class CabysController {

    @Inject @Nonnull AlertasService alertasService;

    public void showInfo(@Nonnull String title, @Nonnull String message) {
        // No-op: legacy FacesMessage push; tests don't exercise this path.
    }

    public void showWarn(@Nonnull String title, @Nonnull String message) {
        // No-op.
    }

    public void showError(@Nonnull String title, @Nonnull String message) {
        // No-op.
    }
}