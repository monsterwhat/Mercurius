package Controllers;

import Models.Clients;
import Services.AlertasService;
import Services.ClientService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "ClientsController")
@ViewScoped
public class ClientsController implements Serializable {
    
    @Inject private ClientService clientService;
    @Inject private SessionController currentSession;
    @Inject private AlertasService alertas;

    private List<Clients> clients;
    private Clients selectedClient;
    private Clients newClient;
    private String clientsFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    public ClientsController() {
    }

    @PostConstruct
    public void init() {
        newClient = new Clients();
        selectedClient = new Clients();
        clientsList();
        filterBy = new ArrayList<>();        
    }

    public List<Clients> clientsList() {
        if (clients == null) {
            clients = clientService.listAll();
        }
        return clients;
    }

    public long clientsCount() {
        return clientService.count();
    }
    
    public long clientsActivosCount() {
        return clientsList().stream().filter(c -> c.getStatus() != null && c.getStatus()).count();
    }
    
    public long clientsInactivosCount() {
        return clientsList().stream().filter(c -> c.getStatus() == null || !c.getStatus()).count();
    }

    public void openNewClient() {
        newClient = new Clients();
    }

    public void updateClient() {
        if(currentSession.isValid()){
            var oldClient = selectedClient;
            selectedClient.setUsuario(currentSession.getCurrentUser());
            clientService.update(selectedClient);
            alertas.registrarAlerta("Cliente Actualizado", "Se actualizo el cliente: " + selectedClient.getName(), currentSession.getCurrentUser(), 0, "updateClient()", oldClient.toString() , selectedClient.toString());
            clearSelectedClient();
            PrimeFaces.current().executeScript("PF('EditarClienteDialog').hide();");
        }
    }

    public void createClient() {
        if(currentSession.isValid()){
            var exists = clientService.checkClientName(newClient.getName());
            var oldClient = newClient;
            if(!exists){
                newClient.setUsuario(currentSession.getCurrentUser());
                newClient.setStatus(true);
                clientService.create(newClient);
                alertas.registrarAlerta("Se creo el cliente", "Se creo el cliente: " + newClient.getName(), currentSession.getCurrentUser(), 0, "createClient()", oldClient.toString(), newClient.toString());
                clearSelectedClient();    
                PrimeFaces.current().executeScript("PF('CrearClienteDialog').hide();");
            }
        }
    }

    public void toggleClient() {
        if (selectedClient != null) {
            var oldClient = selectedClient;
            if(selectedClient.getStatus()){
                disableCliente();
            }else{
                enableCliente();
            }
            clientService.update(selectedClient);
            alertas.registrarAlerta("Se cambio el status del cliente", "El estatus cambio", currentSession.getCurrentUser(), 0, "toggleClient()", oldClient.toString(), selectedClient.toString());
            clearSelectedClient();
        }
    }
    
    public void disableCliente(){
        selectedClient.setStatus(false);
    }
    
    public void enableCliente(){
        selectedClient.setStatus(true);
    }

    public void clearSelectedClient() {
        clients = null;
        newClient = null;
        selectedClient = null;
    }    
        
    public List<Clients> getFilteredClients() {
        if (clientsFilter != null && !clientsFilter.isEmpty()) {
            return clientsList().stream()
                    .filter(profile -> globalFilterFunction(profile, clientsFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return clientsList();
        }
    }
       
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Clients client = (Clients) value;
        return client.getName().toLowerCase().contains(filterText)
                || client.getEmail().toLowerCase().contains(filterText)
                || client.getBirthDate().toString().toLowerCase().contains(filterText)
                || client.getIdType().toLowerCase().contains(filterText)
                || String.valueOf(client.getIdNumber()).contains(filterText)
                || String.valueOf(client.getDiscount()).contains(filterText)
                || String.valueOf(client.getPhoneNumber()).contains(filterText)
                || String.valueOf(client.isTaxpayer()).contains(filterText)
                || String.valueOf(client.getZoneCode()).contains(filterText);
    }
    
}
