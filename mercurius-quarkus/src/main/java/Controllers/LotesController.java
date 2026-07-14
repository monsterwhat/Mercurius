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
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Named
@SessionScoped
@Getter @Setter @ToString @EqualsAndHashCode
public class LotesController implements Serializable {

    @Inject @Nonnull
    private LoteService loteService;

    @Inject @Nonnull
    private AlertasService alertasService;

    @Nullable
    private List<Lote> lotesProximosVencer;
    @Nullable
    private List<Lote> lotesVencidos;
    @Nullable
    private Lote selectedLote;
    @Nonnull
    private Lote newLote;
    @Nullable
    private Articulos selectedArticulo;
    private int diasAlerta = 30;

    @PostConstruct
    public void init() {
        newLote = new Lote();
        refresh();
    }

    public final synchronized void refresh() {
        lotesProximosVencer = loteService.listProximosVencer(diasAlerta);
        lotesVencidos = loteService.listVencidos();
    }

    public long getCountProximosVencer() {
        return loteService.countProximosVencer(diasAlerta);
    }

    public long getCountVencidos() {
        return loteService.countVencidos();
    }

    public synchronized void ingresarLote() {
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
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error al crear lote: " + e.getMessage(), null, 0, "LotesController.ingresarLote()", null, e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error al crear lote", e.getMessage()));
        }
    }
}
