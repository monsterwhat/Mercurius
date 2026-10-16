import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class MigrateInvoices {
    static boolean dryRun;
    static int limit = 0;

    static long nextId(Connection c, String seq) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT nextval('" + seq + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static void main(String[] args) throws Exception {
        String dbUrl = getenv("DB_URL", "jdbc:postgresql://localhost:5434/mercurius");
        String dbUser = getenv("DB_USERNAME", "mercurius");
        String dbPass = getenv("DB_PASSWORD", "Mercurius@1!");
        String facturasDbf = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/facturas.DBF";
        String detaDbf = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/detafact.dbf";
        String clientesDbf = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/CLIENTES.DBF";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--limit")) limit = Integer.parseInt(args[++i]);
            else if (args[i].equals("--dry-run")) dryRun = true;
        }

        DbfReader facturasR = new DbfReader(facturasDbf);
        DbfReader detaR = new DbfReader(detaDbf);
        DbfReader clientesR = new DbfReader(clientesDbf);

        // Map legacy client code -> Mercurius name.
        Map<String,String> legacyClientName = new HashMap<>();
        for (DbfReader.Row r : customers(clientesR)) legacyClientName.put(str(r.get("NUMERO")), safe(r.get("NOMBRE")));
        Map<String,String> oldClientName = new HashMap<>();
        for (DbfReader.Row r : customers(clientesR)) oldClientName.put(str(r.get("NUMERO")), str(r.get("NOMBRE")));

        Map<String,Integer> clientCodeByName = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT code,name FROM clients")) {
                while (rs.next()) {
                    clientCodeByName.putIfAbsent(rs.getString(2), rs.getInt(1));
                }
            }
        }

        // Group lines by legacy FACTURA number.
        Map<Integer,List<DbfReader.Row>> linesByInvoice = new HashMap<>();
        for (DbfReader.Row line : detaR.rows) {
            int key = num(line.get("FACTURA"));
            linesByInvoice.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }

        int imported = 0;
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            c.setAutoCommit(false);
            int processed = 0;
            for (DbfReader.Row inv : facturasR.rows) {
                if (limit > 0 && processed >= limit) break;
                int invNo = num(inv.get("NUMERO"));
                if (invNo == 0) { processed++; continue; }
                String cons = String.valueOf(invNo);
                if (exists(c, "SELECT 1 FROM encabezado WHERE numero_consecutivo = ?", cons)) { processed++; continue; }

                String oldClient = str(inv.get("CLIENTE"));
                String clientName = oldClientName.get(oldClient);
                Integer clientId = clientName == null ? null : clientCodeByName.get(clientName);

                String name = str(inv.get("NOMBRE"));
                String oldState = str(inv.get("ESTADO"));
                String estado = oldState.isBlank() ? "ACEPTADO" : oldState;

                LocalDate fecha = localDate(inv.get("FECHA"));
                if (fecha == null) fecha = LocalDate.now();
                if (dryRun) {
                    if (processed < 5) System.out.println("DRY " + processed + " invoice " + invNo + " customer " + name);
                    processed++;
                    if (processed <= 10 || processed % 2500 == 0) System.out.println("PROCESSED " + processed + " invoice# " + invNo);
                    if (limit > 0 && processed >= limit) break;
                    continue;
                }

                long idEnc = nextId(c, "encabezado_id_seq");
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO encabezado (id, fecha_emision, numero_consecutivo, estado, plazo_credito, schemaversion) VALUES (?,?,?,?,?,?)")) {
                    ps.setLong(1, idEnc);
                    ps.setObject(2, fecha.atStartOfDay());
                    ps.setString(3, cons);
                    ps.setString(4, estado);
                    ps.setString(5, str(inv.get("NPLAZOCRED")).trim());
                    ps.setString(6, "4.4");
                    ps.executeUpdate();
                }

                List<DbfReader.Row> lines = linesByInvoice.getOrDefault(invNo, List.of());
                int lineNo = 1;

                // insert resumen
                long idRes = nextId(c, "resumen_factura_id_seq");
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO resumen_factura (id, total_comprobante, total_descuentos, total_exento, total_gravado, total_impuesto, total_venta, total_venta_neta, schemaversion) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, idRes);
                    ps.setBigDecimal(2, bd(inv.get("TOTAL")));
                    ps.setBigDecimal(3, bd(inv.get("DESCUENTO")));
                    ps.setBigDecimal(4, bd(inv.get("TOTALEXENT")));
                    ps.setBigDecimal(5, bd(inv.get("TOTALNOEXE")));
                    ps.setBigDecimal(6, bd(inv.get("I_VENTAS")));
                    ps.setBigDecimal(7, bd(inv.get("SUBTOTAL")));
                    ps.setBigDecimal(8, bd(inv.get("NSALDO")));
                    ps.setString(9, "4.4");
                    ps.executeUpdate();
                }

                long idCe = nextId(c, "comprobantesemitidos_id_seq");
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO comprobantesemitidos (id, status, encabezado_id, resumen_id, haciendaclave, haciendaestado, schemaversion, usuario) VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, idCe);
                    ps.setBoolean(2, true);
                    ps.setLong(3, idEnc);
                    ps.setLong(4, idRes);
                    ps.setString(5, blank(inv.get("CIDDOCUMEN")));
                    ps.setString(6, blank(inv.get("CSTATUSHDA")));
                    ps.setString(7, "4.4");
                    ps.setString(8, blank(inv.get("USUARIO")));
                    ps.executeUpdate();
                }

                // detail service container
                long idDetail = nextId(c, "detalle_servicio_id_seq");
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO detalle_servicio (id, status, schemaversion) VALUES (?,?,?)")) {
                    ps.setLong(1, idDetail);
                    ps.setBoolean(2, true);
                    ps.setString(3, "4.4");
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement("UPDATE comprobantesemitidos SET detalle_servicio_id=? WHERE id=?")) {
                    ps.setLong(1, idDetail);
                    ps.setLong(2, idCe);
                    ps.executeUpdate();
                }

                for (DbfReader.Row line : lines) {
                    int prodId = num(line.get("NNUMELINEA"));
                    int artKey = num(line.get("CODIGO"));
                    int lineno = (prodId > 0 ? prodId : lineNo);
                    lineNo++;
                    java.math.BigDecimal qty = bd(line.get("CANTIDAD"));
                    java.math.BigDecimal unit = bd(line.get("VALORUNITA"));
                    java.math.BigDecimal total = bd(line.get("TOTAL"));
                    java.math.BigDecimal subtotal = bd(line.get("NVENTAGRAV")).add(bd(line.get("NVENTAEXEN")));
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO linea_detalle (numero_linea, detalle, codigocabys, cantidad, precio_unitario, monto_total, monto_total_de_linea, sub_total, detalle_servicio_id, schemaversion) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                        ps.setInt(1, lineno);
                        ps.setString(2, safe(line.get("DESCRIP")));
                        ps.setString(3, blank(line.get("CODIGOCABY")));
                        ps.setBigDecimal(4, qty);
                        ps.setBigDecimal(5, unit);
                        ps.setBigDecimal(6, total);
                        ps.setBigDecimal(7, total);
                        ps.setBigDecimal(8, subtotal);
                        ps.setLong(9, idDetail);
                        ps.setString(10, "4.4");
                        ps.executeUpdate();
                    }
                    // Look up product code silently; no action required for FK link
                }

                // FE docs will patch hacienda fields in a later pass if going beyond this
                processed++;
                if (processed <= 10 || processed % 2500 == 0) System.out.println("PROCESSED " + processed + " invoice# " + invNo);
                if (limit > 0 && processed >= limit) break;
            }
            c.commit();
            System.out.println("IMPORTED_INVOICES=" + processed);
        }
    }

    static boolean exists(Connection c, String sql, String v) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, v);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    static String getenv(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    static int num(Object v) { if (v == null) return 0; String s = v.toString().trim(); if (s.isBlank()) return 0; try { return (int) Double.parseDouble(s); } catch (Exception e) { return 0; } }
    static String str(Object v) { return v == null ? "" : v.toString().trim(); }
    static String safe(Object v) { String s = str(v); return s.isBlank() ? "SIN DETALLE" : s; }
    static String blank(Object v) { String s = str(v); return s.isBlank() ? null : s; }
    static java.math.BigDecimal bd(Object v) {
        if (v == null) return java.math.BigDecimal.ZERO;
        String s = v.toString().trim();
        if (s.isBlank()) return java.math.BigDecimal.ZERO;
        try { return new java.math.BigDecimal(s); } catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }
    static LocalDate localDate(Object v) {
        String s = str(v);
        if (s.isBlank()) return null;
        try {
            if (s.length() == 8 && s.chars().allMatch(Character::isDigit)) {
                return LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)), Integer.parseInt(s.substring(6, 8)));
            }
        } catch (Exception ignored) {}
        return null;
    }

    static List<DbfReader.Row> customers(DbfReader dbf) { return dbf.rows; }

    static class DbfReader {
        List<Row> rows = new ArrayList<>();
        DbfReader(String path) throws Exception {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(path, "r")) {
                byte[] header = new byte[32];
                f.readFully(header);
                int numRecords = readInt(header,4);
                int headerSize = readShort(header, 8);
                int recordSize = readShort(header, 10);
                f.seek(32);
                List<Field> fields = new ArrayList<>();
                while (true) {
                    byte[] raw = new byte[32];
                    f.readFully(raw);
                    if (raw[0] == 0x0D) break;
                    if (raw[0] == 0x00) break;
                    String name = new String(raw, 0, 11, StandardCharsets.ISO_8859_1).split("\0")[0].trim();
                    String type = new String(raw, 11, 1, StandardCharsets.ISO_8859_1);
                    int len = raw[16] & 0xFF;
                    fields.add(new Field(name, type, len));
                }
                for (int i = 0; i < numRecords; i++) {
                    long off = headerSize + (long) i * recordSize;
                    f.seek(off);
                    byte[] record = new byte[recordSize];
                    f.readFully(record);
                    if (record[0] == (byte) 0x2A) continue;
                    Map<String, Object> data = new LinkedHashMap<>();
                    int pos = 1;
                    for (Field fld : fields) {
                        String val = new String(record, pos, fld.len, StandardCharsets.ISO_8859_1);
                        pos += fld.len;
                        if (fld.type.equals("N")) {
                            val = val.trim();
                            data.put(fld.name, val.isBlank() ? "0" : val);
                        } else if (fld.type.equals("D")) {
                            val = val.trim();
                            data.put(fld.name, val);
                        } else {
                            data.put(fld.name, val.trim());
                        }
                    }
                    rows.add(new Row(data));
                }
            }
        }

        int readInt(byte[] b, int off) {
            return ((b[off] & 0xFF)) | ((b[off+1] & 0xFF) << 8) | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
        }
        int readShort(byte[] b, int off) {
            return ((b[off] & 0xFF)) | ((b[off+1] & 0xFF) << 8);
        }

        static class Field { String name, type; int len; Field(String a,String b,int c){name=a;type=b;len=c;} }
        static class Row { final Map<String,Object> d; Row(Map<String,Object> x){d=x;} Object get(String k){return d.get(k);} }
    }
}
