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

import org.jboss.logging.Logger;

/**
/**
 * Utility for JWT access token generation and validation for Mercatus marketplace clients.
 * Access tokens are short-lived JWTs signed with HMAC-SHA256.
 * Refresh tokens are opaque UUIDs stored in the database.
 *
 * <p><b>Two token families, one signing key, separated by iss/aud and an
 * enforced type claim.</b> This service mints two shapes: marketplace access
 * tokens ({@link #generateAccessToken}) and OAuth client-credentials tokens
 * ({@link #generateApiAccessToken}). Previously all of them were signed with the
 * same key and carried no issuer or audience, and neither validator inspected
 * the {@code type} claim -- so a token minted for the marketplace was accepted by
 * {@link #validateApiToken} and vice versa.</p>
 *
 * <p>That was not cosmetic. In {@code PublicApiJwtFilter} the API branch looks
 * the client up by the token subject, and a marketplace token's subject is an
 * integer client code that matches no {@code ClientesApi.client_id}; the
 * rate-limit block is guarded by {@code if (client != null)}, so such a token
 * entered the authenticated branch with <em>no limiter applied at all</em>.
 * Audience checking closes that.</p>
 */
@ApplicationScoped
public class JwtTokenUtil {

    private static final Logger LOG = Logger.getLogger(JwtTokenUtil.class);

    /** Issuer stamped on every token this service mints. */
    public static final String ISSUER = "mercurius";

    /** Audience for marketplace (buyer) access tokens. */
    public static final String AUDIENCIA_MERCATUS = "mercurius-mercatus";

    /** Audience for OAuth2 client-credentials tokens. */
    public static final String AUDIENCIA_API = "mercurius-api";

    private static final String TIPO_ACCESS = "access";
    private static final String TIPO_API_ACCESS = "api_access";

    /** HS256 requires at least 256 bits of key material. */
    private static final int MINIMOS_BYTES_CLAVE = 32;

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    public JwtTokenUtil(
            @ConfigProperty(name = "mercatus.jwt.secret") @Nonnull String secret,
            @ConfigProperty(name = "mercatus.jwt.expiry-minutes", defaultValue = "15") long expiryMinutes,
            @ConfigProperty(name = "mercatus.jwt.refresh-expiry-days", defaultValue = "7") long refreshTokenExpiryDays) {
        // Decode Base64 secret if provided in that format, otherwise use raw string
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        // Fail fast on a short key. This used to zero-pad up to 32 bytes, which
        // turned a 10-byte secret into 10 bytes of entropy followed by 22 zero
        // bytes and emitted only a warning: the service looked like it enforced
        // the documented 256-bit minimum while actually accepting a trivially
        // brute-forceable HMAC key. A weak signing key is a deployment error, so
        // it stops the boot instead of degrading silently.
        if (keyBytes.length < MINIMOS_BYTES_CLAVE) {
            throw new IllegalArgumentException(
                    "mercatus.jwt.secret debe aportar al menos " + MINIMOS_BYTES_CLAVE
                    + " bytes (256 bits) para HS256; se recibieron " + keyBytes.length
                    + ". No se rellena con ceros: eso reduce la entropia real de la clave. "
                    + "Genere una con: openssl rand -base64 32");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiryMs = expiryMinutes * 60 * 1000;
        this.refreshTokenExpiryMs = refreshTokenExpiryDays * 24 * 60 * 60 * 1000L;
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
                .issuer(ISSUER)
                .audience().add(AUDIENCIA_MERCATUS).and()
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", TIPO_ACCESS)
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
     * Validates a MARKETPLACE access token and extracts the client code.
     *
     * <p>Requires issuer, the marketplace audience AND the {@code access} type
     * claim. All three are load-bearing: without the audience check an OAuth
     * client-credentials token (subject = a string client id) is accepted here,
     * and without the type check the reverse holds.</p>
     *
     * @param token the JWT string
     * @return client code if valid, null otherwise
     */
    @Nullable
    public Integer validateAccessToken(@Nonnull String token) {
        Claims payload = validar(token, AUDIENCIA_MERCATUS, TIPO_ACCESS);
        if (payload == null) {
            return null;
        }
        String subject = payload.getSubject();
        if (subject == null) {
            LOG.warn("JWT token has no subject claim");
            return null;
        }
        try {
            return Integer.parseInt(subject);
        } catch (NumberFormatException e) {
            LOG.warn("JWT subject is not a client code: " + subject);
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
                .issuer(ISSUER)
                .audience().add(AUDIENCIA_API).and()
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", TIPO_API_ACCESS)
                .claim("scope", String.join(" ", scopes))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Shared parse-and-verify: signature, then issuer, audience and type.
     *
     * <p>Rejects anything minted for the other token family, which is what stops
     * a marketplace token from being replayed against {@code /api/v1/**} and
     * thereby skipping the per-client rate limiter.</p>
     *
     * @return the payload, or {@code null} for any failure.
     */
    @Nullable
    private Claims validar(@Nonnull String token,
                           @Nonnull String audienciaEsperada,
                           @Nonnull String tipoEsperado) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(audienciaEsperada)
                    .build()
                    .parseSignedClaims(token);

            Claims payload = jws.getPayload();
            Object tipo = payload.get("type");
            if (!tipoEsperado.equals(tipo)) {
                LOG.warn("JWT con tipo inesperado: se esperaba " + tipoEsperado
                        + " y se recibio " + tipo);
                return null;
            }
            return payload;
        } catch (ExpiredJwtException e) {
            LOG.debug("JWT token expired", e);
            return null;
        } catch (io.jsonwebtoken.ClaimJwtException e) {
            // Covers BOTH MissingClaimException and IncorrectClaimException, i.e.
            // a token with no iss/aud and a token with the wrong iss/aud. The
            // narrower IncorrectClaimException is not enough: a token minted
            // before these claims existed carries neither, which surfaces as
            // MissingClaimException and would otherwise escape as an uncaught
            // RuntimeException — turning an authentication failure into a 500
            // instead of the 401 the caller expects.
            LOG.warn("JWT con emisor o audiencia ausentes/incorrectos: " + e.getMessage());
            return null;
        } catch (MalformedJwtException | SecurityException | IllegalArgumentException e) {
            LOG.warn("Invalid JWT token", e);
            return null;
        }
    }

    /**
     * Validates an API (client-credentials) access token and returns the Claims.
     *
     * <p>Requires issuer, the API audience and the {@code api_access} type claim,
     * so a marketplace token is rejected here — which is what stops one from
     * reaching {@code /api/v1/**} with the per-client rate limiter skipped.</p>
     *
     * @return Claims payload if valid, null otherwise
     */
    @Nullable
    public Claims validateApiToken(@Nonnull String token) {
        return validar(token, AUDIENCIA_API, TIPO_API_ACCESS);
    }
}
