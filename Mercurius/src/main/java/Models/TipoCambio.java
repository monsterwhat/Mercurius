package Models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Data
public class TipoCambio {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(nullable = false)
    private double valorCompra;

    @Column(nullable = false)
    private double valorVenta;

    public TipoCambio() {
    }

    public TipoCambio(Long id, LocalDateTime fecha, double valorCompra, double valorVenta) {
        this.id = id;
        this.fecha = fecha;
        this.valorCompra = valorCompra;
        this.valorVenta = valorVenta;
    }

    
}
