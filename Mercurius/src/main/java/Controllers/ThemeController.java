package Controllers;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.context.ExternalContext;
import jakarta.inject.Named;
import java.io.Serializable;
import lombok.Data;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Named("ThemeController")
@SessionScoped
@Data
public class ThemeController implements Serializable {

    @PostConstruct
    public void init() {
        loadThemeFromCookie();
    }

    public void switchThemeAndSave() {
        switchTheme();
        saveThemeToCookie();  
    }
    private String currentTheme = "theme-light";

    public void switchTheme() {
        if (currentTheme.equals("theme-light")) {
            currentTheme = "theme-dark";
        } else {
            currentTheme = "theme-light";
        }
    }

    public String currentThemeToIcon() {
        if ("theme-dark".equals(currentTheme)) {
            return "🌚";
        } else {
            return "🌞";
        }
    }

    public void loadThemeFromCookie() {
        HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("theme".equals(cookie.getName())) {
                    currentTheme = cookie.getValue();
                    break;
                }
            }
        }
    }

    public void saveThemeToCookie() {
        HttpServletResponse response = (HttpServletResponse) FacesContext.getCurrentInstance().getExternalContext().getResponse();
        Cookie themeCookie = new Cookie("theme", currentTheme);
        themeCookie.setMaxAge(60 * 60 * 24 * 30); // Cookie valid for 30 days
        themeCookie.setPath("/");
        response.addCookie(themeCookie);
    } 

    public static void reloadCurrentPage() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        ExternalContext externalContext = facesContext.getExternalContext();

        String viewId = facesContext.getViewRoot().getViewId();
        String url = externalContext.getRequestContextPath() + viewId;

        try {
            externalContext.redirect(url);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
