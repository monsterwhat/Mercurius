package Utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

public class DbSessionKeyConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(DbSessionKeyConfigSource.class);
    private static final String KEY = "quarkus.http.auth.session.encryption-key";
    private static final int ORDINAL = 400;
    private static volatile String cached = null;

    @Override
    public Map<String, String> getProperties() {
        String value = getValue(KEY);
        if (value != null) return Map.of(KEY, value);
        return Collections.emptyMap();
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.of(KEY);
    }

    @Override
    public String getValue(String propertyName) {
        if (!KEY.equals(propertyName)) return null;
        if (cached != null) return cached;
        String url = resolveUrl();
        String user = resolveUser();
        String pass = resolvePass();
        if (url == null || user == null) return null;
        try {
            try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException ignored) {}
            try (Connection c = DriverManager.getConnection(url, user, pass)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT auth_session_key FROM appsettings WHERE estatus = true AND auth_session_key IS NOT NULL AND auth_session_key <> '' LIMIT 1")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String dbKey = rs.getString(1);
                            if (dbKey != null && dbKey.length() >= 32) {
                                cached = dbKey;
                                LOG.debug("loaded auth session key from DB");
                                return dbKey;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("auth session key column not yet available", e);
                    return null;
                }
                try {
                    String newKey = Utils.EncryptionUtil.generateKey();
                    int updated = 0;
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE appsettings SET auth_session_key = ? WHERE estatus = true AND (auth_session_key IS NULL OR auth_session_key = '')")) {
                        upd.setString(1, newKey);
                        updated = upd.executeUpdate();
                    } catch (Exception ignored) {
                        LOG.debug("failed to persist generated auth session key");
                    }
                    if (updated == 0) {
                        return null;
                    }
                    cached = newKey;
                    LOG.info("generated new DB-managed auth session key");
                    return newKey;
                } catch (Exception e) {
                    LOG.warn("failed to generate auth session key", e);
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveUrl() {
        String v = System.getProperty("quarkus.datasource.jdbc.url");
        if (v != null) return v;
        v = System.getenv("DB_URL");
        if (v != null) return v;
        v = System.getenv("QUARKUS_DATASOURCE_JDBC_URL");
        if (v != null) return v;
        // Dev default from application.properties
        return "jdbc:postgresql://localhost:5433/mercurius";
    }

    private String resolveUser() {
        String v = System.getProperty("quarkus.datasource.username");
        if (v != null) return v;
        v = System.getenv("DB_USERNAME");
        if (v != null) return v;
        return "mercurius";
    }

    private String resolvePass() {
        String v = System.getProperty("quarkus.datasource.password");
        if (v != null) return v;
        v = System.getenv("DB_PASSWORD");
        if (v != null) return v;
        return "Mercurius@1!";
    }

    @Override
    public String getName() {
        return "DbSessionKeyConfigSource";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
