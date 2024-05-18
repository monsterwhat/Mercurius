package Controllers;

import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.Arrays;
import lombok.Data;
import org.primefaces.PrimeFaces;

/**
 *
 * @author Al
 */

@Data
@Named(value = "ViewController")
@SessionScoped
public class ViewController implements Serializable {
    
    @Inject UsersController profiles;
    @Inject ClientsController clients;
    @Inject FamiliaController familias;
    @Inject ArticulosController articulos;
    @Inject DepartamentoController departamentos;
    @Inject InventarioController inventarios;
    
    String selectedOption = "none";
    String[] selectedOptions;
    
    public void selectViewUsersDetallados(){
        selectedOption = "viewUsersDetallados";
    }
    
    public void selectEditUsers(){
        selectedOption = "editUsers";
    }
        
    public void selectViewFacturasDetalladas(){
        selectedOption = "viewFacturasDetallado";
    }
    
    public void selectViewClients(){
        selectedOption = "viewClients";
    }
    
    public void selectEditClients(){
        selectedOption = "editClients";
    }
    
    public void selectCreateClients(){
        selectedOption = "createClients";
        clients.openNewClient();
    }
    
    public void selectViewFamilias(){
        selectedOption = "viewFamilias";
    }
    
    public void selectViewFamiliasDetallado(){
        selectedOption = "viewFamiliasDetallado";
    }
    
    public void selectCreateFamilias(){
        familias.openNewFamilia();
    }
    
    public void selectViewInventario(){
        selectedOption = "viewInventarios";
    }
    
    public void selectViewInventarioInactivo(){
        selectedOption = "viewInventarioInactivo";
    }
    
    public void selectViewInventarioSinProcesar(){
        selectedOption = "viewInventarioSinProcesar";
    }
    
    public void selectViewInventarioActivoYProcesado(){
        selectedOption = "viewInventarioActivoYProcesado";
    }
    
    public void selectViewInventarioDetallado(){
        selectedOption = "viewInventariosDetallado";
    }
    
    public void selectEditInventario(){
        selectedOption="editInventarios";
    }
    
    public void selectSyncInventario(){
        selectedOption="syncInventarios";
    }
    
    public void selectCreateInventario(){
        selectedOption = "createInventarios";
        createNewInventario();
    }
    
    public void selectViewArticulos(){
        selectedOption="viewArticulos";
    }
    
    public void selectViewArticulosSinProcesar(){
        selectedOption="viewArticulosPendientes";
    }
    
    public void selectViewArticulosActivosYProcesados(){
        selectedOption="viewArticulosActivosYProcesados";
    }
    
    public void selectViewArticulosInactivos(){
        selectedOption="viewArticulosInactivos";
    }
    
    public void selectViewArticulosDetallado(){
        selectedOption="viewArticulosDetallado";
    }
    
    public void selectCreateArticulos(){
        articulos.openNewArticulo();
        PrimeFaces.current().executeScript("PF('CrearArticuloDialog').show();");
    }
    
    public void selectViewDepartamentos(){
        selectedOption="viewDepartamentos";
    }
    
    public void selectViewDepartamentosDetallado(){
        selectedOption="viewDepartamentosDetallado";
    }
    
    public void selectCreateDepartamentos(){
        selectedOption="createDepartamentos";
        createNewDepartamento();
    }
    
    public void createNewDepartamento(){
        departamentos.openNewDepartamento();
    }
    
    public void createNewInventario(){
        inventarios.openNewInventario();
    }
    
    public void selectNone(){
        selectedOption = "none";
    }
    
    public void selectedOptionsChanged() {
        String message = "Se cambio a: ";
        if (selectedOptions != null) {
            for (int i = 0; i < selectedOptions.length; i++) {
                if (i > 0) {
                    message += ", ";
                }
                message += selectedOptions[i];
            }
        }

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
    }
    
    public boolean isSelected(String selection){
        var state = Arrays.toString(selectedOptions).contains(selection);
        return state;
    }

    
}
