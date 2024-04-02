package Controllers;

import Models.Clients;
import Services.ClientService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

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

    public ClientsController() {
    }

    @PostConstruct
    public void init() {
        newClient = new Clients();
        selectedClient = new Clients();
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

    /*
    public void generateAndCreateRandomUsers() {
        FakeUserGenerator userGenerator = new FakeUserGenerator();
        // Use the selectedGenerator value to determine the generator strategy
        Optional<String> generatorInput = Optional.ofNullable(generatorOption);
        if (generatorInput.isPresent()) {
            userGenerator.setUsernameGenerator(generatorInput.get());
        }

        for (int i = 0; i < 10; i++) {
            Clients newUser = userGenerator.generateFakeClientProfile("user");
            clientService.create(newUser);
        }
        clearSelectedClient();
    }

    public void generateAndCreateRandomAdmins() {
        FakeUserGenerator userGenerator = new FakeUserGenerator();

        Optional<String> generatorInput = Optional.ofNullable(generatorOption);
        if (generatorInput.isPresent()) {
            userGenerator.setUsernameGenerator(generatorInput.get());
        }

        for (int i = 0; i < 10; i++) {
            Clients newUser = userGenerator.generateFakeClientProfile("admin");
            clientService.create(newUser);
        }
        clearSelectedClient();
    }
    */
}
