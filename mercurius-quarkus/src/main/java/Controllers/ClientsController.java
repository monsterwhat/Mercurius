package Controllers;

import Models.Clients;
import Services.AlertasService;
import Services.ClientService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import Utils.DiffUtils;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "ClientsController")
@ViewScoped
public class ClientsController implements Serializable {
    
    @Inject @Nonnull private ClientService clientService;
    @Inject @Nonnull private SessionController currentSession;
    @Inject @Nonnull private AlertasService alertas;

    @Nullable
    private List<Clients> clients;
    @Nullable
    private Clients selectedClient;
    @Nullable
    private Clients newClient;
    @Nullable
    private String clientsFilter;
    @Nonnull
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

    @Nonnull
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
            String antes = DiffUtils.snapshotEntity(selectedClient);
            selectedClient.setUsuario(currentSession.getCurrentUser());
            clientService.update(selectedClient);
            alertas.registrarAlerta("Cliente Actualizado", "Se actualizo el cliente: " + selectedClient.getName(), currentSession.getCurrentUser(), 0, "updateClient()", antes, DiffUtils.snapshotEntity(selectedClient));
            clearSelectedClient();
            PrimeFaces.current().executeScript("PF('EditarClienteDialog').hide();");
        }
    }

    public void createClient() {
        if(currentSession.isValid()){
            String antes = DiffUtils.snapshotEntity(newClient);

            // Check tax ID uniqueness if idNumber is provided
            String idNumber = newClient.getIdNumber();
            if (idNumber != null && !idNumber.isBlank()
                && clientService.checkClientByIdNumber(idNumber)) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new jakarta.faces.application.FacesMessage(
                        jakarta.faces.application.FacesMessage.SEVERITY_ERROR,
                        "Error", "Ya existe un cliente con la cédula: " + idNumber));
                return;
            }

            var exists = clientService.checkClientName(newClient.getName());
            if(!exists){
                newClient.setUsuario(currentSession.getCurrentUser());
                newClient.setStatus(true);
                clientService.create(newClient);
                alertas.registrarAlerta("Se creo el cliente", "Se creo el cliente: " + newClient.getName(), currentSession.getCurrentUser(), 0, "createClient()", antes, DiffUtils.snapshotEntity(newClient));
                clearSelectedClient();    
                PrimeFaces.current().executeScript("PF('CrearClienteDialog').hide();");
            }
        }
    }

    public void toggleClient() {
        if (selectedClient != null) {
            String antes = DiffUtils.snapshotEntity(selectedClient);
            if(selectedClient.getStatus()){
                disableCliente();
            }else{
                enableCliente();
            }
            clientService.update(selectedClient);
            alertas.registrarAlerta("Se cambio el status del cliente", "El estatus cambio", currentSession.getCurrentUser(), 0, "toggleClient()", antes, DiffUtils.snapshotEntity(selectedClient));
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
        
    @Nonnull
    public List<Clients> getFilteredClients() {
        if (clientsFilter != null && !clientsFilter.isEmpty()) {
            return clientsList().stream()
                    .filter(profile -> globalFilterFunction(profile, clientsFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return clientsList();
        }
    }
       
    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Clients client = (Clients) value;
        return client.getName().toLowerCase().contains(filterText)
                || client.getEmail().toLowerCase().contains(filterText)
                || client.getBirthDate().toString().toLowerCase().contains(filterText)
                || client.getIdType().toLowerCase().contains(filterText)
                || (client.getIdNumber() != null && client.getIdNumber().toLowerCase().contains(filterText))
                || String.valueOf(client.getDiscount()).contains(filterText)
                || (client.getPhoneNumber() != null && client.getPhoneNumber().toLowerCase().contains(filterText))
                || String.valueOf(client.isTaxpayer()).contains(filterText)
                || String.valueOf(client.getZoneCode()).contains(filterText);
    }
    
}
