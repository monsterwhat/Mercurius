package Models;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One log-out tombstone.
 *
 * <p>Quarkus form auth with {@code quarkus.http.auth.session.encryption-key}
 * keeps the session client-side: the cookie is signed and the server holds no
 * session table, so clearing the cookie on log-out does NOT stop a replay of
 * the old value. This row is the server-side memory the mechanism lacks: the
 * SHA-256 of the cookie value, kept until the cookie would have expired.</p>
 *
 * <p>The hash (not the cookie) is stored on purpose: a database dump must not
 * hand out live credentials.</p>
 */
@Entity
@Table(name = "sesion_cerrada")
public class SesionCerrada {

    /** Hex SHA-256 of the cookie value; primary key makes re-logout idempotent. */
    @Id
    @Column(length = 64, nullable = false)
    private String hash;

    /** Instant after which the tombstone is useless (the cookie is expired). */
    @Column(nullable = false)
    private Instant expiraEn;

    protected SesionCerrada() {
        // JPA
    }

    public SesionCerrada(String hash, Instant expiraEn) {
        this.hash = hash;
        this.expiraEn = expiraEn;
    }

    public String getHash() {
        return hash;
    }

    public Instant getExpiraEn() {
        return expiraEn;
    }
}
