package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;

public class OrderItemDTO {
    private Long id;
    private Long productCode;
    private String productName;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    @Nullable private BigDecimal subtotal;

    public OrderItemDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProductCode() { return productCode; }
    public void setProductCode(Long productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    @Nullable
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(@Nullable BigDecimal subtotal) { this.subtotal = subtotal; }
}
