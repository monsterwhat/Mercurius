package Models.Marketplace;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

@Data
@Entity
@Table(name = "marketplace_cart", uniqueConstraints = @UniqueConstraint(columnNames = {"client_code", "product_code"}))
public class MarketplaceCartItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false)
    private int clientCode;

    @Column(name = "product_code", nullable = false)
    private Long productCode;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Nullable
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    public MarketplaceCartItem() {}

    @PrePersist
    protected void onCreate() {
        createdAt = new Date();
    }
}
