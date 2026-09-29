package Services;

import Models.Clientes;
import Models.DTO.AuthResponse;
import Models.DTO.LoginRequest;
import Models.DTO.RegisterRequest;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.util.Date;
import java.util.List;

import org.jboss.logging.Logger;

/**
 * Authentication service for Mercatus marketplace clients.
 * Handles registration, login, and token refresh.
 */
@ApplicationScoped
public class ClientAuthService {

    private static final Logger LOG = Logger.getLogger(ClientAuthService.class);

    @Inject
    @Nonnull
    JwtTokenUtil jwtTokenUtil;

    @Inject
    @Nonnull
    ClientService clientService;

    /**
     * Registers a new client for marketplace access.
     *
     * @param request registration details
     * @return auth response with tokens
     * @throws IllegalArgumentException if email already exists or validation fails
     */
    @Transactional
    @Nonnull
    public AuthResponse register(@Nonnull RegisterRequest request) {
        // Validate required fields
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("El nombre es requerido");
        }
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new IllegalArgumentException("El correo electrónico es requerido");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres");
        }

        // Check if email already exists
        if (findByEmail(request.getEmail()) != null) {
            throw new IllegalArgumentException("El correo electrónico ya está registrado");
        }

        // Create client entity
        Clientes client = new Clientes();
        client.setName(request.getName().trim());
        client.setEmail(request.getEmail().trim().toLowerCase());
        client.setPassword(hashPassword(request.getPassword()));
        client.setStatus(true);

        if (request.getIdType() != null && !request.getIdType().isBlank()) {
            client.setIdType(request.getIdType().trim());
            client.setTipoIdentificacion(request.getIdType().trim());
        }
        if (request.getIdNumber() != null && !request.getIdNumber().isBlank()) {
            client.setIdNumber(request.getIdNumber().trim());
        }
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            client.setPhoneNumber(request.getPhoneNumber().trim());
        }
        if (request.getAddress() != null && !request.getAddress().isBlank()) {
            client.setAddress(request.getAddress().trim());
        }

        clientService.create(client);

        // Generate tokens
        return buildAuthResponse(client);
    }

    /**
     * Authenticates a client with email and password.
     *
     * @param request login credentials
     * @return auth response with tokens
     * @throws IllegalArgumentException if credentials are invalid
     */
    @Nonnull
    public AuthResponse login(@Nonnull LoginRequest request) {
        Clientes client = findByEmail(request.getEmail().trim().toLowerCase());
        if (client == null) {
            throw new IllegalArgumentException("Credenciales inválidas");
        }

        if (client.getPassword() == null) {
            throw new IllegalArgumentException("Esta cuenta no tiene acceso al mercado en línea. Contacte al administrador.");
        }

        if (!verifyPassword(request.getPassword(), client.getPassword())) {
            throw new IllegalArgumentException("Credenciales inválidas");
        }

        if (client.getStatus() != null && !client.getStatus()) {
            throw new IllegalArgumentException("La cuenta está desactivada");
        }

        return buildAuthResponse(client);
    }

    /**
     * Refreshes an access token using a valid refresh token.
     *
     * @param refreshToken the refresh token string
     * @return new auth response with fresh tokens
     * @throws IllegalArgumentException if refresh token is invalid or expired
     */
    @Transactional
    @Nonnull
    public AuthResponse refreshAccessToken(@Nonnull String refreshToken) {
        Clientes client = findByRefreshToken(refreshToken);
        if (client == null) {
            throw new IllegalArgumentException("Token de actualización inválido");
        }

        if (client.getTokenExpiry() != null && client.getTokenExpiry().before(new Date())) {
            client.setRefreshToken(null);
            client.setTokenExpiry(null);
            clientService.update(client);
            throw new IllegalArgumentException("Token de actualización expirado. Inicie sesión nuevamente.");
        }

        if (client.getStatus() != null && !client.getStatus()) {
            throw new IllegalArgumentException("La cuenta está desactivada");
        }

        return buildAuthResponse(client);
    }

    /**
     * Retrieves a client by their database code.
     */
    @Nullable
    public Clientes findByCode(int clientCode) {
        return clientService.find(clientCode);
    }

    /**
     * Builds an auth response with fresh tokens for the given client.
     *
     * <p>The refresh token is stored as a SHA-256 digest, never raw. A raw
     * refresh token is a bearer credential: anyone who reads it (backup file,
     * dump archive, over-broad API read, a future SQLi) can replay it for a
     * fresh access token for up to 7 days. The digest is not replayable, and
     * SHA-256 (not BCrypt) is the right tool here: the token is a 128-bit
     * random UUID, not a human password, so no stretching is needed and the
     * lookup stays a single indexed equality. The client still receives the
     * raw token; only the stored copy is digested.</p>
     *
     * <p>Migration note: rows written before this change hold raw tokens and
     * will no longer match — those sessions must log in again. That silent
     * invalidation is intentional and safer than accepting both formats.</p>
     */
    @Transactional
    @Nonnull
    AuthResponse buildAuthResponse(@Nonnull Clientes client) {
        String accessToken = jwtTokenUtil.generateAccessToken(client.getCode());
        String refreshToken = jwtTokenUtil.generateRefreshToken();
        Date refreshExpiry = jwtTokenUtil.getRefreshTokenExpiry();

        // Store refresh token digest in database (never the raw bearer value)
        client.setRefreshToken(sha256Hex(refreshToken));
        client.setTokenExpiry(refreshExpiry);
        clientService.update(client);

        long expiresInSeconds = 15 * 60; // 15 minutes, matches default config
        AuthResponse.ClientInfo clientInfo = new AuthResponse.ClientInfo(
                client.getCode(),
                client.getName(),
                client.getEmail() != null ? client.getEmail() : ""
        );

        return new AuthResponse(accessToken, refreshToken, expiresInSeconds, clientInfo);
    }

    /**
     * SHA-256 hex digest for refresh-token storage and lookup.
     *
     * <p>MessageDigest is instantiated per call because it is not thread-safe;
     * SHA-256 is guaranteed present on every JDK, so the catch is defensive
     * only and fails closed.</p>
     */
    @Nonnull
    static String sha256Hex(@Nonnull String valor) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(valor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    /**
     * Finds a client by email address.
     */
    @Nullable
    Clientes findByEmail(@Nonnull String email) {
        try {
            TypedQuery<Clientes> query = clientService.em.createQuery(
                    "SELECT c FROM Clientes c WHERE LOWER(c.email) = :email", Clientes.class);
            query.setParameter("email", email.toLowerCase());
            List<Clientes> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
            LOG.warn("Error finding client by email", e);
            return null;
        }
    }

    /**
     * Finds a client by refresh token.
     *
     * <p>The presented raw token is digested before lookup because only
     * digests are stored (see {@link #buildAuthResponse}). A raw token taken
     * from a database read therefore matches nothing.</p>
     */
    @Nullable
    Clientes findByRefreshToken(@Nonnull String refreshToken) {
        try {
            TypedQuery<Clientes> query = clientService.em.createQuery(
                    "SELECT c FROM Clientes c WHERE c.refreshToken = :token", Clientes.class);
            query.setParameter("token", sha256Hex(refreshToken));
            List<Clientes> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
            LOG.warn("Error finding client by refresh token", e);
            return null;
        }
    }

    /**
     * Hashes a password using BCrypt. Reuses the same algorithm as LoginService.
     */
    @Nonnull
    String hashPassword(@Nonnull String password) {
        return at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, password.toCharArray());
    }

    /**
     * Verifies a password against a BCrypt hash.
     */
    boolean verifyPassword(@Nonnull String password, @Nonnull String hashedPassword) {
        return at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(password.toCharArray(), hashedPassword).verified;
    }
}
