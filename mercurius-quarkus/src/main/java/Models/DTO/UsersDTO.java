package Models.DTO;

import jakarta.annotation.Nullable;

/**
 * Read-side view of a system user (entity {@code Models.Users}) for the
 * Usuarios administration pages.
 *
 * SECURITY: the password hash field is intentionally excluded from this DTO.
 * Never add it here — expose credentials only through the authentication flow.
 */
public class UsersDTO {
    private Long id;
    private String username;
    @Nullable private String email;
    private String groupName;
    @Nullable private Boolean status; // Archivar o deshabilitar

    public UsersDTO() {}

    public UsersDTO(Long id, String username, @Nullable String email,
                    String groupName, @Nullable Boolean status) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.groupName = groupName;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    @Nullable
    public String getEmail() { return email; }
    public void setEmail(@Nullable String email) { this.email = email; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    @Nullable
    public Boolean getStatus() { return status; }
    public void setStatus(@Nullable Boolean status) { this.status = status; }
}
