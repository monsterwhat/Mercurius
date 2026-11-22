package Services;

import io.quarkus.scheduler.Scheduled;
import org.junit.jupiter.api.Test;
import Utils.ProgramadorTareas;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regresión del respaldo corrupto del 2026-11-09..2026-11-22.
 *
 * <p>Causa: {@code ProgramadorTareas} y {@code BackupScheduler} disparaban a
 * las 03:00 y llamaban a {@code BackupService.ejecutarBackup()} a la vez. Dos
 * {@code GZIPOutputStream} escribiendo el mismo archivo producen un gzip
 * inválido ({@code invalid compressed data--format violated}); el respaldo se
 * marcaba completado pero no se podía restaurar.</p>
 *
 * <p>Invariante: el respaldo programado tiene un solo dueño,
 * {@link BackupScheduler}. {@link ProgramadorTareas} no debe volver a declarar
 * un job de backup.</p>
 */
class BackupSchedulingTest {

    @Test
    void programadorTareasNoDeclaraNingunJobDeBackup() {
        long jobs = Arrays.stream(ProgramadorTareas.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .filter(m -> m.getName().toLowerCase().contains("backup"))
                .count();

        assertThat(jobs)
                .as("ProgramadorTareas no puede volver a programar un backup: "
                        + "BackupScheduler es el unico dueño")
                .isZero();
    }

    @Test
    void elMetodoLegadoEjecutarBackupProgramadoYaNoExiste() {
        assertThatThrownBy(() ->
                ProgramadorTareas.class.getDeclaredMethod("ejecutarBackupProgramado"))
                .as("el scheduler legado duplicado debe seguir eliminado")
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void backupSchedulerEsElUnicoQueProgramaElRespaldo() {
        long jobs = Arrays.stream(BackupScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .filter(m -> m.getName().contains("respaldoAutomatico"))
                .count();

        assertThat(jobs)
                .as("BackupScheduler.respaldoAutomatico debe ser el unico job de respaldo")
                .isEqualTo(1);
    }
}
