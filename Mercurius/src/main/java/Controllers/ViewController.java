package Controllers;

import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.Arrays;
import lombok.Data;

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

    public void selectViewUsers(){
        selectedOption = "viewUsers";
    }
    
    public void selectViewUsersDetallados(){
        selectedOption = "viewUsersDetallados";
    }
    
    public void selectEditUsers(){
        selectedOption = "editUsers";
    }
    
    public void selectCreateUsers(){
        selectedOption = "createUsers";
        profiles.openNewUser();
    }
    
    public void selectViewFacturas(){
        selectedOption = "viewFacturas";
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
    
    public void selectViewCabys(){
        selectedOption = "viewCabys";
    }
    
    public void selectViewFamilias(){
        selectedOption = "viewFamilias";
    }
    
    public void selectViewFamiliasDetallado(){
        selectedOption = "viewFamiliasDetallado";
    }
    
    public void selectEditFamilias(){
        selectedOption="editFamilias";
    }
    
    public void selectCreateFamilias(){
        selectedOption = "createFamilias";
        createNewFamilia();
    }
    
    public void selectViewInventario(){
        selectedOption = "viewInventarios";
    }
    
    public void selectViewInventarioDetallado(){
        selectedOption = "viewInventariosDetallado";
    }
    
    public void selectEditInventario(){
        selectedOption="editInventarios";
    }
    
    public void selectCreateInventario(){
        selectedOption = "createInventarios";
        createNewInventario();
    }
    
    public void selectViewArticulos(){
        selectedOption="viewArticulos";
    }
    
    public void selectViewArticulosDetallado(){
        selectedOption="viewArticulosDetallado";
    }
    
    public void selectEditArticulos(){
        selectedOption="editArticulos";
    }
    
    public void selectCreateArticulos(){
        selectedOption="createArticulos";
        articulos.openNewArticulo();
    }
    
    public void selectViewDepartamentos(){
        selectedOption="viewDepartamentos";
    }
    
    public void selectViewDepartamentosDetallado(){
        selectedOption="viewDepartamentosDetallado";
    }

    public void selectEditDepartamentos(){
        selectedOption="editDepartamentos";
    }
    
    public void selectCreateDepartamentos(){
        selectedOption="createDepartamentos";
        createNewDepartamento();
    }
    
    public void createNewDepartamento(){
        departamentos.openNewDepartamento();
    }
    
    public void createNewFamilia(){
        familias.openNewFamilia();
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
