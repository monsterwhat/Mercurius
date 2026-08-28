package Services;

import Models.Marketplace.MarketplaceCartItem;
import Models.DTO.AddToCartRequest;
import Models.DTO.CartItemDTO;
import Models.DTO.CartResponse;
import Models.DTO.UpdateCartItemRequest;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service for Mercatus marketplace shopping cart operations.
 * Each client has their own cart stored in the marketplace_cart table.
 */
@Named
@ApplicationScoped
public class MarketplaceCartService {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MarketplaceCartService.class.getName());

    
    @jakarta.persistence.PersistenceContext
    @Nonnull
    private jakarta.persistence.EntityManager em;

    protected MarketplaceCartService() {}

    /**
     * Get the current cart for a client, with computed subtotals and total.
     */
    @Nonnull
    public CartResponse getCart(int clientCode) {
        try {
            List<MarketplaceCartItem> items = findCartItems(clientCode);
            if (items.isEmpty()) {
                return new CartResponse(Collections.emptyList(), 0, BigDecimal.ZERO);
            }

            List<CartItemDTO> dtos = new ArrayList<>(items.size());
            BigDecimal total = BigDecimal.ZERO;
            int count = 0;

            for (MarketplaceCartItem item : items) {
                CartItemDTO dto = toCartItemDTO(item);
                dtos.add(dto);
                total = total.add(dto.getSubtotal());
                count += item.getQuantity().intValue();
            }

            return new CartResponse(dtos, count, total.setScale(2, RoundingMode.HALF_UP));
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error getting cart: " + e.getMessage() + " | source=" + "MarketplaceCartService.getCart()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return new CartResponse(Collections.emptyList(), 0, BigDecimal.ZERO);
        }
    }

    /**
     * Add an item to the cart. If the product already exists, increment quantity.
     */
    @Transactional
    @Nonnull
    public CartItemDTO addItem(int clientCode, @Nonnull AddToCartRequest request) {
        try {
            // Check if product already in cart
            MarketplaceCartItem existing = findCartItem(clientCode, request.getProductCode());
            if (existing != null) {
                existing.setQuantity(existing.getQuantity().add(request.getQuantity()));
                em.merge(existing);
                return toCartItemDTO(existing);
            }

            // Create new cart item
            MarketplaceCartItem item = new MarketplaceCartItem();
            item.setClientCode(clientCode);
            item.setProductCode(request.getProductCode());
            item.setProductName(request.getProductName() != null ? request.getProductName() : "Producto #" + request.getProductCode());
            item.setUnitPrice(request.getUnitPrice() != null ? request.getUnitPrice() : BigDecimal.ZERO);
            item.setQuantity(request.getQuantity() != null && request.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? request.getQuantity() : BigDecimal.ONE);
            item.setImageUrl(request.getImageUrl());
            em.persist(item);
            return toCartItemDTO(item);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error adding cart item: " + e.getMessage() + " | source=" + "MarketplaceCartService.addItem()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            throw new RuntimeException("Error al agregar al carrito", e);
        }
    }

    /**
     * Update the quantity of a cart item. If quantity is 0 or negative, removes the item.
     */
    @Transactional
    public void updateItemQuantity(int clientCode, @Nonnull Long cartItemId, @Nonnull UpdateCartItemRequest request) {
        try {
            MarketplaceCartItem item = em.find(MarketplaceCartItem.class, cartItemId);
            if (item == null || item.getClientCode() != clientCode) {
                throw new IllegalArgumentException("Item no encontrado en el carrito");
            }

            if (request.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                em.remove(item);
            } else {
                item.setQuantity(request.getQuantity());
                em.merge(item);
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating cart item: " + e.getMessage() + " | source=" + "MarketplaceCartService.updateItemQuantity()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            throw new RuntimeException("Error al actualizar el carrito", e);
        }
    }

    /**
     * Remove an item from the cart.
     */
    @Transactional
    public void removeItem(int clientCode, @Nonnull Long cartItemId) {
        try {
            MarketplaceCartItem item = em.find(MarketplaceCartItem.class, cartItemId);
            if (item != null && item.getClientCode() == clientCode) {
                em.remove(item);
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error removing cart item: " + e.getMessage() + " | source=" + "MarketplaceCartService.removeItem()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    /**
     * Clear all items from the client's cart.
     */
    @Transactional
    public void clearCart(int clientCode) {
        try {
            em.createQuery("DELETE FROM MarketplaceCartItem c WHERE c.clientCode = :clientCode")
                .setParameter("clientCode", clientCode)
                .executeUpdate();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error clearing cart: " + e.getMessage() + " | source=" + "MarketplaceCartService.clearCart()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    /**
     * Get cart items for a client (raw entities).
     */
    @Nonnull
    public List<MarketplaceCartItem> findCartItems(int clientCode) {
        try {
            TypedQuery<MarketplaceCartItem> query = em.createQuery(
                "SELECT c FROM MarketplaceCartItem c WHERE c.clientCode = :clientCode ORDER BY c.createdAt",
                MarketplaceCartItem.class);
            query.setParameter("clientCode", clientCode);
            List<MarketplaceCartItem> results = query.getResultList();
            return results != null ? results : Collections.emptyList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding cart items: " + e.getMessage() + " | source=" + "MarketplaceCartService.findCartItems()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * Find a single cart item by client and product code.
     */
    @Nullable
    public MarketplaceCartItem findCartItem(int clientCode, @Nonnull Long productCode) {
        try {
            TypedQuery<MarketplaceCartItem> query = em.createQuery(
                "SELECT c FROM MarketplaceCartItem c WHERE c.clientCode = :clientCode AND c.productCode = :productCode",
                MarketplaceCartItem.class);
            query.setParameter("clientCode", clientCode);
            query.setParameter("productCode", productCode);
            List<MarketplaceCartItem> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding cart item: " + e.getMessage() + " | source=" + "MarketplaceCartService.findCartItem()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * Convert entity to DTO, computing subtotal.
     */
    @Nonnull
    private CartItemDTO toCartItemDTO(@Nonnull MarketplaceCartItem item) {
        CartItemDTO dto = new CartItemDTO();
        dto.setId(item.getId());
        dto.setProductCode(item.getProductCode());
        dto.setProductName(item.getProductName());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setQuantity(item.getQuantity());
        dto.setSubtotal(item.getUnitPrice().multiply(item.getQuantity()).setScale(2, RoundingMode.HALF_UP));
        dto.setImageUrl(item.getImageUrl());
        return dto;
    }
}
