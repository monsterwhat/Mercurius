package Services;

import Models.ConfiguracionAplicacion;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Rellena, SOLO en modo simulado (dev/test), los datos de emision que el XSD
 * de Hacienda exige y que una base de desarrollo suele tener vacios.
 *
 * <p>Objetivo: que una venta de prueba recorra el flujo completo (XML, XSD,
 * firma, PDF, inventario) sin que nadie tenga que configurar la empresa a
 * mano. Nunca sobreescribe un valor existente: si el usuario ya configuro un
 * campo, se respeta.</p>
 *
 * <p>Los valores son ficticios y se registran en el log para que no se
 * confundan con datos reales.</p>
 */
@ApplicationScoped
public class DevSettingsSeeder {

    private static final Logger LOG = Logger.getLogger(DevSettingsSeeder.class);

    @Inject
    AppSettingsService appSettingsService;

    void onStart(@Observes StartupEvent event) {
        if (!HaciendaTransmissionPolicy.perfilDesarrollo()) {
            return;
        }
        try {
            ConfiguracionAplicacion s = appSettingsService.returnCurrent();
            if (s == null) {
                return;
            }
            boolean changed = false;
            changed |= setIfBlank(s.getModoHacienda(), v -> s.setModoHacienda(v), "simulada");
            changed |= setIfBlank(s.getNombre(), v -> s.setNombre(v),
                    "EMPRESA DE PRUEBA MERCURIUS");
            changed |= setIfBlank(s.getNombreNegocio(), v -> s.setNombreNegocio(v),
                    "MERCURIUS PRUEBAS");
            changed |= setIfBlank(s.getTipoIdentificacion(), v -> s.setTipoIdentificacion(v), "01");
            changed |= setIfBlank(s.getIdentificacion(), v -> s.setIdentificacion(v), "104550334");
            changed |= setIfBlank(s.getProvincia(), v -> s.setProvincia(v), "1");
            changed |= setIfBlank(s.getCanton(), v -> s.setCanton(v), "01");
            changed |= setIfBlank(s.getDistrito(), v -> s.setDistrito(v), "01");
            changed |= setIfBlank(s.getBarrio(), v -> s.setBarrio(v), "Catedral");
            changed |= setIfBlank(s.getDireccionCompleta(), v -> s.setDireccionCompleta(v),
                    "Costado este del parque central, San Jose");
            changed |= setIfBlank(s.getTelefono(), v -> s.setTelefono(v), "22222222");
            changed |= setIfBlank(s.getCodigoPais(), v -> s.setCodigoPais(v), "506");
            changed |= setIfBlank(s.getCorreoElectronicoTributacion(),
                    v -> s.setCorreoElectronicoTributacion(v), "pruebas@mercurius.local");
            changed |= setIfBlank(s.getCodigoActividad(), v -> s.setCodigoActividad(v), "469001");
            changed |= setIfBlank(s.getProvedor(), v -> s.setProvedor(v), "3101999999");
            changed |= setIfBlank(s.getNombrePerfil(), v -> s.setNombrePerfil(v), "dev");

            if (changed) {
                appSettingsService.update(s);
                LOG.warn("Datos de emision de DESARROLLO completados con valores ficticios"
                        + " (solo campos vacios; no se sobreescribio nada)"
                        + " | emisor=" + s.getNombre()
                        + " | cedula=" + s.getIdentificacion()
                        + " | source=DevSettingsSeeder.onStart()");
            }
        } catch (RuntimeException e) {
            LOG.warn("No se pudieron completar los datos de emision de desarrollo: "
                    + e.getMessage() + " | source=DevSettingsSeeder.onStart()", e);
        }
    }

    private static boolean setIfBlank(String actual, java.util.function.Consumer<String> setter,
                                      String valor) {
        if (actual != null && !actual.isBlank()) {
            return false;
        }
        setter.accept(valor);
        return true;
    }
}
