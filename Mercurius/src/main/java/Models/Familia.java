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

    public Familia() {
    }

    public Familia(int id, String nombre) {
        this.id = id;
        this.nombre = nombre;
    }
    
    
    
}
