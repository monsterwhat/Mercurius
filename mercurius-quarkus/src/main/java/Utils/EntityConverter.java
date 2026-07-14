package Utils;

import Models.Articulos.Articulos;
import Services.ArticulosService;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import jakarta.inject.Inject;

@FacesConverter(value = "entityConverter", managed = true)
public class EntityConverter implements Converter<Articulos> {

    @Inject
    private ArticulosService articulosService;

    @Override
    public Articulos getAsObject(FacesContext context, UIComponent component, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            int codigo = Integer.parseInt(value);
            return articulosService.findById(codigo);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String getAsString(FacesContext context, UIComponent component, Articulos articulo) {
        if (articulo == null) {
            return "";
        }
        return String.valueOf(articulo.getCodigo());
    }
}
