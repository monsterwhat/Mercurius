package Controllers;

import Models.Lote;
import Models.Articulos.Articulos;
import Services.LoteService;
import Services.AlertasService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Named
@SessionScoped
@Data
public class LotesController implements Serializable {

    @Inject
    private LoteService loteService;

    @Inject
    private AlertasService alertasService;

    private List<Lote> lotesProximosVencer;
    private List<Lote> lotesVencidos;
    private Lote selectedLote;
    private Lote newLote;
    private Articulos selectedArticulo;
    private int diasAlerta = 30;

    @PostConstruct
    public void init() {
        newLote = new Lote();
        refresh();
    }

    public final void refresh() {
        lotesProximosVencer = loteService.listProximosVencer(diasAlerta);
        lotesVencidos = loteService.listVencidos();
    }

    public long getCountProximosVencer() {
        return loteService.countProximosVencer(diasAlerta);
    }

    public long getCountVencidos() {
        return loteService.countVencidos();
    }

    public void ingresarLote() {
        try {
            newLote.setStatus(true);
            if (newLote.getFechaIngreso() == null) {
                newLote.setFechaIngreso(new Date());
            }
            if (newLote.getCantidadActual() == null) {
                newLote.setCantidadActual(newLote.getCantidadInicial());
            }
            loteService.create(newLote);
            alertasService.registrarAlerta("Creacion", "Lote creado: " + newLote.getNumeroLote()
                    + " para articulo " + newLote.getArticulo().getNombre(), null, 0, "LotesController.ingresarLote()", null, null);
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Lote ingresado exitosamente", null));
            newLote = new Lote();
            refresh();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error al crear lote: " + e.getMessage(), null, 0, "LotesController.ingresarLote()", null, e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error al crear lote", e.getMessage()));
        }
    }
}
