package Controllers;

import Models.Clients;
import Services.ClientService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "ClientsController")
@SessionScoped
public class ClientsController implements Serializable {
    @Inject private ClientService clientService;
    @Inject private ViewController viewManager;

    private List<Clients> clients;
    private Clients selectedClient;
    private Clients newClient;
    //private String generatorOption;
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

    public void openNewClient() {
        newClient = new Clients();
    }

    public void updateClient() {
        clientService.update(selectedClient);
        clearSelectedClient();
    }

    public void createClient() {
        clientService.create(newClient);
        clearSelectedClient();
    }

    public void deleteClient() {
        if (selectedClient != null) {
            if (selectedClient.getCode() != 0) {
                clientService.delete(selectedClient);
                clearSelectedClient();
            }
        }
    }

    public void clearSelectedClient() {
        clients = null;
        newClient = null;
        selectedClient = null;
        viewManager.selectViewClients();
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
