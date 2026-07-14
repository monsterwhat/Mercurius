package Controllers;

import Controllers.SessionController;
import Models.CierreCaja;
import Services.AlertasService;
import Services.CierreCajaService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString @EqualsAndHashCode
@Named("cierreCajaController")
@ViewScoped
public class CierreCajaController implements Serializable {

    @Inject @Nonnull
    private CierreCajaService cierreCajaService;

    @Inject @Nonnull
    private SessionController sessionController;

    @Inject @Nonnull
    private AlertasService alertasService;

    @Nullable
    private CierreCaja sesionActual;
    @Nullable
    private List<CierreCaja> historial;
    private boolean sesionAbierta;

    @Nullable
    private BigDecimal montoApertura;
    @Nullable
    private BigDecimal montoContadoEfectivo;
    @Nullable
    private BigDecimal montoContadoSinpe;
    @Nullable
    private BigDecimal montoContadoTarjeta;
    @Nullable
    private String notasCierre;

    @PostConstruct
    public void init() {
        if (sessionController.getCurrentUser() != null) {
            sesionActual = cierreCajaService.findSesionAbierta(sessionController.getCurrentUser());
            sesionAbierta = sesionActual != null;
            cargarHistorial();
        }
    }

    public void abrirSesion() {
        if (montoApertura == null || montoApertura.compareTo(BigDecimal.ZERO) <= 0) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Debe ingresar un monto inicial valido"));
            return;
        }

        CierreCaja sesion = new CierreCaja();
        sesion.setUsuario(sessionController.getCurrentUser());
        sesion.setFechaApertura(new Date());
        sesion.setMontoInicial(montoApertura);
        sesion.setEstado("abierto");
        cierreCajaService.create(sesion);

        sesionActual = sesion;
        sesionAbierta = true;
        montoApertura = null;

        alertasService.registrarAlerta("Caja abierta",
            "Sesion de caja abierta con monto inicial: " + montoApertura,
            sessionController.getCurrentUser(), 0, "CierreCajaController.abrirSesion()", null, null);

        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito", "Sesion de caja abierta correctamente"));
    }

    public void cerrarSesion() {
        if (sesionActual == null) {
            return;
        }

        if (montoContadoEfectivo == null) {
            montoContadoEfectivo = BigDecimal.ZERO;
        }
        if (montoContadoSinpe == null) {
            montoContadoSinpe = BigDecimal.ZERO;
        }
        if (montoContadoTarjeta == null) {
            montoContadoTarjeta = BigDecimal.ZERO;
        }

        sesionActual.setMontoContadoEfectivo(montoContadoEfectivo);
        sesionActual.setMontoContadoSinpe(montoContadoSinpe);
        sesionActual.setMontoContadoTarjeta(montoContadoTarjeta);

        BigDecimal totalEsperado = BigDecimal.ZERO;
        if (sesionActual.getMontoEsperadoEfectivo() != null) {
            totalEsperado = totalEsperado.add(sesionActual.getMontoEsperadoEfectivo());
        }
        if (sesionActual.getMontoEsperadoSinpe() != null) {
            totalEsperado = totalEsperado.add(sesionActual.getMontoEsperadoSinpe());
        }
        if (sesionActual.getMontoEsperadoTarjeta() != null) {
            totalEsperado = totalEsperado.add(sesionActual.getMontoEsperadoTarjeta());
        }

        BigDecimal totalContado = montoContadoEfectivo.add(montoContadoSinpe).add(montoContadoTarjeta);
        BigDecimal diferencia = totalContado.subtract(totalEsperado);

        sesionActual.setDiferencia(diferencia);
        sesionActual.setFechaCierre(new Date());
        sesionActual.setEstado("cerrado");
        sesionActual.setNotas(notasCierre);
        cierreCajaService.update(sesionActual);

        sesionAbierta = false;
        sesionActual = null;
        montoContadoEfectivo = null;
        montoContadoSinpe = null;
        montoContadoTarjeta = null;
        notasCierre = null;

        cargarHistorial();

        alertasService.registrarAlerta("Caja cerrada",
            "Sesion de caja cerrada. Diferencia: " + diferencia,
            sessionController.getCurrentUser(), 0, "CierreCajaController.cerrarSesion()", null, null);

        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito",
                "Sesion de caja cerrada. Diferencia: " + diferencia));
    }

    public void cargarHistorial() {
        historial = cierreCajaService.listHistorial(sessionController.getCurrentUser());
    }
}
