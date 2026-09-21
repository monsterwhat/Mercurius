package Models.Articulos;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@Entity
@Table(name = "articulo_imagenes")
public class ArticuloImagen implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "articulo_codigo", nullable = false)
    private Articulos articulo;

    @Column(nullable = false)
    private String ruta;

    @Column(nullable = false)
    private int orden;

    @Column
    private String nombreOriginal;

    @Column
    private String mimeType;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaSubida;

    @PrePersist
    protected void onCreate() {
        fechaSubida = new Date();
    }
}
