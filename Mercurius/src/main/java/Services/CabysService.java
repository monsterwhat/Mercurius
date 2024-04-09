package Services;

import Models.Cabys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
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
public class CabysService extends GService<Cabys>{
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
                    
                    // Display message: Requesting full list
                    FacesMessage requestingMessage = new FacesMessage(FacesMessage.SEVERITY_INFO, "Info", "Requesting full list...");
                    FacesContext.getCurrentInstance().addMessage(null, requestingMessage);


                    // Step 2: Request the full list using the total count
                    if (totalCount > 0) {
                        url = new URL("https://api.hacienda.go.cr/fe/cabys?q=*&top=" + totalCount);
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        responseCode = connection.getResponseCode();

                        switch (responseCode) {
                            case HttpURLConnection.HTTP_OK -> {
                                // Display message: Parsing response
                                FacesMessage parsingMessage = new FacesMessage(FacesMessage.SEVERITY_INFO, "Info", "Parsing response...");
                                FacesContext.getCurrentInstance().addMessage(null, parsingMessage);

                                // Parse JSON response and populate Cabys objects
                                try (BufferedReader fullListReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                                    StringBuilder fullListResponse = new StringBuilder();
                                    while ((inputLine = fullListReader.readLine()) != null) {
                                        fullListResponse.append(inputLine);
                                    }
                                    cabysList = parseJsonResponse(fullListResponse.toString());
                                }
                                // Display message: Done
                                FacesMessage doneMessage = new FacesMessage(FacesMessage.SEVERITY_INFO, "Info", "Done");
                                FacesContext.getCurrentInstance().addMessage(null, doneMessage);
                        
                            }
                            case 429 -> {
                                System.err.println("HTTP request failed with response code: " + responseCode);
                                // Handle error 429 (Too Many Requests)
                                FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Too many requests. Please try again later.");
                                FacesContext.getCurrentInstance().addMessage(null, message);
                            }
                            default -> {
                                System.err.println("HTTP request failed with response code: " + responseCode);
                                FacesMessage errorMessage = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "HTTP request failed with response code: " + responseCode);
                                FacesContext.getCurrentInstance().addMessage(null, errorMessage);
                            }
                        }
                    }
                }
            } else {
                System.err.println("HTTP request failed with response code: " + responseCode);
            }
        } catch (IOException e) {
            System.err.println("Error making HTTP request: " + e.getMessage());
        }

        return cabysList;
    }

    private List<Cabys> parseJsonResponse(String jsonResponse) throws IOException {
        List<Cabys> resultList = new ArrayList<>();
        System.out.println("Parsing respuesta");
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(new ByteArrayInputStream(jsonResponse.getBytes(StandardCharsets.UTF_8)));
            JsonNode cabysArray = rootNode.get("cabys");

            for (JsonNode cabysNode : cabysArray) {
                String codigo = cabysNode.get("codigo").asText();
                String descripcion = cabysNode.get("descripcion").asText();
                List<String> categorias = new ArrayList<>();
                JsonNode categoriasArray = cabysNode.get("categorias");
                for (JsonNode categoriaNode : categoriasArray) {
                    categorias.add(categoriaNode.asText());
                }
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
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_INFO, "Info", "Cabys list saved successfully.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        } catch (Exception e) {
            FacesMessage errorMessage = new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Failed to save Cabys list: " + e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null, errorMessage);
        }
    }

    
}
