package Models.DTO;

import jakarta.annotation.Nonnull;

/**
 * Login request payload for Mercatus client authentication.
 */
public class LoginRequest {

    @Nonnull
    private String email;

    @Nonnull
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(@Nonnull String email, @Nonnull String password) {
        this.email = email;
        this.password = password;
    }

    @Nonnull
    public String getEmail() {
        return email;
    }

    public void setEmail(@Nonnull String email) {
        this.email = email;
    }

    @Nonnull
    public String getPassword() {
        return password;
    }

    public void setPassword(@Nonnull String password) {
        this.password = password;
    }
}
