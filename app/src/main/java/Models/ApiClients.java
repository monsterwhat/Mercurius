package Models;

/**
 * JPA entity for OAuth2 API clients (client_credentials grant type).
 * Hibernate auto-creates this table via drop-and-create strategy.
 */

import jakarta.persistence.*;
import java.util.Date;
import lombok.Data;

@Entity
@Table(name = "ApiClients")
@Data
public class ApiClients {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "client_id", unique = true, nullable = false, length = 64)
    private String clientId;

    @Column(name = "client_secret", nullable = false, length = 255)
    private String clientSecret; // BCrypt-hashed

    @Column(name = "scopes", length = 1024)
    private String scopes; // JSON array stored as string, e.g. '["mercatus","accounting"]'

    @Column(name = "rate_limit_per_min")
    private int rateLimitPerMin = 60;

    @Column(name = "rate_limit_per_hour")
    private int rateLimitPerHour = 1000;

    @Column(name = "status")
    private boolean status = true; // active/inactive

    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    @Column(name = "name", length = 128)
    private String name; // Friendly name for admin identification

    public ApiClients() {
    }

    public ApiClients(int id, String clientId, String clientSecret, String scopes,
                      int rateLimitPerMin, int rateLimitPerHour, boolean status,
                      Date createdAt, String name) {
        this.id = id;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes;
        this.rateLimitPerMin = rateLimitPerMin;
        this.rateLimitPerHour = rateLimitPerHour;
        this.status = status;
        this.createdAt = createdAt;
        this.name = name;
    }
}
