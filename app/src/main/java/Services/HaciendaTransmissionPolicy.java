package Services;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Candado de transmision a Hacienda.
 *
 * <p>En desarrollo no se puede emitir contra Tributacion: un documento con
 * clave y XML firmado es fiscalmente real aunque el ambiente sea sandbox, y
 * ademas contamina los consecutivos y la cola del entorno local.</p>
 *
 * <p>La propiedad {@code mercurius.hacienda.transmision-habilitada} se apaga
 * en el perfil {@code dev} (application.properties). El valor por defecto es
 * {@code true}, de modo que produccion no cambia de comportamiento.</p>
 *
 * <p>Se consulta en los DOS puntos de salida: el envio inmediato de una venta
 * ({@code ComprobanteService.enviarComprobanteAHacienda}) y el reintento de la
 * cola ({@code EnvioFueraLineaService.reintentar}), incluido "Enviar ahora"
 * del drawer de Consultas.</p>
 */
@ApplicationScoped
public class HaciendaTransmissionPolicy {

    @ConfigProperty(name = "mercurius.hacienda.transmision-habilitada", defaultValue = "true")
    boolean transmisionHabilitada;

    /** {@code true} cuando este entorno puede transmitir documentos. */
    public boolean habilitada() {
        return transmisionHabilitada;
    }
}
