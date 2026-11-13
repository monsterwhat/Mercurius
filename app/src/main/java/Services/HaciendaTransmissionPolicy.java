package Services;

import Models.ConfiguracionAplicacion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Politica de transmision a Hacienda.
 *
 * <p>El modo se configura en Ajustes (columna {@code modoHacienda}):</p>
 * <ul>
 *   <li>{@code simulada}: el comprobante se arma, se valida contra el XSD, se
 *       firma si hay certificado, se genera el PDF, se mueve el inventario y
 *       se registran los estados exactamente como en produccion. Lo unico que
 *       no ocurre es la llamada HTTP a Hacienda: la respuesta se simula.</li>
 *   <li>{@code real}: transmite al ambiente configurado (sandbox o
 *       produccion).</li>
 *   <li>{@code null}: decide el perfil; {@code dev}/{@code test} simulan y
 *       produccion transmite. Asi una instalacion nueva de desarrollo
 *       funciona sin configurar nada.</li>
 * </ul>
 */
@ApplicationScoped
public class HaciendaTransmissionPolicy {

    private static final Logger LOG = Logger.getLogger(HaciendaTransmissionPolicy.class);

    @Inject
    AppSettingsService appSettingsService;

    /** {@code true} cuando el comprobante no se transmite. */
    public boolean simulada() {
        String valor = modoConfigurado();
        if ("simulada".equals(valor)) {
            return true;
        }
        if ("real".equals(valor)) {
            return false;
        }
        return perfilDesarrollo();
    }

    /** {@code true} cuando se puede transmitir de verdad. */
    public boolean real() {
        return !simulada();
    }

    /**
     * Marca para el mensaje al operador, para no confundir una aceptacion
     * local con una aceptacion de Hacienda.
     */
    public String etiquetaSimulada() {
        return simulada() ? " (simulado, sin envío)" : "";
    }

    /** Perfil activo, para las decisiones por defecto. */
    public static boolean perfilDesarrollo() {
        String perfil = perfilActivo();
        return "dev".equals(perfil) || "test".equals(perfil);
    }

    /** Perfil activo segun la configuracion de Quarkus (o el sistema). */
    public static String perfilActivo() {
        String perfil = null;
        try {
            perfil = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.profile", String.class)
                    .orElse(null);
        } catch (RuntimeException e) {
            LOG.debug("No se pudo leer quarkus.profile de la configuracion", e);
        }
        if (perfil == null || perfil.isBlank()) {
            perfil = System.getProperty("quarkus.profile");
        }
        if (perfil == null || perfil.isBlank()) {
            perfil = System.getenv("QUARKUS_PROFILE");
        }
        return perfil == null ? "" : perfil.trim().toLowerCase();
    }

    private String modoConfigurado() {
        try {
            ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
            if (settings == null || settings.getModoHacienda() == null) {
                return null;
            }
            return settings.getModoHacienda().trim().toLowerCase();
        } catch (RuntimeException e) {
            LOG.debug("No se pudo leer el modo de transmision; se usa el perfil", e);
            return null;
        }
    }
}
