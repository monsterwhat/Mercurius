import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class MigrateHaciendaStatus {
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        String dbUrl = prop("DB_URL", "jdbc:postgresql://localhost:5434/mercurius");
        String dbUser = prop("DB_USERNAME", "mercurius");
        String dbPass = prop("DB_PASSWORD", "Mercurius@1!");
        String dbf = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/FE_DOCUMENTOS.DBF";

        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            c.setAutoCommit(false);
            DbfReader r = new DbfReader(dbf);
            int updated = 0;
            for (DbfReader.Row row : r.rows) {
                int num = num(row.get("NUMERO"));
                if (num == 0) continue;
                String clave = trim(row.get("CCLAVE"));
                String estado = trim(row.get("CESTADOFE"));
                Timestamp envio = ts(row.get("FENVIO"));
                Timestamp resp = ts(row.get("FRESPUESTA"));
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE comprobantesemitidos AS ce " +
                        "SET haciendaclave = COALESCE(?, ce.haciendaclave), " +
                        "    haciendaestado = COALESCE(?, ce.haciendaestado), " +
                        "    haciendafechaenvio = COALESCE(?, ce.haciendafechaenvio), " +
                        "    haciendafecharespuesta = COALESCE(?, ce.haciendafecharespuesta) " +
                        "FROM encabezado e " +
                        "WHERE ce.encabezado_id = e.id AND e.numero_consecutivo = ?")) {
                    ps.setString(1, clave);
                    ps.setString(2, estado);
                    if (envio == null) ps.setNull(3, Types.TIMESTAMP); else ps.setTimestamp(3, envio);
                    if (resp == null) ps.setNull(4, Types.TIMESTAMP); else ps.setTimestamp(4, resp);
                    ps.setString(5, String.valueOf(num));
                    updated += ps.executeUpdate();
                }
            }
            c.commit();
            System.out.println("HACIENDA_UPDATES=" + updated);
        }
    }

    static String prop(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    static String trim(Object v) { if (v == null) return null; String s = v.toString().trim(); return s.isEmpty() ? null : s; }
    static int num(Object v) { if (v == null) return 0; String s = v.toString().trim(); if (s.isEmpty()) return 0; try { return (int) Double.parseDouble(s); } catch (Exception e) { return 0; } }
    static Timestamp ts(Object v) {
        String s = trim(v);
        if (s == null) return null;
        try { return Timestamp.valueOf(s); } catch (Exception ignored) {}
        try { if (s.length() == 8 && s.chars().allMatch(Character::isDigit)) return Timestamp.valueOf(LocalDate.of(Integer.parseInt(s.substring(0,4)), Integer.parseInt(s.substring(4,6)), Integer.parseInt(s.substring(6,8))).atStartOfDay()); } catch (Exception ignored) {}
        return null;
    }

    static class DbfReader {
        List<Row> rows = new ArrayList<>();
        DbfReader(String path) throws Exception {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(path, "r")) {
                byte[] h = new byte[32]; f.readFully(h);
                int n = Integer.reverseBytes? reverse4(h,4): reverse4(h,4);
                int hs = (h[8]&0xFF)|((h[9]&0xFF)<<8);
                int rs = (h[10]&0xFF)|((h[11]&0xFF)<<8);
                f.seek(32); List<Field> fields=new ArrayList<>();
                while(true){ byte[] raw=new byte[32]; f.readFully(raw); if(raw[0]==0x0D||raw[0]==0x00) break;
                    String nm=new String(raw,0,11,"ISO-8859-1").split("\0")[0].trim();
                    String tp=new String(raw,11,1,"ISO-8859-1");
                    int len=raw[16]&0xFF;
                    if(!nm.isEmpty()) fields.add(new Field(nm,tp,len));
                }
                for(int i=0;i<n;i++){ long off=hs+(long)i*rs; f.seek(off); byte[] rec=new byte[rs]; f.readFully(rec); if(rec[0]==(byte)0x2A) continue;
                    Map<String,Object> m=new LinkedHashMap<>(); int p=1;
                    for(Field fld:fields){ String val=new String(rec,p,fld.len,"ISO-8859-1").trim(); p+=fld.len; m.put(fld.name,val); }
                    rows.add(new Row(m));
                }
            }
        }
        int reverse4(byte[] b,int o){ return (b[o]&0xFF)|((b[o+1]&0xFF)<<8)|((b[o+2]&0xFF)<<16)|((b[o+3]&0xFF)<<24);}
        static class Field{String name,type;int len;Field(String a,String b,int c){name=a;type=b;len=c;}}
        static class Row{final Map<String,Object>d;Row(Map<String,Object>x){d=x;} Object get(String k){return d.get(k);}}
    }
}
