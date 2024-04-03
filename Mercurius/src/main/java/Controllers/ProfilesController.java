package Controllers;

import Models.Users;
import Services.LoginService;
import Utils.FakeUserGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;
/**
 *
 * @author Al
 */

@Data
@Named(value = "ProfilesController")
@SessionScoped
public class ProfilesController implements Serializable{
    @Inject private LoginService profileService;
    @Inject ViewController viewManager;

    private List<Users> profiles;
    private Users selectedProfile;
    private Users newProfile;
    private String generatorOption;
    private String usernameFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;

    public ProfilesController() {
    }
    
    @PostConstruct
    public void init(){
        newProfile = new Users();
        selectedProfile = new Users();
        profilesList();
        
        filterBy = new ArrayList<>();        
    }
    
    public List<Users> profilesList(){
        if(profiles == null){
            profiles = profileService.listAll();
        }
        return profiles;
    }
    
    public long profileCount(){
        return profileService.count();
    }
    
    public void openNewProfile(){
        newProfile = new Users();
    }
    
    public void updateProfile(){
        profileService.update(selectedProfile);
        clearSelectedProfile();
    }
    
    public void createProfile(){
        profileService.create(newProfile);
        clearSelectedProfile();        
    }
    
    public void deleteProfile(){
        if(selectedProfile != null){
            if(selectedProfile.getId()!=null){
                profileService.delete(selectedProfile);
                clearSelectedProfile();
            }
        }
    }
    
    public void clearSelectedProfile(){
        profiles = null;
        newProfile = null;
        selectedProfile = null;
        viewManager.selectViewUsers();
    }
    
    public void generateAndCreateRandomUsers() {
        FakeUserGenerator userGenerator = new FakeUserGenerator();
        // Use the selectedGenerator value to determine the generator strategy
        Optional<String> generatorInput = Optional.ofNullable(generatorOption);
        if (generatorInput.isPresent()) {
        userGenerator.setUsernameGenerator(generatorInput.get());
        }
        
        for (int i = 0; i < 10; i++) {
            Users newUser = userGenerator.generateFakeUserProfile("user");
            profileService.create(newUser);
        }
        clearSelectedProfile();
    }
    
    public void generateAndCreateRandomAdmins() {
        FakeUserGenerator userGenerator = new FakeUserGenerator();
        
        Optional<String> generatorInput = Optional.ofNullable(generatorOption);
        if (generatorInput.isPresent()) {
        userGenerator.setUsernameGenerator(generatorInput.get());
        }
        
        for (int i = 0; i < 10; i++) {
            Users newUser = userGenerator.generateFakeUserProfile("admin");
            profileService.create(newUser);
        }
        clearSelectedProfile();
    }
    
    public List<Users> getFilteredProfiles() {
        if (usernameFilter != null && !usernameFilter.isEmpty()) {
            return profilesList().stream()
                    .filter(profile -> globalFilterFunction(profile, usernameFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return profilesList();
        }
    }
       
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Users user = (Users) value;
        return user.getUsername().toLowerCase().contains(filterText)
                || user.getGroupName().toLowerCase().contains(filterText);
    }

}
