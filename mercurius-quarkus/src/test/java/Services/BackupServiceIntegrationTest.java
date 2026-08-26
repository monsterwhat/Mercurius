package Services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import Models.AppSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración real del subsistema de respaldos tras el swap mysqldump →
 * pg_dump, contra la base de datos de pruebas local mercurius_test
 * (localhost:5433, sin Docker — mismas credenciales que el perfil %test ya
 * usa; este test NUNCA menciona la contraseña: la resuelve el propio
 * {@link BackupService} a partir de quarkus.datasource.*).
 *
 * <p>Ejecuta pg_dump de verdad (binario resuelto por resolvePgDump(), con la
 * contraseña inyectada vía PGPASSWORD en el entorno del proceso) y valida el
 * artefacto con la convención intacta mercurius_*.sql.gz.</p>
 *
 * <p>Igual que en AppSettingsServiceIntegrationTest, todo corre dentro de una
 * {@link TestTransaction} que se revierte al final; los efectos en disco NO
 * se revierten, por eso el directorio temporal se borra en @AfterEach con
 * recibo de borrado.</p>
 *
 * <p>Escenario único: ejecutarBackup() produce exactamente un
 * mercurius_*.sql.gz no vacío cuyo contenido descomprimido lleva el marcador
 * "PostgreSQL database dump" del formato SQL plano, y listarBackups() (la
 * fuente de BackupController) lo ve.</p>
 */
@QuarkusTest
@TestTransaction
@Tag("integration-services")
class BackupServiceIntegrationTest {

    @Inject
    BackupService backupService;

    @Inject
    AppSettingsService appSettingsService;

    private Path tempDir;

    @BeforeEach
    void createTempBackupDir() throws IOException {
        tempDir = Files.createTempDirectory("debt-pgdump-it");
    }

    /**
     * Recibo de limpieza: borra el directorio temporal completo (si existe) y
     * deja constancia en el log de cada archivo eliminado.
     */
    @AfterEach
    void deleteTempBackupDirWithReceipt() {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    System.out.println("[cleanup receipt] deleted: " + p);
                } catch (IOException e) {
                    System.out.println("[cleanup receipt] FAILED deleting: " + p
                            + " (" + e.getMessage() + ")");
                }
            });
        } catch (IOException e) {
            System.out.println("[cleanup receipt] FAILED walking temp dir: " + e.getMessage());
        }
    }

    @Test
    void ejecutarBackupProducesNonEmptyGzippedPlainSqlDumpWithPgDumpMarker() throws IOException {
        AppSettings settings = appSettingsService.findOrCreateCurrent();
        assertNotNull(settings, "findOrCreateCurrent debe devolver una fila activa");
        settings.setBackupRuta(tempDir.toString());
        appSettingsService.update(settings);

        assertTrue(backupService.ejecutarBackup(),
                "ejecutarBackup debe tener éxito contra mercurius_test real");

        List<Path> produced;
        try (Stream<Path> listing = Files.list(tempDir)) {
            produced = listing
                    .filter(p -> p.getFileName().toString().startsWith("mercurius_")
                            && p.getFileName().toString().endsWith(".sql.gz"))
                    .toList();
        }
        assertEquals(1, produced.size(),
                "debe producirse exactamente un artefacto mercurius_*.sql.gz");
        Path dumpFile = produced.get(0);
        assertTrue(Files.size(dumpFile) > 0, "el archivo de backup debe ser no vacío");

        String plainSql;
        try (InputStream gz = new GZIPInputStream(Files.newInputStream(dumpFile))) {
            plainSql = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(plainSql.contains("PostgreSQL database dump"),
                "el volcado SQL plano debe llevar el marcador de cabecera de pg_dump");

        List<String> listed = backupService.listarBackups();
        assertTrue(listed.stream()
                        .anyMatch(entry -> entry.startsWith(dumpFile.getFileName().toString() + "|")),
                "listarBackups (fuente de BackupController) debe ver el artefacto producido");
    }
}
