package Models.DTO;

import java.math.BigDecimal;
import java.util.List;

public class CartResponse {
    private List<CartItemDTO> items;
    private int itemCount;
    private BigDecimal total;

    public CartResponse() {}

    public CartResponse(List<CartItemDTO> items, int itemCount, BigDecimal total) {
        this.items = items;
        this.itemCount = itemCount;
        this.total = total;
    }

    public List<CartItemDTO> getItems() { return items; }
    public void setItems(List<CartItemDTO> items) { this.items = items; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
}
