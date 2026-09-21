package Controllers.Correos;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import Models.Correos.ReporteProgramado;
import java.util.Date;

/**
 * Minimal stub for legacy JSF CorreosHelper — referenced by CorreosScheduler.
 * The real JSF helper was removed during migration; this stub allows
 * compilation and test execution while preserving the scheduler call sites.
 */
@Named
@ApplicationScoped
public class CorreosHelper {

    public @Nullable Date calcularFechaProximoReporte(@Nonnull Date fechaBase, @Nonnull String frecuencia) {
        // No-op: returns null so scheduler doesn't trigger real sends during tests.
        return null;
    }

    public void checkChanges(@Nonnull ReporteProgramado reporte) {
        // No-op: legacy email generation path; not exercised by REST API tests.
    }
}