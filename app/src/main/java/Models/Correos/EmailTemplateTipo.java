package Models.Correos;

/**
 *
 * @author Al
 */
public enum EmailTemplateTipo {
    REPORTES("Reportes Programados"),
    ALERTAS_STOCK("Alertas de Stock"),
    NOTIFICACIONES("Notificaciones"),
    PERSONALIZADO("Personalizado");

    private final String descripcion;

    EmailTemplateTipo(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getDescripcion() {
        return descripcion;
    }
}
