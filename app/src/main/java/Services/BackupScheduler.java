package Services;

import Models.ConfiguracionAplicacion;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.jboss.logging.Logger;

/**
 * Ejecuta el respaldo automatico configurado en Ajustes.
 *
 * <p>La pantalla ya guardaba {@code backupHabilitado}, {@code backupHora} y
 * {@code backupRetencionDias}, y ofrecia el boton manual, pero NADA programado
 * los leia: un negocio con "Respaldo automático" activo no recibia ningun
 * respaldo. Este job revisa cada 10 minutos y corre el respaldo una vez al dia,
 * a la hora configurada (o en la primera revision despues de esa hora si el
 * servidor estaba apagado).</p>
 */
@ApplicationScoped
public class BackupScheduler {

    private static final Logger LOG = Logger.getLogger(BackupScheduler.class);

    @Inject
    BackupService backupService;

    @Scheduled(cron = "0 */10 * * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void respaldoAutomatico() {
        try {
            ConfiguracionAplicacion settings = backupService.getSettings();
            if (settings == null || !Boolean.TRUE.equals(settings.getBackupHabilitado())) {
                return;
            }
            String horaConfigurada = settings.getBackupHora();
            if (horaConfigurada == null || !horaConfigurada.matches("\\d{2}:\\d{2}")) {
                LOG.warn("Respaldo automático activo con hora inválida: '" + horaConfigurada
                        + "' | source=BackupScheduler.respaldoAutomatico()");
                return;
            }
            LocalDateTime ahora = LocalDateTime.now();
            LocalDateTime ultimo = settings.getBackupUltimoEjecutado();
            if (ultimo != null && !ultimo.toLocalDate().isBefore(ahora.toLocalDate())) {
                // Ya se respaldó hoy.
                return;
            }
            LocalTime programada = LocalTime.parse(horaConfigurada);
            if (ahora.toLocalTime().isBefore(programada)) {
                return;
            }
            LOG.info("Ejecutando respaldo automático programado a las " + horaConfigurada
                    + " | source=BackupScheduler.respaldoAutomatico()");
            if (!backupService.ejecutarBackup()) {
                LOG.warn("El respaldo automático falló; se reintentará en la próxima revisión"
                        + " | source=BackupScheduler.respaldoAutomatico()");
            }
        } catch (RuntimeException e) {
            LOG.error("Error en el respaldo automático", e);
        }
    }
}
