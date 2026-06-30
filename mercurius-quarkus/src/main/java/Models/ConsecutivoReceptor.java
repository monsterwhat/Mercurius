package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Data
@Entity
@Table(name = "consecutivo_receptor", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"sucursal", "terminal", "tipo"})
})
public class ConsecutivoReceptor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sucursal", length = 3, nullable = false)
    private String sucursal;

    @Column(name = "terminal", length = 5, nullable = false)
    private String terminal;

    @Column(name = "tipo", length = 2, nullable = false)
    private String tipo;

    @Column(name = "ultimo_secuencial", nullable = false)
    private Long ultimoSecuencial = 0L;
}
