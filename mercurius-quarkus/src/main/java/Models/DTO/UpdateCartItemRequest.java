package Models.DTO;

import java.math.BigDecimal;

public class UpdateCartItemRequest {
    private BigDecimal quantity;

    public UpdateCartItemRequest() {}

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
}
