package Models.Marketplace;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@Entity
@Table(name = "marketplace_order_items")
public class MarketplaceOrderItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private MarketplaceOrder order;

    @Column(name = "product_code", nullable = false)
    private Long productCode;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Nullable
    @Column(precision = 18, scale = 4)
    private BigDecimal subtotal;

    public MarketplaceOrderItem() {}
}
