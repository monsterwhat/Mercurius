package Controllers;

import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import Services.AlertasService;
import jakarta.faces.context.ExternalContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Named("ThemeController")
@SessionScoped
@Getter @Setter @ToString @EqualsAndHashCode
public class ThemeController implements Serializable {

    @PostConstruct
    public void init() {
        loadThemeFromCookie();
    }

    public synchronized void switchThemeAndSave() {
        switchTheme();
        saveThemeToCookie();  
        reloadCurrentPage();
    }

    public synchronized void saveThemeFromAjax() {
        HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
        String theme = request.getParameter("theme");
        if (theme != null) {
            this.currentTheme = theme;
            saveThemeToCookie();
        }
    }
    @Inject
    @Nonnull
    private AlertasService alertas;

    @Nonnull
    private String currentTheme = "light";

    public void switchTheme() {
        if (currentTheme.equals("light")) {
            currentTheme = "dark";
        } else {
            currentTheme = "light";
        }
    }

    @Nonnull
    public String getDataTheme() {
        return currentTheme;
    }

    @Nonnull
    public String currentThemeToIcon() {
        if ("dark".equals(currentTheme)) {
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

    public void reloadCurrentPage() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        ExternalContext externalContext = facesContext.getExternalContext();

        String viewId = facesContext.getViewRoot().getViewId();
        String url = externalContext.getRequestContextPath() + viewId;

        try {
            externalContext.redirect(url);
        } catch (IOException e) {
            alertas.registrarAlerta("Error", "Error al redirigir tema: " + e.getMessage(), null, 0, "ThemeController.cambiarTema()", null, e.getMessage());
        }
    }

}
