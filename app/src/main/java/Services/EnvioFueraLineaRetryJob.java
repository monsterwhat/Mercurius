package Services;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Scheduler entry point for the offline emission outbox
 * (see {@link EnvioFueraLineaService} for the Art. 21 ¶3 rationale).
 *
 * <p>Follows the conventions of {@code Utils.ProgramadorTareas}: a
 * {@code @Singleton} bean with an {@code @Scheduled} method and an initial
 * {@code delay}, no Quartz, no manual lifecycle.
 *
 * <h3>Safety rules this job obeys</h3>
 * <ul>
 *   <li><b>Never blocks startup</b> — {@code delay} pushes the first run to five
 *       minutes after boot, so a cold database or an unloaded certificate does
 *       not delay the application coming up.</li>
 *   <li><b>Never throws out of the scheduler</b> — {@code RuntimeException} and
 *       {@code Error} are both caught and logged. Quarkus's
 *       {@code InvocationContext} scheduler propagates a throwable as a failed
 *       invocation, and an unchecked error escaping here would be reported as a
 *       scheduler-wide failure rather than as a problem with the outbox.</li>
 *   <li><b>Never piles up</b> — {@link Scheduled.ConcurrentExecution#SKIP} drops
 *       a tick that would overlap the previous one. Combined with the two-hour
 *       internal {@code @Retry} delay in
 *       {@link HaciendaApiService#sendInvoice}, this means a Hacienda outage
 *       produces one in-flight job instead of a growing thread backlog.</li>
 *   <li><b>Bounded work</b> — the pass reads at most
 *       {@link EnvioFueraLineaService#LOTE_MAXIMO} rows.</li>
 * </ul>
 */
@Singleton
public class EnvioFueraLineaRetryJob {

    private static final Logger LOG = Logger.getLogger(EnvioFueraLineaRetryJob.class);

    @Inject
    private @Nonnull EnvioFueraLineaService envioFueraLineaService;

    /**
     * Every 15 minutes: well inside the two-business-day window even if several
     * consecutive ticks fail, while staying cheap when the outbox is empty.
     */
    @Scheduled(every = "15m",
            delay = 5,
            delayUnit = TimeUnit.MINUTES,
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void reintentarDocumentosPendientes() {
        try {
            int enviados = envioFueraLineaService.procesarPendientes();
            if (enviados > 0) {
                LOG.info("Buzon de envio fuera de linea: " + enviados
                        + " documento(s) transmitidos a Hacienda"
                        + " | source=EnvioFueraLineaRetryJob.reintentarDocumentosPendientes()"
                        + " | despues=Art. 21 parrafo 3");
            }
        } catch (RuntimeException | Error e) {
            // Deliberately broad: a failing outbox must not surface as a fatal
            // scheduler error, and must never escape into the scheduler thread.
            LOG.error("Fallo el proceso de reintentos del buzon de envio fuera de linea: " + e.getMessage()
                    + " | source=EnvioFueraLineaRetryJob.reintentarDocumentosPendientes()"
                    + " | despues=se reintentara en la proxima ejecucion; el plazo legal sigue corriendo", e);
        }
    }
}
