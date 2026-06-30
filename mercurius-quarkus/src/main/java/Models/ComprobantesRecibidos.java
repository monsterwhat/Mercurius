package Models;

import Models.Detalles.DetalleServicio;
import Models.Encabezado.Encabezado;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.ResumenFactura;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import lombok.Data;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "ComprobantesRecibidos")
@Entity
@Data
public class ComprobantesRecibidos {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 10)
    private String schemaVersion;
    
    @XmlElement(name = "Encabezado")
    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Encabezado encabezado;
    
    @XmlElement(name = "DetalleServicio")
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalles;
    
    @XmlElement(name = "Resumen")
    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private ResumenFactura resumen;

    @XmlElement(name = "InformacionReferencia")
    @ToString.Exclude
    @jakarta.persistence.OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "comprobante_recibido_id")
    private java.util.List<InformacionReferencia> informacionReferencia;

    private Boolean status;
    private Boolean processed;
    
    @Column(nullable = false)
    private Boolean paid = false;
    
    @Column(length = 50)
    private String user;

    @Column(name = "hacienda_mensaje_receptor_estado", length = 20)
    private String haciendaMensajeReceptorEstado;

    @Column(name = "hacienda_mensaje_receptor_fecha")
    private LocalDateTime haciendaMensajeReceptorFecha;

    @Column(name = "mensaje_receptor_limite")
    private LocalDate mensajeReceptorLimite;

    @Transient
    public LocalDate getMensajeReceptorLimite() {
        if (mensajeReceptorLimite != null) return mensajeReceptorLimite;
        if (encabezado == null || encabezado.getFechaEmision() == null) return null;
        
        LocalDate fechaEmision = encabezado.getFechaEmision()
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        LocalDate inicioMesSiguiente = fechaEmision.withDayOfMonth(1).plusMonths(1);
        return calcularLimite8DiasHabiles(inicioMesSiguiente);
    }

    public void calcularYGuardarLimite() {
        this.mensajeReceptorLimite = getMensajeReceptorLimite();
    }

    private LocalDate calcularLimite8DiasHabiles(LocalDate inicio) {
        int diasHabiles = 0;
        LocalDate fecha = inicio;
        while (diasHabiles < 8) {
            if (fecha.getDayOfWeek() != DayOfWeek.SATURDAY && fecha.getDayOfWeek() != DayOfWeek.SUNDAY) {
                diasHabiles++;
                if (diasHabiles == 8) break;
            }
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    public boolean isMensajeReceptorVencido() {
        LocalDate limite = getMensajeReceptorLimite();
        if (limite == null) return false;
        return LocalDate.now().isAfter(limite);
    }

    public long getDiasRestantesMensajeReceptor() {
        LocalDate limite = getMensajeReceptorLimite();
        if (limite == null) return -1;
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), limite);
    }
    
}
