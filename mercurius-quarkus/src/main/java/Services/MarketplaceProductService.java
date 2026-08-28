package Services;

import Models.Articulos.ArticuloImagen;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.ArticuloStock;
import Models.Articulos.Articulos;
import Models.Departamento;
import Models.Familia;
import Models.DTO.ProductDTO;
import Models.DTO.ProductDetailDTO;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Mercatus marketplace product catalog queries.
 * Reads from existing Articulos, ArticuloPrecio, ArticuloImagen, and ArticuloStock tables.
 * Read-only — products are managed through the admin JSF interface.
 */
@Named
@ApplicationScoped
public class MarketplaceProductService {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MarketplaceProductService.class.getName());

    
    @jakarta.persistence.PersistenceContext
    @Nonnull
    private jakarta.persistence.EntityManager em;

    protected MarketplaceProductService() {}

    /**
     * List all active & processed products with current price and stock status.
     * Returns a lightweight DTO list suitable for product catalog display.
     */
    @Nonnull
    public List<ProductDTO> listActiveProducts() {
        try {
            TypedQuery<Articulos> query = em.createQuery(
                "SELECT a FROM Articulos a WHERE a.status = true AND a.processed = true ORDER BY a.nombre",
                Articulos.class);
            List<Articulos> products = query.getResultList();
            if (products == null || products.isEmpty()) return Collections.emptyList();

            List<ProductDTO> dtos = new ArrayList<>(products.size());
            for (Articulos a : products) {
                ProductDTO dto = new ProductDTO();
                dto.setCodigo(a.getCodigo());
                dto.setNombre(a.getNombre());
                dto.setDescripcion(a.getDescripcion());
                dto.setCodigoBarra(a.getCodigoBarra());
                dto.setUnidadMedida(a.getUnidadMedida());
                if (a.getDepartamento() != null) dto.setDepartamento(a.getDepartamento().getNombre());
                if (a.getFamilia() != null) dto.setFamilia(a.getFamilia().getNombre());

                ArticuloPrecio lastPrecio = a.getLastPrecio();
                if (lastPrecio != null) {
                    dto.setPrecio(lastPrecio.getPrecioFinal());
                }

                // Get first image
                if (a.getImagenes() != null && !a.getImagenes().isEmpty()) {
                    dto.setImagenUrl(a.getImagenes().get(0).getRuta());
                }

                // Check stock
                dto.setTieneStock(hasStock(a.getCodigoBarra()));

                dtos.add(dto);
            }
            return dtos;
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing marketplace products: " + e.getMessage() + " | source=" + "MarketplaceProductService.listActiveProducts()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * Search active products by name fragment.
     */
    @Nonnull
    public List<ProductDTO> searchProducts(@Nonnull String query) {
        try {
            TypedQuery<Articulos> q = em.createQuery(
                "SELECT a FROM Articulos a WHERE a.status = true AND a.processed = true AND LOWER(a.nombre) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY a.nombre",
                Articulos.class);
            q.setParameter("query", query);
            q.setMaxResults(50);
            List<Articulos> products = q.getResultList();
            if (products == null || products.isEmpty()) return Collections.emptyList();

            List<ProductDTO> dtos = new ArrayList<>(products.size());
            for (Articulos a : products) {
                ProductDTO dto = new ProductDTO();
                dto.setCodigo(a.getCodigo());
                dto.setNombre(a.getNombre());
                dto.setDescripcion(a.getDescripcion());
                dto.setCodigoBarra(a.getCodigoBarra());
                dto.setUnidadMedida(a.getUnidadMedida());
                if (a.getDepartamento() != null) dto.setDepartamento(a.getDepartamento().getNombre());
                if (a.getFamilia() != null) dto.setFamilia(a.getFamilia().getNombre());

                ArticuloPrecio lastPrecio = a.getLastPrecio();
                if (lastPrecio != null) dto.setPrecio(lastPrecio.getPrecioFinal());

                if (a.getImagenes() != null && !a.getImagenes().isEmpty()) {
                    dto.setImagenUrl(a.getImagenes().get(0).getRuta());
                }
                dto.setTieneStock(hasStock(a.getCodigoBarra()));
                dtos.add(dto);
            }
            return dtos;
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error searching marketplace products: " + e.getMessage() + " | source=" + "MarketplaceProductService.searchProducts()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * Get full product details including multiple images and stock quantity.
     */
    @Nullable
    public ProductDetailDTO getProductDetail(@Nonnull Long productCode) {
        try {
            Articulos a = em.find(Articulos.class, productCode);
            if (a == null || !a.isStatus() || !a.isProcessed()) return null;

            ProductDetailDTO dto = new ProductDetailDTO();
            dto.setCodigo(a.getCodigo());
            dto.setNombre(a.getNombre());
            dto.setDescripcion(a.getDescripcion());
            dto.setCodigoBarra(a.getCodigoBarra());
            dto.setUnidadMedida(a.getUnidadMedida());
            dto.setStatus(a.isStatus());
            dto.setProcessed(a.isProcessed());
            if (a.getDepartamento() != null) dto.setDepartamento(a.getDepartamento().getNombre());
            if (a.getFamilia() != null) dto.setFamilia(a.getFamilia().getNombre());

            ArticuloPrecio lastPrecio = a.getLastPrecio();
            if (lastPrecio != null) {
                dto.setPrecio(lastPrecio.getPrecioFinal());
                dto.setPrecioCostoSinIVA(lastPrecio.getPrecioCostoSinIVA());
                dto.setPorcentajeUtilidad(lastPrecio.getPorcentajeUtilidad());
            }

            // Multiple images
            if (a.getImagenes() != null && !a.getImagenes().isEmpty()) {
                dto.setImagenes(a.getImagenes().stream()
                    .map(ArticuloImagen::getRuta)
                    .collect(Collectors.toList()));
            }

            // Stock quantity
            if (a.getCodigoBarra() != null) {
                BigDecimal stock = getStockQuantity(a.getCodigoBarra());
                dto.setStockDisponible(stock);
            }

            return dto;
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error getting product detail: " + e.getMessage() + " | source=" + "MarketplaceProductService.getProductDetail()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * Check if a product has available stock (> 0).
     */
    public boolean hasStock(@Nullable String barcode) {
        if (barcode == null || barcode.isBlank()) return false;
        try {
            TypedQuery<BigDecimal> query = em.createQuery(
                "SELECT a.stock FROM ArticuloStock a WHERE a.codigoBarra = :barcode",
                BigDecimal.class);
            query.setParameter("barcode", barcode);
            List<BigDecimal> results = query.getResultList();
            return !results.isEmpty() && results.get(0).compareTo(BigDecimal.ZERO) > 0;
        } catch (PersistenceException e) {
            return false;
        }
    }

    /**
     * Get current stock quantity for a barcode.
     */
    @Nonnull
    public BigDecimal getStockQuantity(@Nullable String barcode) {
        if (barcode == null || barcode.isBlank()) return BigDecimal.ZERO;
        try {
            TypedQuery<BigDecimal> query = em.createQuery(
                "SELECT a.stock FROM ArticuloStock a WHERE a.codigoBarra = :barcode",
                BigDecimal.class);
            query.setParameter("barcode", barcode);
            List<BigDecimal> results = query.getResultList();
            return results.isEmpty() ? BigDecimal.ZERO : results.get(0);
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }
}
