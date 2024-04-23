package Models;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Articulos {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int codigo;

    @ManyToOne
    @JoinColumn(name = "codigo_cabys")
    private Cabys codigoCabys;

    private String nombre;
    
    private String codigoBarra;

    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;

    @ManyToOne
    @JoinColumn(name = "familia_id")
    private Familia familia;

    @Column(name = "precio_costo_sin_iva")
    private double precioCostoSinIVA;

    @Column(name = "precio_costo_con_iva")
    private double precioCostoConIVA;

    @Column(name = "porcentaje_utilidad")
    private double porcentajeUtilidad;

    @Column(name = "precio_final")
    private double precioFinal;
    
    private boolean status;
    
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien creo el Articulo

    public Articulos() {
    }

    public Articulos(int codigo, Cabys codigoCabys, String nombre, String codigoBarra, Departamento departamento, Familia familia, double precioCostoSinIVA, double precioCostoConIVA, double porcentajeUtilidad, double precioFinal, boolean status, Users usuario) {
        this.codigo = codigo;
        this.codigoCabys = codigoCabys;
        this.nombre = nombre;
        this.codigoBarra = codigoBarra;
        this.departamento = departamento;
        this.familia = familia;
        this.precioCostoSinIVA = precioCostoSinIVA;
        this.precioCostoConIVA = precioCostoConIVA;
        this.porcentajeUtilidad = porcentajeUtilidad;
        this.precioFinal = precioFinal;
        this.status = status;
        this.usuario = usuario;
    }

}
