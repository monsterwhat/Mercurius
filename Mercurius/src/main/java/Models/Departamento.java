package Models;

import jakarta.persistence.*;
import java.util.Date;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Data
public class Departamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String nombre;
    
    private Boolean status; //En caso de querer archivar o desabilitar

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien creo el departamento
    
    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fecha; //LOGS LOGS LOGS!!

    public Departamento() {
    }

    public Departamento(int id, String nombre, Boolean status, Users usuario) {
        this.id = id;
        this.nombre = nombre;
        this.status = status;
        this.usuario = usuario;
    }

    @PrePersist
    protected void onCreate() {
        fecha = new Date(); // Sets the current timestamp when creating the entity
    }
    
}
