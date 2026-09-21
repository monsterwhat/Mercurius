package Models.DTO;

import jakarta.annotation.Nullable;
import java.time.LocalDateTime;

/**
 * Estado público del subsistema de respaldos para las vistas bajo
 * META-INF/resources/secured/pages/Ajustes/Backups/**. Refleja lo que
 * Services.BackupService expone públicamente:
 *
 * - backupUltimoEjecutado: fecha del último backup exitoso
 *   (AppSettings.backupUltimoEjecutado, actualizado por BackupService.ejecutarBackup()).
 * - backupHabilitado: bandera de programación automática
 *   (AppSettings.backupHabilitado, consumida por ProgramadorTareas.ejecutarBackupProgramado()).
 * - mysqldumpResuelto: true si BackupService.resolvePgDump() encontró una ruta
 *   concreta al ejecutable pg_dump; false si cayó al fallback por defecto.
 *   El nombre del campo se conserva por estabilidad de la superficie JSON
 *   (Jackson) aunque el motor de respaldos ahora es pg_dump.
 */
public class BackupStatusDTO {

    @Nullable
    private LocalDateTime backupUltimoEjecutado; // Fecha del ultimo backup exitoso ("Nunca" si null)

    private boolean backupHabilitado; // Backup automatico programado habilitado

    private boolean mysqldumpResuelto; // Ruta concreta de pg_dump resuelta (no fallback); nombre histórico conservado por compatibilidad JSON

    public BackupStatusDTO() {
    }

    public BackupStatusDTO(@Nullable LocalDateTime backupUltimoEjecutado,
                           boolean backupHabilitado, boolean mysqldumpResuelto) {
        this.backupUltimoEjecutado = backupUltimoEjecutado;
        this.backupHabilitado = backupHabilitado;
        this.mysqldumpResuelto = mysqldumpResuelto;
    }

    @Nullable
    public LocalDateTime getBackupUltimoEjecutado() {
        return backupUltimoEjecutado;
    }

    public void setBackupUltimoEjecutado(@Nullable LocalDateTime backupUltimoEjecutado) {
        this.backupUltimoEjecutado = backupUltimoEjecutado;
    }

    public boolean isBackupHabilitado() {
        return backupHabilitado;
    }

    public void setBackupHabilitado(boolean backupHabilitado) {
        this.backupHabilitado = backupHabilitado;
    }

    public boolean isMysqldumpResuelto() {
        return mysqldumpResuelto;
    }

    public void setMysqldumpResuelto(boolean mysqldumpResuelto) {
        this.mysqldumpResuelto = mysqldumpResuelto;
    }
}
