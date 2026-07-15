package Services;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for JWT access token generation and validation for Mercatus marketplace clients.
 * Access tokens are short-lived JWTs signed with HMAC-SHA256.
 * Refresh tokens are opaque UUIDs stored in the database.
 */
@ApplicationScoped
public class JwtTokenUtil {

    private static final Logger LOG = Logger.getLogger(JwtTokenUtil.class.getName());

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    public JwtTokenUtil(
            @ConfigProperty(name = "mercatus.jwt.secret") @Nonnull String secret,
            @ConfigProperty(name = "mercatus.jwt.expiry-minutes", defaultValue = "15") long expiryMinutes,
            @ConfigProperty(name = "mercatus.jwt.refresh-expiry-days", defaultValue = "7") long refreshExpiryDays) {
        // Decode Base64 secret if provided in that format, otherwise use raw string
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        // Ensure minimum 256-bit key for HS256
        if (keyBytes.length < 32) {
            LOG.warning("JWT secret is less than 256 bits. Padding to minimum length.");
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiryMs = expiryMinutes * 60 * 1000;
        this.refreshTokenExpiryMs = refreshExpiryDays * 24 * 60 * 60 * 1000L;
    }

    /**
     * Generates a JWT access token for the given client code.
     *
     * @param clientCode the client's unique identifier
     * @return signed JWT string
     */
    @Nonnull
    public String generateAccessToken(int clientCode) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiryMs);

        return Jwts.builder()
                .subject(String.valueOf(clientCode))
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", "access")
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generates an opaque refresh token (UUID).
     *
     * @return unique refresh token string
     */
    @Nonnull
    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * Calculates the refresh token expiry date.
     *
     * @return date when refresh token expires
     */
    @Nonnull
    public Date getRefreshTokenExpiry() {
        return new Date(System.currentTimeMillis() + refreshTokenExpiryMs);
    }

    /**
     * Validates a JWT access token and extracts the client code.
     *
     * @param token the JWT string
     * @return client code if valid, null otherwise
     */
    @Nullable
    public Integer validateAccessToken(@Nonnull String token) {
        try {
            Jws<Claims> claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);

            String subject = claims.getPayload().getSubject();
            if (subject == null) {
                LOG.warning("JWT token has no subject claim");
                return null;
            }
            return Integer.parseInt(subject);
        } catch (ExpiredJwtException e) {
            LOG.log(Level.FINE, "JWT token expired", e);
            return null;
        } catch (MalformedJwtException | SecurityException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Invalid JWT token", e);
            return null;
        }
    }

    /**
     * Generates a JWT access token for an API client with scope claims.
     * Used by OAuth2 client_credentials grant.
     *
     * @param clientId the API client's public identifier
     * @param scopes   set of granted scopes (e.g. "mercatus", "accounting")
     * @return signed JWT string
     */
    @Nonnull
    public String generateApiAccessToken(@Nonnull String clientId, @Nonnull Set<String> scopes) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiryMs);

        return Jwts.builder()
                .subject(clientId)
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", "api_access")
                .claim("scope", String.join(" ", scopes))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates an API access token and returns the Claims (including scope).
     * Returns null if token is invalid or expired.
     *
     * @param token the JWT string
     * @return Claims payload if valid, null otherwise
     */
    @Nullable
    public Claims validateApiToken(@Nonnull String token) {
        try {
            Jws<Claims> claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            return claims.getPayload();
        } catch (ExpiredJwtException e) {
            LOG.log(Level.FINE, "API JWT token expired", e);
            return null;
        } catch (MalformedJwtException | SecurityException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Invalid API JWT token", e);
            return null;
        }
    }
}
