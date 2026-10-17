import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class MigrateHistoricalInvoices {
    static String facturasPath, detaPath, clientesPath;
    static boolean dryRun;
    static int limit = 0;

    public static void main(String[] args) throws Exception {
        String dbUrl = getenv("DB_URL", "jdbc:postgresql://localhost:5434/mercurius");
        String dbUser = getenv("DB_USERNAME", "mercurius");
        String dbPass = getenv("DB_PASSWORD", "Mercurius@1!");
        facturasPath = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/facturas.DBF";
        detaPath = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/detafact.dbf";
        clientesPath = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/CLIENTES.DBF";
        for (int i=0;i<args.length;i++) {
            if (args[i].equals("--facturas")) facturasPath = args[++i];
            else if (args[i].equals("--detafact")) detaPath = args[++i];
            else if (args[i].equals("--clientes")) clientesPath = args[++i];
            else if (args[i].equals("--limit")) limit = Integer.parseInt(args[++i]);
            else if (args[i].equals("--dry-run")) dryRun = true;
        }

        DbfReader facturasR = new DbfReader(facturasPath);
        DbfReader detaR = new DbfReader(detaPath);
        DbfReader clientesR = new DbfReader(clientesPath);

        Map<String,String> oldClientName = new HashMap<>();
        for (DbfReader.Row r : clientesR.rows) oldClientName.put(str(r.get("NUMERO")), str(r.get("NOMBRE")));

        Map<String,Integer> clientCodeByName = new HashMap<>();
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT code,name FROM clients")) {
                while (rs.next()) clientCodeByName.putIfAbsent(rs.getString(2), rs.getInt(1));
            }
        }

        Map<String,Long> productByBarcode = new HashMap<>();
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT codigo,codigobarra FROM articulos")) {
                while (rs.next()) if (rs.getString(2) != null) productByBarcode.put(rs.getString(2), rs.getLong(1));
            }
        }

        Map<Integer,List<DbfReader.Row>> linesByInvoice = new HashMap<>();
        for (DbfReader.Row line : detaR.rows) {
            int key = num(line.get("FACTURA"));
            linesByInvoice.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }

        int processed = 0;
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            c.setAutoCommit(false);
            for (DbfReader.Row inv : facturasR.rows) {
                if (limit > 0 && processed >= limit) break;
                int invNo = num(inv.get("NUMERO"));
                if (invNo == 0) { processed++; continue; }
                String cons = String.valueOf(invNo);
                if (exists(c, "SELECT 1 FROM encabezado WHERE numero_consecutivo = ?", cons)) { processed++; continue; }

                String oldClient = str(inv.get("CLIENTE"));
                String clientName = oldClientName.get(oldClient);
                if (clientName == null || clientName.isBlank()) clientName = str(inv.get("NOMBRE"));
                Integer clientId = clientName == null || clientName.isBlank() ? null : clientCodeByName.get(clientName);

                String oldState = str(inv.get("ESTADO"));
                String estado = oldState.isBlank() ? "ACEPTADO" : oldState;
                LocalDate fecha = localDate(inv.get("FECHA"));
                if (fecha == null) fecha = LocalDate.now();

                if (dryRun) {
                    if (processed < 5) System.out.println("DRY " + invNo + " " + clientName);
                    processed++;
                    if (processed % 2500 == 0) System.out.println("PROCESSED " + processed);
                    continue;
                }

                long idEnc = nextId(c, "encabezado_id_seq");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO encabezado (id, fecha_emision, numero_consecutivo, estado, plazo_credito, schemaversion) VALUES (?,?,?,?,?,?)")) {
                    ps.setLong(1, idEnc);
                    ps.setObject(2, fecha.atStartOfDay());
                    ps.setString(3, cons);
                    ps.setString(4, estado);
                    ps.setString(5, str(inv.get("NPLAZOCRED")).trim());
                    ps.setString(6, "4.4");
                    ps.executeUpdate();
                }
                long idRes = nextId(c, "resumen_factura_id_seq");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO resumen_factura (id, total_comprobante, total_descuentos, total_exento, total_gravado, total_impuesto, total_venta, total_venta_neta, schemaversion) VALUES (?,?,?,?,?,?,?,?,?)")) {
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
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO comprobantesemitidos (id, status, encabezado_id, resumen_id, haciendaclave, haciendaestado, schemaversion, usuario) VALUES (?,?,?,?,?,?,?,?)")) {
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
                long idDetail = nextId(c, "detalle_servicio_id_seq");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO detalle_servicio (id, status, schemaversion) VALUES (?,?,?)")) {
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

                List<DbfReader.Row> lines = linesByInvoice.getOrDefault(invNo, List.of());
                int lineNo = 1;
                for (DbfReader.Row line : lines) {
                    int lineno = num(line.get("NNUMELINEA"));
                    if (lineno <= 0) lineno = lineNo;
                    lineNo++;
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO linea_detalle (numero_linea, detalle, codigocabys, cantidad, precio_unitario, monto_total, monto_total_de_linea, sub_total, detalle_servicio_id, schemaversion) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                        ps.setInt(1, lineno);
                        ps.setString(2, safe(line.get("DESCRIP")));
                        ps.setString(3, blank(line.get("CODIGOCABY")));
                        ps.setBigDecimal(4, bd(line.get("CANTIDAD")));
                        ps.setBigDecimal(5, bd(line.get("VALORUNITA")));
                        ps.setBigDecimal(6, bd(line.get("TOTAL")));
                        ps.setBigDecimal(7, bd(line.get("TOTAL")));
                        ps.setBigDecimal(8, bd(line.get("NVENTAGRAV")).add(bd(line.get("NVENTAEXEN"))));
                        ps.setLong(9, idDetail);
                        ps.setString(10, "4.4");
                        ps.executeUpdate();
                    }
                }
                processed++;
                if (processed <= 5 || processed % 5000 == 0) System.out.println("PROCESSED " + processed + " invoice# " + invNo);
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
    static long nextId(Connection c, String seq) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT nextval('" + seq + "')")) {
            rs.next();
            return rs.getLong(1);
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

    static class DbfReader {
        List<Row> rows = new ArrayList<>();
        DbfReader(String path) throws Exception {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(path, "r")) {
                byte[] h = new byte[32]; f.readFully(h);
                int n = readInt(h,4);
                int hs = (h[8]&0xFF)|((h[9]&0xFF)<<8);
                int rs = (h[10]&0xFF)|((h[11]&0xFF)<<8);
                f.seek(32); List<Field> fields=new ArrayList<>();
                while (true) {
                    byte[] raw = new byte[32]; int got=f.read(raw); if (got < 32) break;
                    if (raw[0] == 0x0D || raw[0] == 0x00) break;
                    String name = new String(raw,0,11,"ISO-8859-1").split("\0")[0].trim();
                    String type = new String(raw,11,1,"ISO-8859-1");
                    int len = raw[16] & 0xFF;
                    if (!name.isEmpty()) fields.add(new Field(name,type,len));
                }
                for (int i = 0; i < n; i++) {
                    long off = hs + (long) i * rs;
                    f.seek(off);
                    byte[] rec = new byte[rs];
                    int got = f.read(rec);
                    if (got < rs) break;
                    if (rec[0] == (byte)0x2A) continue;
                    Map<String,Object> m = new LinkedHashMap<>();
                    int p = 1;
                    for (Field fld : fields) {
                        String val = new String(rec,p,fld.len,"ISO-8859-1").trim();
                        p += fld.len;
                        m.put(fld.name, val);
                    }
                    rows.add(new Row(m));
                }
            }
        }
        int readInt(byte[] b,int o){ return (b[o]&0xFF)|((b[o+1]&0xFF)<<8)|((b[o+2]&0xFF)<<16)|((b[o+3]&0xFF)<<24); }
        static class Field { String name,type; int len; Field(String a,String b,int c){name=a;type=b;len=c;} }
        static class Row { final Map<String,Object> d; Row(Map<String,Object> x){d=x;} Object get(String k){return d.get(k);} }
    }
}
