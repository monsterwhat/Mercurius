package Models;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Familia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String nombre;
    
    private Boolean status; //En caso de querer archivar o desabilitar

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien creo la familia

    public Familia() {
    }

    public Familia(int id, String nombre, Boolean status, Users usuario) {
        this.id = id;
        this.nombre = nombre;
        this.status = status;
        this.usuario = usuario;
    }
    
}
