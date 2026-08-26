package Services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias puras (sin Quarkus) para
 * {@link BackupService#buildDumpCommand(String, BackupService.DbConnectionInfo, String)}.
 *
 * <p>El helper es estático y package-private precisamente para poder probar
 * aquí la construcción del comando pg_dump sin levantar contexto de CDI ni
 * tocar la base de datos.</p>
 *
 * <p>Escenarios (4): comando completo ordenado; presencia y orden de los
 * flags requeridos (-h/-p/-U/-d/-F p/-f); ausencia total de la contraseña en
 * cualquier argumento; propagación de un puerto no por defecto.</p>
 */
class BackupServiceBuildDumpCommandTest {

    private static final String FAKE_PG_DUMP = "C:/fake/instalacion/pg_dump.exe";

    /**
     * Centinela SOLO para verificar ausencia. El contrato real es más fuerte:
     * buildDumpCommand no recibe ninguna contraseña como parámetro (viaja por
     * la variable de entorno PGPASSWORD que ejecutarBackup() inyecta al
     * ProcessBuilder), así que ninguna ruta de código puede colarla aquí.
     */
    private static final String SECRET_SENTINEL = "S3cret-Sentinel-P@ss";

    private BackupService.DbConnectionInfo dbInfo(int port) {
        BackupService.DbConnectionInfo info = new BackupService.DbConnectionInfo();
        info.host = "localhost";
        info.port = port;
        info.dbName = "mercurius_test";
        return info;
    }

    /**
     * Escenario 1 — el comando completo, en orden exacto, con los hechos de
     * conexión derivados del DbConnectionInfo y formato SQL plano a stdout.
     */
    @Test
    void buildsFullOrderedPgDumpCommand() {
        List<String> cmd = BackupService.buildDumpCommand(FAKE_PG_DUMP, dbInfo(5433), "mercurius");

        assertEquals(List.of(
                FAKE_PG_DUMP,
                "-h", "localhost",
                "-p", "5433",
                "-U", "mercurius",
                "-d", "mercurius_test",
                "-F", "p",
                "-f", "-",
                "--clean"
        ), cmd, "el comando debe ser exactamente este, en este orden");
    }

    /**
     * Escenario 2 — los flags requeridos existen, en el orden relativo
     * esperado, con sus valores adyacentes correctos.
     */
    @Test
    void requiredFlagsAppearInRelativeOrderWithAdjacentValues() {
        List<String> cmd = BackupService.buildDumpCommand(FAKE_PG_DUMP, dbInfo(5433), "mercurius");

        assertTrue(cmd.contains("-h"), "falta -h");
        assertTrue(cmd.contains("-p"), "falta -p (puerto)");
        assertTrue(cmd.contains("-U"), "falta -U (usuario)");
        assertTrue(cmd.contains("-d"), "falta -d (base de datos)");
        assertTrue(cmd.contains("-F"), "falta -F (formato)");
        assertTrue(cmd.contains("-f"), "falta -f (archivo/salida)");

        int h = cmd.indexOf("-h");
        int p = cmd.indexOf("-p");
        int u = cmd.indexOf("-U");
        int d = cmd.indexOf("-d");
        int f = cmd.indexOf("-F");
        int file = cmd.indexOf("-f");
        assertTrue(h < p && p < u && u < d && d < f && f < file,
                "orden relativo esperado: -h < -p < -U < -d < -F < -f");

        assertEquals("localhost", cmd.get(h + 1), "-h debe ir seguido del host");
        assertEquals("5433", cmd.get(p + 1), "-p debe ir seguido del puerto");
        assertEquals("mercurius", cmd.get(u + 1), "-U debe ir seguido del usuario");
        assertEquals("mercurius_test", cmd.get(d + 1), "-d debe ir seguido de la base");
        assertEquals("p", cmd.get(f + 1), "-F debe ir seguido de 'p' (SQL plano)");
        assertEquals("-", cmd.get(file + 1), "-f debe ir seguido de '-' (stdout para el gzip)");

        assertEquals(FAKE_PG_DUMP, cmd.get(0), "el binario resuelto va primero");
    }

    /**
     * Escenario 3 — la contraseña jamás aparece en el comando: ni como
     * argumento, ni incrustada en uno (el estilo viejo de mysqldump era
     * "-p&lt;pass&gt;", visible en la lista de procesos del SO).
     */
    @Test
    void passwordNeverAppearsAnywhereInTheCommand() {
        List<String> cmd = BackupService.buildDumpCommand(FAKE_PG_DUMP, dbInfo(5433), "mercurius");

        for (String arg : cmd) {
            assertFalse(arg.contains(SECRET_SENTINEL),
                    "ningún argumento puede contener la contraseña");
        }
        assertFalse(String.join(" ", cmd).contains(SECRET_SENTINEL),
                "la contraseña no puede aparecer en el comando unido");

        // Blindaje estructural: el único uso legítimo de "-p" es el flag de
        // puerto seguido de dígitos; nunca "-p<secreto>" pegado.
        for (int i = 0; i < cmd.size(); i++) {
            if (cmd.get(i).startsWith("-p") && !cmd.get(i).equals("-p")) {
                throw new AssertionError(
                        "argumento sospechoso estilo mysqldump -p<valor>: " + cmd.get(i));
            }
        }
    }

    /**
     * Escenario 4 — un puerto no por defecto se propaga tal cual al flag -p.
     */
    @Test
    void nonDefaultPortIsCarriedIntoThePortFlag() {
        List<String> cmd = BackupService.buildDumpCommand(FAKE_PG_DUMP, dbInfo(6544), "mercurius");

        int p = cmd.indexOf("-p");
        assertEquals("6544", cmd.get(p + 1),
                "el puerto parseado del JDBC URL debe llegar intacto a -p");
    }
}
