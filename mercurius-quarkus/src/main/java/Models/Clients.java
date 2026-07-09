package Models;

/**
 *
 * @author Al
 */

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "Clients")
@Data
public class Clients {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) 
    private int code; // Codigo (INT)
    
    @Column
    private String name; // Nombre (String)
    
    @Column
    private String address; // DIreccion (String)
    
    @Column
    private String email; // Email (String)
    
    @Column
    private Date birthDate; // Fecha Nacimiento (DATE)
    
    @Column
    private String idType; // Tipo de Cedula (String)
    
    @Nullable @Column
    private String idNumber; // Cedula (String — was int, changed to prevent overflow for 10+ digit IDs)
    
    @Deprecated(since = "v4.4.1", forRemoval = true)
    @Column
    private double discount; // Descuento (Double) — DEPRECATED: stored but never applied to invoice totals
    
    @Nullable @Column
    private String phoneNumber; // Telefono (String — was int, changed to preserve leading zeros)
    
    @Column
    private boolean taxpayer; // Tributario (Boolean)
    
    @Column
    private int zoneCode; // Codigo de Zona (Int)
    
    @Column
    private String TipoIdentificacion; //Tipo de identificacion Fisica/Juridica/DiMEX/NITE
    
    @Column
    private String CodigoActividadComercial;
    
    private Boolean status; //En caso de querer archivar o desabilitar

    @Nullable
    @Column(name = "puntosAcumulados")
    private BigDecimal puntosAcumulados; //Customer loyalty points

    @Nullable
    @Column(name = "lastPurchaseDate")
    private Date lastPurchaseDate; //Date of last purchase for activity tracking

    @Nullable
    @Column(name = "statusPuntos")
    private String statusPuntos; //Status of points: 'active', 'inactive', 'expired'

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien creo el cliente

    public Clients() {
    }

    public Clients(int code, String name, String address, String email, Date birthDate, String idType, String idNumber, double discount, String phoneNumber, boolean taxpayer, int zoneCode, Boolean status, Users usuario) {
        this.code = code;
        this.name = name;
        this.address = address;
        this.email = email;
        this.birthDate = birthDate;
        this.idType = idType;
        this.idNumber = idNumber;
        this.discount = discount;
        this.phoneNumber = phoneNumber;
        this.taxpayer = taxpayer;
        this.zoneCode = zoneCode;
        this.status = status;
        this.usuario = usuario;
    }

    private static final java.util.Map<String, String> ID_TYPE_TO_HACIENDA_CODE = java.util.Map.of(
        "Cédula Física", "01",
        "Cedula Fisica", "01",
        "Cédula Jurídica", "02",
        "Cedula Juridica", "02",
        "DIMEX", "03",
        "NITE", "04"
    );

    /**
     * Maps idType display name to Hacienda TipoIdentificacion code.
     * @return "01" for Cédula Física, "02" for Cédula Jurídica, "03" for DIMEX, "04" for NITE, null if unknown
     */
    @jakarta.annotation.Nullable
    public String getHaciendaIdTypeCode() {
        if (this.idType == null) return null;
        return ID_TYPE_TO_HACIENDA_CODE.get(this.idType.trim());
    }
}
