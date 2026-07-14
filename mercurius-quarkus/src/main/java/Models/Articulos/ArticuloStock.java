package Models.Articulos;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

//Tabla con el valor actual del stock de los articulos.
@Entity
@Data
public class ArticuloStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String codigoBarra; // Referencing the stable barcode of the Articulo

    private BigDecimal stock;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastUpdated;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastUpdated = new Date(); // Sets the current timestamp whenever the entity is persisted or updated
    }
}