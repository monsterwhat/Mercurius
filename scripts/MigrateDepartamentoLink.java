import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class MigrateDepartamentoLink {
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
    public static void main(String[] args) throws Exception {
        String root = "D:/Documents/Facturas Electronicas/vSuper/Sistema_extracted";
        DbfReader maestro = new DbfReader(root + "/MAESTRO.DBF");
        DbfReader provee = new DbfReader(root + "/PROVEE.DBF");

        Map<String,String> codeToName = new HashMap<>();
        for (DbfReader.Row r : provee.rows) codeToName.put(trim(r.get("CODIGO")), trim(r.get("NOMBRE")));

        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5434/mercurius","mercurius","Mercurius@1!")) {
            c.setAutoCommit(false);
            Map<String,Integer> deptByName = new HashMap<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id,nombre FROM departamento")) { while (r.next()) deptByName.put(r.getString(2), r.getInt(1)); }
            int updated = 0;
            for (DbfReader.Row r : maestro.rows) {
                String oldCode = trim(r.get("CODIGO"));
                String provCode = trim(r.get("PROV"));
                if (oldCode == null || provCode == null) continue;
                String deptName = codeToName.get(provCode);
                if (deptName == null) continue;
                Integer deptId = deptByName.get(deptName);
                if (deptId == null) continue;
                try (PreparedStatement ps = c.prepareStatement("UPDATE articulos SET departamento_id = ? WHERE codigobarra = ?")) {
                    ps.setInt(1, deptId);
                    ps.setString(2, oldCode);
                    updated += ps.executeUpdate();
                }
            }
            c.commit();
            System.out.println("DEPARTAMENTO_LINKS=" + updated);
        }
    }
    static String trim(Object v){ if (v==null) return null; String s=v.toString().trim(); return s.isEmpty()?null:s; }
}
