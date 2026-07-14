package Models.Marketplace;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@Entity
@Table(name = "marketplace_orders")
public class MarketplaceOrder implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false)
    private int clientCode;

    @Column(name = "order_number", nullable = false, unique = true, length = 50)
    private String orderNumber;

    @Column(nullable = false, length = 20)
    private String status; // pending, confirmed, processing, shipped, delivered, cancelled

    @Nullable
    @Column(precision = 18, scale = 4)
    private BigDecimal subtotal;

    @Nullable
    @Column(name = "tax_amount", precision = 18, scale = 4)
    private BigDecimal taxAmount;

    @Nullable
    @Column(precision = 18, scale = 4)
    private BigDecimal total;

    @Nullable
    @Column(length = 500)
    private String notes;

    @Nullable
    @Column(name = "invoice_id")
    private Long invoiceId; // References ComprobantesEmitidos once invoice is generated

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    @Nullable
    @Temporal(TemporalType.TIMESTAMP)
    private Date updatedAt;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MarketplaceOrderItem> items = new ArrayList<>();

    public MarketplaceOrder() {}

    @PrePersist
    protected void onCreate() {
        createdAt = new Date();
        updatedAt = new Date();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Date();
    }
}
