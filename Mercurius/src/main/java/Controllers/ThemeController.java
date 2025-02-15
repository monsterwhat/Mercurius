package Controllers;

import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.Serializable;
import lombok.Data;

@Named("ThemeController")
@SessionScoped
@Data
public class ThemeController implements Serializable {

    private String currentTheme = "saga";
    private String selectedTheme = "";
    
    public void changeTheme(String theme) {
        setCurrentTheme(theme);
     }
    
    public void updateTheme(){
        if(!selectedTheme.isBlank()){
            currentTheme = selectedTheme;
             reloadCurrentPage();
        }
    }
        
    public static void reloadCurrentPage() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        ExternalContext externalContext = facesContext.getExternalContext();
        HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
        
        String url = request.getRequestURL().toString();
        String queryString = request.getQueryString();
        
        if (queryString != null) {
            url += "?" + queryString;
        }
        
        try {
            externalContext.redirect(url);
        } catch (IOException e) {
            e.printStackTrace(); // Handle exception as appropriate
        }
    }
    
}
