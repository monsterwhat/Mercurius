package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public class OrderDTO {
    private Long id;
    private String orderNumber;
    private String status;
    @Nullable private BigDecimal subtotal;
    @Nullable private BigDecimal taxAmount;
    @Nullable private BigDecimal total;
    @Nullable private Long invoiceId;
    @Nullable private String notes;
    private Date createdAt;
    @Nullable private List<OrderItemDTO> items;

    public OrderDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Nullable
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(@Nullable BigDecimal subtotal) { this.subtotal = subtotal; }

    @Nullable
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(@Nullable BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    @Nullable
    public BigDecimal getTotal() { return total; }
    public void setTotal(@Nullable BigDecimal total) { this.total = total; }

    @Nullable
    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(@Nullable Long invoiceId) { this.invoiceId = invoiceId; }

    @Nullable
    public String getNotes() { return notes; }
    public void setNotes(@Nullable String notes) { this.notes = notes; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    @Nullable
    public List<OrderItemDTO> getItems() { return items; }
    public void setItems(@Nullable List<OrderItemDTO> items) { this.items = items; }
}
