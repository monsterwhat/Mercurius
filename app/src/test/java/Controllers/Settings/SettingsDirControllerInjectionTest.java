package Controllers.Settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * El planificador de correo usa SettingsDirController para resolver las carpetas
 * de XML/PDF. El bean es @RequestScoped y su settingsService no tenía @Inject:
 * al inicializarse fuera de una petición, el campo quedaba null y el barrido
 * IMAP moría con NPE (que además abría el circuit breaker 15 minutos).
 */
@QuarkusTest
class SettingsDirControllerInjectionTest {

    @Inject
    SettingsDirController dirController;

    @Test
    void lasCarpetasSeResuelvenAunqueNoHayaPeticion() {
        var requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            assertThat(dirController.getXMLDirPath()).isNotBlank().contains("xml");
            assertThat(dirController.getPDFDirPath()).isNotBlank().contains("pdf");
        } finally {
            requestContext.terminate();
        }
    }
}
