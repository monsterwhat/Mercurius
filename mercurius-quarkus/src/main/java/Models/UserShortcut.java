package Models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

@Entity
@Table(name = "user_shortcuts")
@Data
public class UserShortcut implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String actionKey;

    @Column(nullable = false)
    private String actionLabel;

    @Column
    private String shortcutKey;

    @Column
    private String actionUrl;

    @Column
    private String iconClass;

    @Column
    private Integer displayOrder;

    @Column
    private Boolean isFavorite = false;

    @Column
    private Integer usageCount = 0;

    @Temporal(TemporalType.TIMESTAMP)
    @Column
    private Date lastUsed;

    @Temporal(TemporalType.TIMESTAMP)
    @Column
    private Date createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Date();
        lastUsed = new Date();
    }
}
