package Services;

import Controllers.CabysController;
import Models.Cabys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class CabysService extends GService<Cabys>{
    
    @Inject CabysController controller;
    
    @Override
    protected Class<Cabys> getEntityClass() {
        return Cabys.class;
    }

    @PostConstruct
    public void init() {
    }
    
    @Override
    public void create(Cabys entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Cabys entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }
    
    
    public List<Cabys> listAllAPI() {
        List<Cabys> cabysList = new ArrayList<>();

        try {
            // Step 1: Query one record to get the total count
            URL url = new URL("https://api.hacienda.go.cr/fe/cabys?q=*&limit=1");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String inputLine;

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }

                    // Parse JSON response to get the total count
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode rootNode = mapper.readTree(response.toString());
                    int totalCount = rootNode.get("total").asInt();

                    // Step 2: Request the full list using the total count
                    if (totalCount > 0) {
                        url = new URL("https://api.hacienda.go.cr/fe/cabys?q=*&top="+totalCount);
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        responseCode = connection.getResponseCode();

                        switch (responseCode) {
                            case HttpURLConnection.HTTP_OK:
                                
                                controller.showInfo("Ok", "Conectado a tributacion...");
                                
                                // Parse JSON response and populate Cabys objects
                                try (BufferedReader fullListReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                                    StringBuilder fullListResponse = new StringBuilder();
                                    while ((inputLine = fullListReader.readLine()) != null) {
                                        fullListResponse.append(inputLine);
                                    }
                                    cabysList = parseJsonResponse(fullListResponse.toString());
                                }
                                break;
                            case 429:
                                controller.showWarn("Oops!", "Demaciados Intentos, trate mas tarde");
                                // Handle error 429 (Too Many Requests)
                                break;
                            default:
                                controller.showError("Error", "Codigo: " + responseCode);
                                break;
                        }
                    }
                }
            } else {
                controller.showError("Error", "Codigo: " + responseCode);
            }
        } catch (IOException e) {
            controller.showError("Error", "Error: " + e.getLocalizedMessage());
            System.err.println("Error making HTTP request: " + e.getMessage());
        }
        controller.showInfo("Exito", "Se descargo la lista CaByS");
        return cabysList;
    }

    private List<Cabys> parseJsonResponse(String jsonResponse) throws IOException {
        List<Cabys> resultList = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(new ByteArrayInputStream(jsonResponse.getBytes(StandardCharsets.UTF_8)));
            JsonNode cabysArray = rootNode.get("cabys");

            for (JsonNode cabysNode : cabysArray) {
                String codigo = cabysNode.get("codigo").asText();
                String descripcion = cabysNode.get("descripcion").asText();
                // Concatenate categorias into a single string
                StringBuilder categoriasBuilder = new StringBuilder();
                JsonNode categoriasArray = cabysNode.get("categorias");
                for (JsonNode categoriaNode : categoriasArray) {
                    categoriasBuilder.append(categoriaNode.asText()).append("; ");
                }
                String categorias = categoriasBuilder.toString();
                int impuesto = cabysNode.get("impuesto").asInt();
                String uri = cabysNode.get("uri").asText();
                String estado = cabysNode.get("estado").asText();

                Cabys cabys = new Cabys(codigo, descripcion, categorias, impuesto, uri, estado);
                resultList.add(cabys);
            }
        } catch (JsonProcessingException e) {
            System.err.println("Error parsing JSON response: " + e.getMessage());
        }

        return resultList;
    }
    
    public void saveAllDB(List<Cabys> cabysList) {
        try {
            for (Cabys cabys : cabysList) {
                create(cabys);
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
        }
    }
}
