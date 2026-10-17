import java.io.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class MigrateCompras {
    public static void main(String[] args) throws Exception {
        String dbUrl = getenv("DB_URL", "jdbc:postgresql://localhost:5434/mercurius");
        String dbUser = getenv("DB_USERNAME", "mercurius");
        String dbPass = getenv("DB_PASSWORD", "Mercurius@1!");
        String dbf = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/facturascompras.dbf";

        DbfReader r = new DbfReader(dbf);
        int imported = 0;
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            c.setAutoCommit(false);
            for (DbfReader.Row row : r.rows) {
                String num = "P-" + str(row.get("FACTURA"));
                if (num.isBlank()) continue;
                if (exists(c, "SELECT 1 FROM comprobantesrecibidos cr JOIN encabezado e ON cr.encabezado_id=e.id WHERE e.numero_consecutivo=?", num)) continue;

                LocalDate fecha = localDate(row.get("FECHA"));
                if (fecha == null) fecha = LocalDate.now();
                String estado = str(row.get("ESTADO"));
                if (estado.isBlank()) estado = "REGISTRADO";

                long idEnc = nextId(c, "encabezado_id_seq");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO encabezado (id, fecha_emision, numero_consecutivo, estado, schemaversion) VALUES (?,?,?,?,?)")) {
                    ps.setLong(1, idEnc);
                    ps.setObject(2, fecha.atStartOfDay());
                    ps.setString(3, num);
                    ps.setString(4, estado);
                    ps.setString(5, "4.4");
                    ps.executeUpdate();
                }
                long idRes = nextId(c, "resumen_factura_id_seq");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO resumen_factura (id, total_comprobante, total_exento, total_gravado, total_impuesto, total_venta, total_descuentos, schemaversion) VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, idRes);
                    ps.setBigDecimal(2, bd(row.get("TOTAL")));
                    ps.setBigDecimal(3, bd(row.get("EXENTO")));
                    ps.setBigDecimal(4, bd(row.get("GRAVADO")));
                    ps.setBigDecimal(5, bd(row.get("IMPVENTAS")));
                    ps.setBigDecimal(6, bd(row.get("SUBTOTAL")));
                    ps.setBigDecimal(7, bd(row.get("DESCUENTO")));
                    ps.setString(8, "4.4");
                    ps.executeUpdate();
                }
                long idCr = nextId(c, "comprobantesrecibidos_id_seq");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO comprobantesrecibidos (id, encabezado_id, resumen_id, paid, status, usuario, schemaversion) VALUES (?,?,?,?,?,?,?)")) {
                    ps.setLong(1, idCr);
                    ps.setLong(2, idEnc);
                    ps.setLong(3, idRes);
                    ps.setBoolean(4, "P".equalsIgnoreCase(estado) || "PAGADO".equalsIgnoreCase(estado));
                    ps.setBoolean(5, true);
                    ps.setString(6, blank(row.get("USUARIO")));
                    ps.setString(7, "4.4");
                    ps.executeUpdate();
                }
                imported++;
                if (imported <= 5 || imported % 500 == 0) System.out.println("PROCESSED " + imported + " purchase# " + num);
            }
            c.commit();
            System.out.println("IMPORTED_COMPRAS=" + imported);
        }
    }

    static String getenv(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    static String str(Object v) { return v == null ? "" : v.toString().trim(); }
    static String blank(Object v) { if (v == null) return null; String s = v.toString().trim(); return s.isEmpty() ? null : s; }
    static java.math.BigDecimal bd(Object v) { if (v == null) return java.math.BigDecimal.ZERO; String s = v.toString().trim(); if (s.isEmpty()) return java.math.BigDecimal.ZERO; try { return new java.math.BigDecimal(s); } catch (Exception e) { return java.math.BigDecimal.ZERO; } }
    static LocalDate localDate(Object v) { String s = v == null ? "" : v.toString().trim(); if (s.isEmpty()) return null; if (s.length() == 8 && s.chars().allMatch(Character::isDigit)) { try { return LocalDate.of(Integer.parseInt(s.substring(0,4)), Integer.parseInt(s.substring(4,6)), Integer.parseInt(s.substring(6,8))); } catch (Exception ignored) {} } return null; }
    static boolean exists(Connection c, String sql, String v) throws Exception { try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setString(1,v); try (ResultSet rs = ps.executeQuery()) { return rs.next(); } } }
    static long nextId(Connection c, String seq) throws SQLException { try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT nextval('" + seq + "')")) { rs.next(); return rs.getLong(1); } }
    static class DbfReader {
        List<Row> rows = new ArrayList<>();
        DbfReader(String path) throws Exception {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(path, "r")) {
                byte[] h = new byte[32]; f.readFully(h);
                int n = readInt(h,4);
                int hs = (h[8]&0xFF)|((h[9]&0xFF)<<8);
                int rs = (h[10]&0xFF)|((h[11]&0xFF)<<8);
                f.seek(32); List<Field> fields=new ArrayList<>();
                while (true) { byte[] raw=new byte[32]; int got=f.read(raw); if (got<32) break; if (raw[0]==0x0D||raw[0]==0x00) break; String nm=new String(raw,0,11,"ISO-8859-1").split("\0")[0].trim(); int len=raw[16]&0xFF; String tp=new String(raw,11,1,"ISO-8859-1"); if(!nm.isEmpty()) fields.add(new Field(nm,tp,len)); }
                for (int i=0;i<n;i++){ long off=hs+(long)i*rs; f.seek(off); byte[] rec=new byte[rs]; int got=f.read(rec); if (got<rs) break; if (rec[0]==(byte)0x2A) continue; Map<String,Object> m=new LinkedHashMap<>(); int p=1; for (Field fld:fields){ String val=new String(rec,p,fld.len,"ISO-8859-1").trim(); p+=fld.len; m.put(fld.name,val); } rows.add(new Row(m)); }
            }
        }
        int readInt(byte[] b,int o){ return (b[o]&0xFF)|((b[o+1]&0xFF)<<8)|((b[o+2]&0xFF)<<16)|((b[o+3]&0xFF)<<24); }
        static class Field{String name,type;int len;Field(String a,String b,int c){name=a;type=b;len=c;}}
        static class Row{final Map<String,Object>d;Row(Map<String,Object>x){d=x;} Object get(String k){return d.get(k);}}
    }
}
