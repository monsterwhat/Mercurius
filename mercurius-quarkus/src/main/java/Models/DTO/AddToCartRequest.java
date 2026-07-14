package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;

public class AddToCartRequest {
    private Long productCode;
    private BigDecimal quantity;
    @Nullable private String productName;
    @Nullable private BigDecimal unitPrice;
    @Nullable private String imageUrl;

    public AddToCartRequest() {}

    public Long getProductCode() { return productCode; }
    public void setProductCode(Long productCode) { this.productCode = productCode; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    @Nullable
    public String getProductName() { return productName; }
    public void setProductName(@Nullable String productName) { this.productName = productName; }

    @Nullable
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(@Nullable BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    @Nullable
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(@Nullable String imageUrl) { this.imageUrl = imageUrl; }
}
