package Models;

import Models.Articulos.Articulos;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 *
 * @author Al
 */

@Entity
@Data
public class Inventario implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int codigo;
    
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "articulo_codigo")
    private Articulos articulo; //Referencia al articulo (Al valido en el momento del ajuste)
    
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien realizo el ajuste
        
    private BigDecimal cantidad;
    
    private BigDecimal unidadesRecomendadasFactura;
    
    private String tipoMovimiento;
    
    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaMovimiento; 
    
    private String notas;
    
    private Boolean status; //En caso de querer archivar o desabilitar un ajuste
    
    private Boolean processed;

}