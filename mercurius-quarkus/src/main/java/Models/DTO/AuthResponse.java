package Models.DTO;

import jakarta.annotation.Nonnull;

/**
 * Authentication response with JWT access token, refresh token, and client info.
 */
public class AuthResponse {

    @Nonnull
    private String accessToken;

    @Nonnull
    private String refreshToken;

    @Nonnull
    private String tokenType; // "Bearer"

    private long expiresIn; // seconds until access token expires

    @Nonnull
    private ClientInfo client;

    public AuthResponse() {
    }

    public AuthResponse(@Nonnull String accessToken, @Nonnull String refreshToken,
                        long expiresIn, @Nonnull ClientInfo client) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenType = "Bearer";
        this.expiresIn = expiresIn;
        this.client = client;
    }

    @Nonnull
    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(@Nonnull String accessToken) {
        this.accessToken = accessToken;
    }

    @Nonnull
    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(@Nonnull String refreshToken) {
        this.refreshToken = refreshToken;
    }

    @Nonnull
    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(@Nonnull String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    @Nonnull
    public ClientInfo getClient() {
        return client;
    }

    public void setClient(@Nonnull ClientInfo client) {
        this.client = client;
    }

    /**
     * Minimal client info included in auth responses.
     */
    public static class ClientInfo {
        private int code;
        @Nonnull private String name;
        @Nonnull private String email;

        public ClientInfo() {
        }

        public ClientInfo(int code, @Nonnull String name, @Nonnull String email) {
            this.code = code;
            this.name = name;
            this.email = email;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        public void setName(@Nonnull String name) {
            this.name = name;
        }

        @Nonnull
        public String getEmail() {
            return email;
        }

        public void setEmail(@Nonnull String email) {
            this.email = email;
        }
    }
}
