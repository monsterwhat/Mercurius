import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class MigrateStock {
    public static void main(String[] args) throws Exception {
        String dbUrl = getenv("DB_URL", "jdbc:postgresql://localhost:5434/mercurius");
        String dbUser = getenv("DB_USERNAME", "mercurius");
        String dbPass = getenv("DB_PASSWORD", "Mercurius@1!");
        String dbf = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted/MAESTRO.DBF";

        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            int inserted = 0;
            DbfReader r = new DbfReader(dbf);
            for (DbfReader.Row row : r.rows) {
                String code = trim(row.get("CODIGO"));
                if (code == null) continue;
                String stockRaw = trim(row.get("EXISTENCIA"));
                double stock = 0;
                try { stock = Double.parseDouble(stockRaw); } catch (Exception ignored) {}
                try (PreparedStatement del = c.prepareStatement("DELETE FROM articulostock WHERE codigo_barra = ?")) {
                    del.setString(1, code); del.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO articulostock (stock, lastupdated, version, codigo_barra) VALUES (?,?,?,?)")) {
                    ps.setDouble(1, stock);
                    ps.setObject(2, LocalDateTime.now());
                    ps.setLong(3, 0L);
                    ps.setString(4, code);
                    ps.executeUpdate();
                    inserted++;
                } catch (Exception e) {
                    System.err.println("stock insert failed " + code + " -> " + e.getMessage());
                }
            }
            c.commit();
            System.out.println("STOCK_ROWS=" + inserted);
        }
    }
    static String getenv(String k,String d){ String v=System.getenv(k); return v==null||v.isBlank()?d:v; }
    static String trim(Object v){ if(v==null)return null; String s=v.toString().trim(); return s.isEmpty()?null:s;}

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
