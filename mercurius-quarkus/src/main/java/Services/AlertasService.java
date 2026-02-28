package Services;

import Models.Registros.Alertas;
import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.Date;

/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class AlertasService extends GService<Alertas> {

    @Override
    protected Class<Alertas> getEntityClass() {
        return Alertas.class;
    }

    @PostConstruct
    public void init() {
        
    }
    
    @Override
    public void create(Alertas alerta) {
        try {
            em.persist(alerta);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
    public void registrarAlerta(String tipo, String Mensaje, Users user, int codigo, String source, String antes, String despues){
            Alertas alerta = new Alertas();
            alerta.setTipo(tipo);
            alerta.setMensaje(Mensaje);
            alerta.setTimestamp(LocalDateTime.now());
            alerta.setUser(user);
            alerta.setVista(false);
            alerta.setCodigo(codigo);
            alerta.setSource(source);
            alerta.setAntes(antes);
            alerta.setDespues(despues);
            
            create(alerta);
    }  
    
    public void toggleVista(Alertas alerta) {
        try {
            alerta.setVista(!alerta.isVista());
            em.merge(alerta);
        } catch (Exception e) {
            System.out.println("Error toggling vista: " + e.getLocalizedMessage());
        }
    }
}
