package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Lightweight client row for lists/tables: identity, contact, loyalty points balance and status.
 * Mirrors the scalar fields of Models.Clients used by the Clientes list views.
 * Relations, credentials and deprecated fields are intentionally excluded.
 */
public class ClientsDTO {

    private int code; // Codigo (INT)
    private String name; // Nombre
    @Nullable private String address; // Direccion
    @Nullable private String idType; // Tipo de Cedula
    @Nullable private String idNumber; // Cedula
    @Nullable private String email; // Email
    @Nullable private String phoneNumber; // Telefono
    private boolean taxpayer; // Tributario
    @Nullable private BigDecimal puntosAcumulados; //Customer loyalty points
    @Nullable private String statusPuntos; //Status of points: 'active', 'inactive', 'expired'
    @Nullable private Date lastPurchaseDate; //Date of last purchase for activity tracking
    @Nullable private Boolean status; //En caso de querer archivar o desabilitar

    public ClientsDTO() {
    }

    public ClientsDTO(int code, String name, @Nullable String address, @Nullable String idType,
                      @Nullable String idNumber, @Nullable String email, @Nullable String phoneNumber,
                      boolean taxpayer, @Nullable BigDecimal puntosAcumulados, @Nullable String statusPuntos,
                      @Nullable Date lastPurchaseDate, @Nullable Boolean status) {
        this.code = code;
        this.name = name;
        this.address = address;
        this.idType = idType;
        this.idNumber = idNumber;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.taxpayer = taxpayer;
        this.puntosAcumulados = puntosAcumulados;
        this.statusPuntos = statusPuntos;
        this.lastPurchaseDate = lastPurchaseDate;
        this.status = status;
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Nullable
    public String getAddress() { return address; }
    public void setAddress(@Nullable String address) { this.address = address; }

    @Nullable
    public String getIdType() { return idType; }
    public void setIdType(@Nullable String idType) { this.idType = idType; }

    @Nullable
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(@Nullable String idNumber) { this.idNumber = idNumber; }

    @Nullable
    public String getEmail() { return email; }
    public void setEmail(@Nullable String email) { this.email = email; }

    @Nullable
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(@Nullable String phoneNumber) { this.phoneNumber = phoneNumber; }

    public boolean isTaxpayer() { return taxpayer; }
    public void setTaxpayer(boolean taxpayer) { this.taxpayer = taxpayer; }

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
    public Boolean getStatus() { return status; }
    public void setStatus(@Nullable Boolean status) { this.status = status; }
}
