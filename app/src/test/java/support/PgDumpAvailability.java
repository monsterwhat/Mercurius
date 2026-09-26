package support;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

/**
 * Precondición para las pruebas que dependen del binario cliente
 * {@code pg_dump}.
 *
 * <p>Los binarios del SERVIDOR PostgreSQL (initdb/postgres/pg_ctl) y los
 * binarios CLIENTE ({@code pg_dump}, {@code pg_restore}) se distribuyen por
 * separado, así que un entorno puede tener la base de datos de pruebas
 * funcionando y aun así no poder ejecutar un respaldo real. Cuando eso pasa,
 * la prueba se marca como OMITIDA con {@link Assumptions} en vez de fallar:
 * un rojo por falta de herramienta hides el estado real del código, mientras
 * que un "skip" la declara con honestidad.
 *
 * <p>En una máquina con PostgreSQL completo (la del proyecto, ver
 * README → Pruebas) {@code pg_dump} está en PATH o en
 * {@code %ProgramFiles%\PostgreSQL\<n>\bin}, así que la precondición se
 * cumple y la prueba corre de verdad contra {@code mercurius_test}.
 *
 * <p>La búsqueda replica la misma estrategia de
 * {@code Services.BackupService#resolvePgDump()} —override explícito, PATH y
 * rutas conocidas— para que la precondición coincida con lo que el servicio
 * realmente resuelve.
 */
public final class PgDumpAvailability {

    private PgDumpAvailability() {
    }

    /** True when a usable {@code pg_dump} can be located and executed. */
    public static boolean isAvailable() {
        String override = System.getenv("BACKUP_PGDUMP_PATH");
        if (override != null && !override.isBlank() && new File(override).exists()) {
            return true;
        }
        if (runsOnPath()) {
            return true;
        }
        for (String candidate : knownLocations()) {
            if (new File(candidate).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Skips the calling test when {@code pg_dump} is absent. Call it first
     * statement of any test that triggers a real dump.
     */
    public static void assumeAvailable() {
        Assumptions.assumeTrue(isAvailable(),
                "pg_dump no está disponible en este entorno: se omite la prueba de "
                        + "respaldo real (instale PostgreSQL completo o defina "
                        + "BACKUP_PGDUMP_PATH)");
    }

    private static boolean runsOnPath() {
        try {
            Process test = new ProcessBuilder("pg_dump", "--version").redirectErrorStream(true).start();
            int code = test.waitFor();
            test.getInputStream().close();
            return code == 0;
        } catch (Exception ignored) {
            // Sondeo de disponibilidad: cualquier fallo significa "no está en PATH".
            return false;
        }
    }

    private static List<String> knownLocations() {
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String[] roots = { System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)") };
            for (String root : roots) {
                if (root == null) {
                    continue;
                }
                for (int version = 18; version >= 13; version--) {
                    candidates.add(root + "\\PostgreSQL\\" + version + "\\bin\\pg_dump.exe");
                }
            }
        } else {
            candidates.add("/usr/bin/pg_dump");
            candidates.add("/usr/local/bin/pg_dump");
            candidates.add("/opt/homebrew/bin/pg_dump");
            for (int version = 18; version >= 13; version--) {
                candidates.add("/usr/lib/postgresql/" + version + "/bin/pg_dump");
            }
        }
        return candidates;
    }
}
