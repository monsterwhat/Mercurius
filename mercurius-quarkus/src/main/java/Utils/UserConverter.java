package Utils;

import Models.Users;
import Services.UserService;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import jakarta.inject.Inject;

@FacesConverter(value = "userConverter", managed = true)
public class UserConverter implements Converter<Users> {

    @Inject
    private UserService userService;

    @Override
    public Users getAsObject(FacesContext context, UIComponent component, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            Long id = Long.valueOf(value);
            return userService.find(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String getAsString(FacesContext context, UIComponent component, Users user) {
        if (user == null) {
            return "";
        }
        return String.valueOf(user.getId());
    }
}
