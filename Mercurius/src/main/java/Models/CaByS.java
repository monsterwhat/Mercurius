package Models;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
public class CaByS {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Long categoria1;
    private String descripcionCategoria1;
    private Long categoria2;
    private String descripcionCategoria2;
    private Long categoria3;
    private String descripcionCategoria3;
    private Long categoria4;
    private String descripcionCategoria4;
    private Long categoria5;
    private String descripcionCategoria5;
    private Long categoria6;
    private String descripcionCategoria6;
    private Long categoria7;
    private String descripcionCategoria7;
    private Long categoria8;
    private String descripcionCategoria8;
    private Long categoria9;
    private String descripcionCategoria9;
    private double impuesto;
    private String notaInclusiva1;
    private String notaExclusiva1;

    public CaByS(Long id, Long categoria1, String descripcionCategoria1, Long categoria2, String descripcionCategoria2, Long categoria3, String descripcionCategoria3, Long categoria4, String descripcionCategoria4, Long categoria5, String descripcionCategoria5, Long categoria6, String descripcionCategoria6, Long categoria7, String descripcionCategoria7, Long categoria8, String descripcionCategoria8, Long categoria9, String descripcionCategoria9, double impuesto, String notaInclusiva1, String notaExclusiva1) {
        this.id = id;
        this.categoria1 = categoria1;
        this.descripcionCategoria1 = descripcionCategoria1;
        this.categoria2 = categoria2;
        this.descripcionCategoria2 = descripcionCategoria2;
        this.categoria3 = categoria3;
        this.descripcionCategoria3 = descripcionCategoria3;
        this.categoria4 = categoria4;
        this.descripcionCategoria4 = descripcionCategoria4;
        this.categoria5 = categoria5;
        this.descripcionCategoria5 = descripcionCategoria5;
        this.categoria6 = categoria6;
        this.descripcionCategoria6 = descripcionCategoria6;
        this.categoria7 = categoria7;
        this.descripcionCategoria7 = descripcionCategoria7;
        this.categoria8 = categoria8;
        this.descripcionCategoria8 = descripcionCategoria8;
        this.categoria9 = categoria9;
        this.descripcionCategoria9 = descripcionCategoria9;
        this.impuesto = impuesto;
        this.notaInclusiva1 = notaInclusiva1;
        this.notaExclusiva1 = notaExclusiva1;
    }

    public CaByS() {
    }
    
    
    
}

