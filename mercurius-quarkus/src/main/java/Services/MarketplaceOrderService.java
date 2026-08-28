package Services;

import Models.Marketplace.MarketplaceCartItem;
import Models.Marketplace.MarketplaceOrder;
import Models.Marketplace.MarketplaceOrderItem;
import Models.DTO.CreateOrderRequest;
import Models.DTO.OrderDTO;
import Models.DTO.OrderItemDTO;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Mercatus marketplace orders.
 * Creates orders from cart items, listing client orders.
 * Invoice generation (via ComprobanteService) deferred until checkout flow is decided.
 */
@Named
@ApplicationScoped
public class MarketplaceOrderService {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MarketplaceOrderService.class.getName());

    
    @Nonnull
    MarketplaceCartService cartService;

    @jakarta.persistence.PersistenceContext
    @Nonnull
    private jakarta.persistence.EntityManager em;

    protected MarketplaceOrderService() {}

    /**
     * Create an order from the current cart contents.
     * Clears the cart after successful order creation.
     */
    @Transactional
    @Nonnull
    public OrderDTO createOrderFromCart(int clientCode, @Nullable CreateOrderRequest request) {
        try {
            List<MarketplaceCartItem> cartItems = cartService.findCartItems(clientCode);
            if (cartItems.isEmpty()) {
                throw new IllegalArgumentException("El carrito está vacío");
            }

            // Calculate totals
            BigDecimal subtotal = BigDecimal.ZERO;
            for (MarketplaceCartItem item : cartItems) {
                subtotal = subtotal.add(item.getUnitPrice().multiply(item.getQuantity()));
            }
            BigDecimal tax = subtotal.multiply(new BigDecimal("0.13")).setScale(2, RoundingMode.HALF_UP); // 13% IVA
            BigDecimal total = subtotal.add(tax);

            // Create order
            MarketplaceOrder order = new MarketplaceOrder();
            order.setClientCode(clientCode);
            order.setOrderNumber(generateOrderNumber(clientCode));
            order.setStatus("pending");
            order.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
            order.setTaxAmount(tax);
            order.setTotal(total.setScale(2, RoundingMode.HALF_UP));
            if (request != null && request.getNotes() != null) {
                order.setNotes(request.getNotes());
            }

            // Create order items from cart items
            List<MarketplaceOrderItem> orderItems = new ArrayList<>(cartItems.size());
            for (MarketplaceCartItem cartItem : cartItems) {
                MarketplaceOrderItem orderItem = new MarketplaceOrderItem();
                orderItem.setOrder(order);
                orderItem.setProductCode(cartItem.getProductCode());
                orderItem.setProductName(cartItem.getProductName());
                orderItem.setUnitPrice(cartItem.getUnitPrice());
                orderItem.setQuantity(cartItem.getQuantity());
                orderItem.setSubtotal(cartItem.getUnitPrice().multiply(cartItem.getQuantity()).setScale(2, RoundingMode.HALF_UP));
                orderItems.add(orderItem);
            }
            order.setItems(orderItems);

            em.persist(order);

            // Clear the cart
            cartService.clearCart(clientCode);

            return toOrderDTO(order);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating order: " + e.getMessage() + " | source=" + "MarketplaceOrderService.createOrderFromCart()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            throw new RuntimeException("Error al crear la orden", e);
        }
    }

    /**
     * List all orders for a client, newest first.
     */
    @Nonnull
    public List<OrderDTO> listClientOrders(int clientCode) {
        try {
            TypedQuery<MarketplaceOrder> query = em.createQuery(
                "SELECT o FROM MarketplaceOrder o WHERE o.clientCode = :clientCode ORDER BY o.createdAt DESC",
                MarketplaceOrder.class);
            query.setParameter("clientCode", clientCode);
            List<MarketplaceOrder> orders = query.getResultList();
            if (orders == null || orders.isEmpty()) return Collections.emptyList();
            return orders.stream().map(this::toOrderDTO).collect(Collectors.toList());
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing client orders: " + e.getMessage() + " | source=" + "MarketplaceOrderService.listClientOrders()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * Get a single order by ID, verifying client ownership.
     */
    @Nullable
    public OrderDTO getOrder(int clientCode, @Nonnull Long orderId) {
        try {
            MarketplaceOrder order = em.find(MarketplaceOrder.class, orderId);
            if (order == null || order.getClientCode() != clientCode) return null;
            return toOrderDTO(order);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error getting order: " + e.getMessage() + " | source=" + "MarketplaceOrderService.getOrder()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * Cancel an order if it's still in "pending" status.
     */
    @Transactional
    public boolean cancelOrder(int clientCode, @Nonnull Long orderId) {
        try {
            MarketplaceOrder order = em.find(MarketplaceOrder.class, orderId);
            if (order == null || order.getClientCode() != clientCode) return false;
            if (!"pending".equals(order.getStatus())) return false;

            order.setStatus("cancelled");
            em.merge(order);
            return true;
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error cancelling order: " + e.getMessage() + " | source=" + "MarketplaceOrderService.cancelOrder()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }

    /**
     * Generate a human-readable order number: MP-{CLIENTCODE}-{YYYYMMDD}-{ID}
     */
    @Nonnull
    private String generateOrderNumber(int clientCode) {
        String datePart = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        return String.format("MP-%d-%s", clientCode, datePart);
    }

    @Nonnull
    private OrderDTO toOrderDTO(@Nonnull MarketplaceOrder order) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setOrderNumber(order.getOrderNumber());
        dto.setStatus(order.getStatus());
        dto.setSubtotal(order.getSubtotal());
        dto.setTaxAmount(order.getTaxAmount());
        dto.setTotal(order.getTotal());
        dto.setInvoiceId(order.getInvoiceId());
        dto.setNotes(order.getNotes());
        dto.setCreatedAt(order.getCreatedAt());

        if (order.getItems() != null && !order.getItems().isEmpty()) {
            dto.setItems(order.getItems().stream().map(item -> {
                OrderItemDTO oi = new OrderItemDTO();
                oi.setId(item.getId());
                oi.setProductCode(item.getProductCode());
                oi.setProductName(item.getProductName());
                oi.setUnitPrice(item.getUnitPrice());
                oi.setQuantity(item.getQuantity());
                oi.setSubtotal(item.getSubtotal());
                return oi;
            }).collect(Collectors.toList()));
        }

        return dto;
    }
}
