import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class MigrateInventoryMovements {
    public static void main(String[] args) throws Exception {
        String dbUrl = getenv("DB_URL", "jdbc:postgresql://localhost:5434/mercurius");
        String dbUser = getenv("DB_USERNAME", "mercurius");
        String dbPass = getenv("DB_PASSWORD", "Mercurius@1!");
        String root = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted";

        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            c.setAutoCommit(false);
            Map<String, Long> productMap = new HashMap<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT codigo,codigobarra FROM articulos")) {
                while (r.next()) productMap.put(r.getString(2), r.getLong(1));
            }
            long nextCode = 1;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COALESCE(MAX(codigo),0) FROM inventario")) {
                if (r.next()) nextCode = r.getLong(1) + 1;
            }

            int kardex = insertKardex(c, root, productMap, nextCode);
            nextCode += kardex;
            int entradas = insertEntradas(c, root, productMap, nextCode);
            nextCode += entradas;
            int ajustes = insertAjustes(c, root, productMap, nextCode);
            c.commit();
            System.out.println("KARDEX_INSERTED=" + kardex);
            System.out.println("ENTRADAS_INSERTED=" + entradas);
            System.out.println("AJUSTES_INSERTED=" + ajustes);
            System.out.println("TOTAL=" + (kardex + entradas + ajustes));
        }
    }

    static int insertKardex(Connection c, String root, Map<String, Long> pm, long nextCode) throws Exception {
        DbfReader r = new DbfReader(root + "/KARDEX.DBF");
        int n = 0;
        for (DbfReader.Row row : r.rows) {
            String code = trim(row.get("CODIGO"));
            Long art = code == null ? null : pm.get(code);
            if (art == null) continue;
            LocalDate fecha = localDate(row.get("FECHA"));
            if (fecha == null) continue;
            String mov = trim(row.get("MOVIMIENTO"));
            String tipo = "E".equals(mov) ? "ENTRADA" : "S".equals(mov) ? "SALIDA" : "MOVIMIENTO";
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO inventario (codigo,cantidad,fechamovimiento,tipomovimiento,articulo_codigo,notas,status,processed) VALUES (?,?,?,?,?,?,true,true)")) {
                ps.setLong(1, nextCode++);
                ps.setDouble(2, dbl(row.get("CANTIDAD")));
                ps.setObject(3, fecha.atStartOfDay());
                ps.setString(4, tipo);
                ps.setLong(5, art);
                ps.setString(6, trim(row.get("DOCUMENTO")));
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }

    static int insertEntradas(Connection c, String root, Map<String, Long> pm, long nextCode) throws Exception {
        DbfReader r = new DbfReader(root + "/ENTRADAS.DBF");
        int n = 0;
        for (DbfReader.Row row : r.rows) {
            String code = trim(row.get("CODIGO"));
            Long art = code == null ? null : pm.get(code);
            if (art == null) continue;
            LocalDate fecha = localDate(row.get("FECHA"));
            if (fecha == null) continue;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO inventario (codigo,cantidad,fechamovimiento,tipomovimiento,articulo_codigo,notas,status,processed) VALUES (?,?,?,?,?,?,true,true)")) {
                ps.setLong(1, nextCode++);
                ps.setDouble(2, dbl(row.get("CANTIDAD")));
                ps.setObject(3, fecha.atStartOfDay());
                ps.setString(4, "ENTRADA");
                ps.setLong(5, art);
                ps.setString(6, trim(row.get("DOCUMENTO")));
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }

    static int insertAjustes(Connection c, String root, Map<String, Long> pm, long nextCode) throws Exception {
        DbfReader r = new DbfReader(root + "/AJUSTESINV.DBF");
        int n = 0;
        for (DbfReader.Row row : r.rows) {
            String doc = trim(row.get("DOCUMENTO"));
            // Adjustments don't carry a product code; record as notes only.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO inventario (codigo,cantidad,fechamovimiento,tipomovimiento,articulo_codigo,notas,status,processed) VALUES (?,?,?,?,null,?,true,true)")) {
                ps.setLong(1, nextCode++);
                ps.setDouble(2, 0);
                ps.setObject(3, java.time.LocalDate.now().atStartOfDay());
                ps.setString(4, "AJUSTE");
                ps.setString(5, doc);
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }

    static String getenv(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    static String trim(Object v) { if (v == null) return null; String s = v.toString().trim(); return s.isEmpty() ? null : s; }
    static double dbl(Object v) { if (v == null) return 0; String s = v.toString().trim(); if (s.isEmpty()) return 0; try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
    static LocalDate localDate(Object v) {
        String s = trim(v);
        if (s == null) return null;
        if (s.length() == 8 && s.chars().allMatch(Character::isDigit)) {
            try { return LocalDate.of(Integer.parseInt(s.substring(0,4)), Integer.parseInt(s.substring(4,6)), Integer.parseInt(s.substring(6,8))); } catch (Exception ignored) {}
        }
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
                while(true){ byte[] raw=new byte[32]; f.readFully(raw); if(raw[0]==0x0D||raw[0]==0x00) break;
                    String nm=new String(raw,0,11,"ISO-8859-1").split("\0")[0].trim();
                    int len=raw[16]&0xFF; String tp=new String(raw,11,1,"ISO-8859-1");
                    if(!nm.isEmpty()) fields.add(new Field(nm,tp,len));
                }
                for(int i=0;i<n;i++){ long off=hs+(long)i*rs; f.seek(off); byte[] rec=new byte[rs]; f.readFully(rec); if(rec[0]==(byte)0x2A) continue;
                    Map<String,Object> m=new LinkedHashMap<>(); int p=1;
                    for(Field fld:fields){ String val=new String(rec,p,fld.len,"ISO-8859-1").trim(); p+=fld.len; m.put(fld.name,val); }
                    rows.add(new Row(m));
                }
            }
        }
        int readInt(byte[] b,int o){ return (b[o]&0xFF)|((b[o+1]&0xFF)<<8)|((b[o+2]&0xFF)<<16)|((b[o+3]&0xFF)<<24); }
        static class Field{String name,type;int len;Field(String a,String b,int c){name=a;type=b;len=c;}}
        static class Row{final Map<String,Object>d;Row(Map<String,Object>x){d=x;} Object get(String k){return d.get(k);}}
    }
}
