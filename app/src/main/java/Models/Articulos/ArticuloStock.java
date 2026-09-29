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

    /**
     * Referencing the stable barcode of the Articulo — the natural key of this
     * table: one running-stock row per article.
     *
     * <p>{@code unique = true} is load-bearing, not hygiene. Without it two
     * concurrent first-sales of a new barcode both found no row and both
     * inserted one, permanently splitting that article's stock across two rows
     * (only one of which the read paths would see). It also gives
     * {@code InventarioService.updateStock} a database-level arbiter for that
     * race, so exactly one insert wins.
     *
     * <p><b>Migration note:</b> with
     * {@code quarkus.hibernate-orm.schema-management.strategy=update} this adds
     * a unique index at startup. An existing database that already holds
     * duplicate barcodes will fail that DDL step — collapse the duplicates
     * first, e.g.:
     * {@code SELECT codigo_barra, COUNT(*), SUM(stock) FROM articulo_stock
     * GROUP BY codigo_barra HAVING COUNT(*) > 1;}
     * The {@code %test} profile uses {@code drop-and-create}, so the suite is
     * unaffected.</p>
     */
    @Column(name = "codigo_barra", nullable = false, unique = true)
    private String codigoBarra;

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