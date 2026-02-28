package Utils;

import jakarta.faces.convert.FacesConverter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@FacesConverter(value = "localDateTimeConverter")
public class LocalDateTimeConverter implements jakarta.faces.convert.Converter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public Object getAsObject(jakarta.faces.context.FacesContext context, jakarta.faces.component.UIComponent component, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(value, FORMATTER);
    }

    @Override
    public String getAsString(jakarta.faces.context.FacesContext context, jakarta.faces.component.UIComponent component, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(FORMATTER);
        }
        if (value instanceof java.util.Date) {
            java.time.Instant instant = ((java.util.Date) value).toInstant();
            LocalDateTime dateTime = LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
            return dateTime.format(FORMATTER);
        }
        return value.toString();
    }
}
