import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Idempotent first-pass restore for the vSuper catalog sources (families, articulos, clients).
 *
 * This migrates ONLY reviewable master-data tables. Invoice headers, line items and
 * stock history should stay in a separate script so they can be rehearsed first.
 *
 * Usage:
 *   java -cp "postgresql-42.x.jar" vsuper-import-catalogs \
 *        --csv "D:/Documents/Facturas Electronicas/vSuper/csv" \
 *        --db "jdbc:postgresql://localhost:5434/mercurius" \
 *        --user "mercurius" --password "Mercurius@1!"
 */
class vsuper_import_catalogs {
    static String csvDir;
    static String dbUrl;
    static String dbUser;
    static String dbPass;

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            c.setAutoCommit(false);
            System.out.println("Families ensured: " + insertFamilies(c));
            System.out.println("Articulos inserted: " + insertArticulos(c));
            System.out.println("Clients inserted: " + insertClients(c));
            c.commit();
            System.out.println("DONE (catalog import)");
        }
    }

    static void parseArgs(String[] args) {
        csvDir = env("VSUPER_CSV_DIR", "D:/Documents/Facturas Electronicas/vSuper/csv");
        dbUrl = env("DB_URL", "jdbc:postgresql://localhost:5434/mercurius");
        dbUser = env("DB_USERNAME", "mercurius");
        dbPass = env("DB_PASSWORD", "Mercurius@1!");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--csv")) csvDir = args[++i];
            else if (args[i].equals("--db")) dbUrl = args[++i];
            else if (args[i].equals("--user")) dbUser = args[++i];
            else if (args[i].equals("--password")) dbPass = args[++i];
        }
    }

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    static List<Map<String, String>> readCsv(String name) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(csvDir, name), StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        String[] headers = lines.get(0).split(",", -1);
        headers[0] = headers[0].replace("\ufeff", "");
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] vals = lines.get(i).split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.length; j++) {
                row.put(headers[j], j < vals.length ? vals[j] : "");
            }
            out.add(row);
        }
        return out;
    }

    static int insertFamilies(Connection c) throws Exception {
        int inserted = 0;
        for (Map<String, String> r : readCsv("familias.csv")) {
            String name = trim(r.get("NOMBRE"));
            if (name == null) continue;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM familia WHERE nombre = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) continue;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO familia (nombre, status, fecha) VALUES (?,?,?)")) {
                ps.setString(1, name);
                ps.setBoolean(2, true);
                ps.setObject(3, LocalDateTime.now());
                ps.executeUpdate();
                inserted++;
            }
        }
        return inserted;
    }

    static Map<String, Integer> familyIds(Connection c) throws Exception {
        Map<String, Integer> out = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id,nombre FROM familia")) {
            while (rs.next()) out.put(rs.getString(2), rs.getInt(1));
        }
        return out;
    }

    static int insertArticulos(Connection c) throws Exception {
        Map<String, Integer> fam = familyIds(c);
        int inserted = 0;
        for (Map<String, String> r : readCsv("maestro.csv")) {
            String name = trim(r.get("NOMBRE"));
            if (name == null) continue;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM articulos WHERE nombre = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) continue; }
            }
            Integer familiaId = fam.get(trim(r.get("FAMILIA")));
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO articulos (nombre, descripcion, exento, familia_id, codigobarra, fecha, status, version) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, name);
                ps.setBoolean(3, "S".equalsIgnoreCase(r.get("EXENTO")) || "SI".equalsIgnoreCase(r.get("EXENTO")));
                if (familiaId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, familiaId);
                ps.setString(5, trim(r.get("CODIGO")));
                ps.setObject(6, LocalDateTime.now());
                ps.setBoolean(7, true);
                ps.setLong(8, 0L);
                ps.executeUpdate();
                inserted++;
            } catch (Exception e) {
                System.out.println("Articulo skipped: " + name + " -> " + e.getMessage());
            }
        }
        return inserted;
    }

    static int insertClients(Connection c) throws Exception {
        int inserted = 0;
        for (Map<String, String> r : readCsv("clientes.csv")) {
            String name = trim(r.get("NOMBRE"));
            if (name == null) continue;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM clients WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) continue; }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO clients (name, tipoidentificacion, idnumber, address, email, phonenumber, taxpayer, status, codigoactividadcomercial) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, trim(r.get("CTIPOIDENT")));
                ps.setString(3, first(r.get("IDENTIFAC"), r.get("CNUMIDENTI")));
                ps.setString(4, trim(r.get("DIRECCION")));
                ps.setString(5, trim(r.get("EMAIL")));
                ps.setString(6, trim(r.get("TELEFONO")));
                ps.setBoolean(7, "S".equalsIgnoreCase(r.get("OBLIGADO")));
                ps.setBoolean(8, true);
                ps.setString(9, trim(r.get("CACTIVIDAD")));
                ps.executeUpdate();
                inserted++;
            } catch (Exception e) {
                System.out.println("Client skipped: " + name + " -> " + e.getMessage());
            }
        }
        return inserted;
    }

    static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    static String first(String a, String b) { if (a != null && !a.isBlank()) return a.trim(); if (b != null && !b.isBlank()) return b.trim(); return null; }
}
