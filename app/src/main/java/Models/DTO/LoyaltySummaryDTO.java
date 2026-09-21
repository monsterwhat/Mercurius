package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Resumen de lealtad de un cliente para las vistas bajo META-INF/resources/secured/pages/Loyalty/**.
 * tierColor es un campo string placeholder con el color hexadecimal del nivel del cliente,
 * tal como lo calcula LoyaltyController.getCustomerTierColor:
 * "#ffd700" (Oro), "#c0c0c0" (Plata), "#cd7f32" (Bronce), "#cccccc" (Básico).
 */
public class LoyaltySummaryDTO {

    private int clienteCode; // Clients.code
    private String clienteNombre; // Clients.name
    @Nullable private BigDecimal puntosAcumulados; // Customer loyalty points
    @Nullable private String statusPuntos; // Status of points: 'active', 'inactive', 'expired'
    @Nullable private Date lastPurchaseDate; // Date of last purchase for activity tracking
    @Nullable private String tierColor; // Placeholder: hex color del nivel (ver LoyaltyController.getCustomerTierColor)

    public LoyaltySummaryDTO() {
    }

    public LoyaltySummaryDTO(int clienteCode, String clienteNombre,
                             @Nullable BigDecimal puntosAcumulados, @Nullable String statusPuntos,
                             @Nullable Date lastPurchaseDate, @Nullable String tierColor) {
        this.clienteCode = clienteCode;
        this.clienteNombre = clienteNombre;
        this.puntosAcumulados = puntosAcumulados;
        this.statusPuntos = statusPuntos;
        this.lastPurchaseDate = lastPurchaseDate;
        this.tierColor = tierColor;
    }

    public int getClienteCode() { return clienteCode; }
    public void setClienteCode(int clienteCode) { this.clienteCode = clienteCode; }

    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }

    @Nullable
    public BigDecimal getPuntosAcumulados() { return puntosAcumulados; }
    public void setPuntosAcumulados(@Nullable BigDecimal puntosAcumulados) { this.puntosAcumulados = puntosAcumulados; }

    @Nullable
    public String getStatusPuntos() { return statusPuntos; }
    public void setStatusPuntos(@Nullable String statusPuntos) { this.statusPuntos = statusPuntos; }

    @Nullable
    public Date getLastPurchaseDate() { return lastPurchaseDate; }
    public void setLastPurchaseDate(@Nullable Date lastPurchaseDate) { this.lastPurchaseDate = lastPurchaseDate; }

    @Nullable
    public String getTierColor() { return tierColor; }
    public void setTierColor(@Nullable String tierColor) { this.tierColor = tierColor; }
}
