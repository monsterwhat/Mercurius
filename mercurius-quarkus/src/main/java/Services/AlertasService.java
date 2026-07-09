package Services;

import Models.Registros.Alertas;
import Models.Users;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.logging.Logger;

/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class AlertasService extends GService<Alertas> {

    private static final Logger LOG = Logger.getLogger(AlertasService.class.getName());

    @Override
    protected @Nonnull Class<Alertas> getEntityClass() {
        return Alertas.class;
    }

    @PostConstruct
    public void init() {
        
    }
    
    @Override
    public void create(@Nonnull Alertas alerta) {
        try {
            em.persist(alerta);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.severe("Error creating Alertas entity: " + e.getMessage());
        }
    }
    
    public void registrarAlerta(@Nonnull String tipo, @Nonnull String Mensaje, @Nullable Users user, int codigo, @Nonnull String source, @Nullable String antes, @Nullable String despues){
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
    
    public void toggleVista(@Nonnull Alertas alerta) {
        try {
            alerta.setVista(!alerta.isVista());
            em.merge(alerta);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.severe("Error toggling Alertas vista: " + e.getMessage());
        }
    }
}
