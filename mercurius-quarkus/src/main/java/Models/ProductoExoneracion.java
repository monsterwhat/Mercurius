package Models;

import Models.Articulos.Articulos;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"articuloCodigo"}))
@Data
public class ProductoExoneracion {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String articuloCodigo;
    
    @Column(length = 2)
    private String tipoDocumentoEX1;
    
    @Column(length = 2)
    private String tipoDocumentoOTRO;
    
    @Column(length = 20)
    private String numeroDocumento;
    
    @Column(precision = 18, scale = 5)
    private BigDecimal articulo;
    
    @Column(precision = 3, scale = 0)
    private BigDecimal inciso;
    
    @Column(length = 255)
    private String nombreInstitucion;
    
    @Column(length = 255)
    private String nombreInstitucionOtros;
    
    private LocalDateTime fechaEmisionEX;
    
    @Column(precision = 5, scale = 2)
    private BigDecimal tarifaExonerada;
    
    @Column(precision = 18, scale = 5)
    private BigDecimal montoExoneracion;
    
    @OneToOne
    @JoinColumn(name = "articulo_codigo", referencedColumnName = "codigo", insertable = false, updatable = false)
    private Articulos articuloEntity;
}
