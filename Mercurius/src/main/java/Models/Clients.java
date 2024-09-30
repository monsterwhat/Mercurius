package Models;

/**
 *
 * @author Al
 */

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Date;
import lombok.Data;

@Entity
@Table(name = "Clients")
@Data
public class Clients {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) 
    private int code; // Codigo (INT)
    
    @Column
    private String name; // Nombre (String)
    
    @Column
    private String address; // DIreccion (String)
    
    @Column
    private String email; // Email (String)
    
    @Column
    private Date birthDate; // Fecha Nacimiento (DATE)
    
    @Column
    private String idType; // Tipo de Cedula (String)
    
    @Column
    private int idNumber; // Cedula (Int)
    
    @Column
    private double discount; // Descuento (Double)
    
    @Column
    private int phoneNumber; // Telefono (Int)
    
    @Column
    private boolean taxpayer; // Tributario (Boolean)
    
    @Column
    private int zoneCode; // Codigo de Zona (Int)
    
    @Column
    private String TipoIdentificacion; //Tipo de identificacion Fisica/Juridica/DiMEX/NITE
    
    private Boolean status; //En caso de querer archivar o desabilitar

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien creo el cliente

    public Clients() {
    }

    public Clients(int code, String name, String address, String email, Date birthDate, String idType, int idNumber, double discount, int phoneNumber, boolean taxpayer, int zoneCode, Boolean status, Users usuario) {
        this.code = code;
        this.name = name;
        this.address = address;
        this.email = email;
        this.birthDate = birthDate;
        this.idType = idType;
        this.idNumber = idNumber;
        this.discount = discount;
        this.phoneNumber = phoneNumber;
        this.taxpayer = taxpayer;
        this.zoneCode = zoneCode;
        this.status = status;
        this.usuario = usuario;
    }
    
}

