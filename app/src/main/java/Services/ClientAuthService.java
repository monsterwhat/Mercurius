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

    /**
     * Unico texto que sale por el cable cuando un login se rechaza.
     *
     * <p>Las cuatro salidas negativas de {@link #login(LoginRequest, String)} —
     * correo no registrado, cuenta sin password de mercado, contrasena
     * incorrecta y cuenta desactivada — devuelven EXACTAMENTE esta cadena. No
     * es cosmetico: las tres ultimas solo son alcanzables con un correo que ya
     * esta registrado, asi que un texto propio por rama convertia el 401 en un
     * oraculo de enumeracion de usuarios por el cuerpo de la respuesta. El
     * coste de BCrypt ya estaba igualado con
     * {@link LoginService#verificarContraHashFalso(String)}; esta constante
     * cierra el mismo hueco por el lado del cuerpo.</p>
     *
     * <p>La distincion NO se pierde del todo: cada rama escribe su propio
     * {@code LOG.debug} con el motivo real, de modo que el operador sigue
     * pudiendo diagnosticar en el servidor. Lo que no debe volver a funcionar es
     * que el cliente lo pueda leer.</p>
     */
    @Nonnull
    public static final String MENSAJE_CREDENCIALES_INVALIDAS = "Credenciales inválidas";

    @Inject
    @Nonnull
    JwtTokenUtil jwtTokenUtil;

    @Inject
    @Nonnull
    ClientService clientService;

    /**
     * Bounds password guessing against {@link #login(LoginRequest, String)}.
     *
     * <p>Counted per account (the email, normalized inside
     * {@link Utils.IntentosDeCredencial#claveCuenta}) AND per source address, so
     * neither one target being ground down nor one caller spraying many targets
     * is possible: the account key alone would not catch the spray, because
     * every victim would stay under its own threshold.</p>
     */
    @Inject
    @Nonnull
    Utils.IntentosDeCredencial intentosDeCredencial;

    /**
     * Injected ONLY for {@link LoginService#verificarContraHashFalso(String)} —
     * the equalization helper the two form-auth BCrypt oracles already use. It
     * is stateless for this purpose (a throwaway cost-12 hash), so reusing it
     * here keeps a single dummy-hash implementation in the codebase.
     */
    @Inject
    @Nonnull
    LoginService loginService;

    /**
     * EntityManager propio de este bean, usado por las dos consultas de lectura.
     *
     * <p>No es un detalle de estilo: estas consultas se hacian antes con
     * {@code clientService.em}, es decir leyendo un <em>campo</em> de otro bean a
     * traves del client proxy de ArC. El proxy arrastra su propia copia del campo
     * y ArC no la inyecta, de modo que esa lectura devolvia {@code null} y
     * <strong>todo</strong> login de marketplace terminaba en
     * {@code NullPointerException} -&gt; 500, sin llegar a la verificacion ni al
     * limiter. Un metodo invocado a traves del proxy si se ejecuta sobre la
     * instancia real (con su EntityManager); un campo no. Injectarlo aqui
     * replica exactamente el mecanismo que ya usa {@code GService.em} y que si
     * funciona sobre peticiones HTTP.</p>
     */
    @jakarta.persistence.PersistenceContext
    @Nonnull
    jakarta.persistence.EntityManager em;

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
     * <p><b>Throttled and timing-equalized.</b> This verifies a BCrypt hash for
     * an <em>arbitrary, caller-supplied</em> email, so unthrottled it was both a
     * password-guessing oracle and — because the not-found and no-market-access
     * branches returned without ever invoking BCrypt — a username-enumeration
     * oracle measurable from outside: those answered in microseconds while a
     * wrong password cost a full cost-12 verification.</p>
     *
     * <p>Both are closed here, mirroring
     * {@code AppAuthResource.supervisorAuthorize()}: {@link
     * Utils.IntentosDeCredencial} bounds the attempts per account and per source
     * address, and every branch that rejects without a real verification spends
     * the same CPU via {@link LoginService#verificarContraHashFalso(String)}.</p>
     *
     * <p>Not-found and no-market-access ARE counted as failures even though no
     * stored hash was checked, matching that precedent: counting them keeps the
     * unknown-email and wrong-password paths indistinguishable, and it does not
     * let an attacker escape the budget by spraying unknown emails — those burn
     * the same per-address counter, so polling is bounded too.</p>
     *
     * <p>Every rejection throws {@link IllegalArgumentException} carrying the
     * same {@link #MENSAJE_CREDENCIALES_INVALIDAS}, so the body cannot be used
     * to tell which branch fired; the reason is logged per branch instead.</p>
     *
     * @param request login credentials
     * @param direccion source address for throttling. Passed in because only the
     *        JAX-RS resource can see the Vert.x request; see
     *        {@code AppAuthResource.direccionOrigen()}.
     * @return auth response with tokens
     * @throws IllegalArgumentException if credentials are invalid
     */
    @Nonnull
    public AuthResponse login(@Nonnull LoginRequest request, @Nullable String direccion) {
        String cuenta = request.getEmail();

        Clientes client = findByEmail(cuenta.trim().toLowerCase());
        if (client == null) {
            // Equalize: without this the branch answers in microseconds and
            // response time discloses which emails are registered.
            loginService.verificarContraHashFalso(request.getPassword());
            intentosDeCredencial.registrarFallo(cuenta, direccion);
            LOG.debug("Login rechazado: correo no registrado"
                    + " | source=ClientAuthService.login() | cuenta=" + cuenta);
            throw new IllegalArgumentException(MENSAJE_CREDENCIALES_INVALIDAS);
        }

        if (client.getPassword() == null) {
            // Same equalization as above: a client created by an admin has no
            // marketplace password, so no real BCrypt verification is reachable.
            loginService.verificarContraHashFalso(request.getPassword());
            intentosDeCredencial.registrarFallo(cuenta, direccion);
            LOG.debug("Login rechazado: cuenta sin password de mercado"
                    + " | source=ClientAuthService.login() | cuenta=" + cuenta
                    + " | cliente=" + client.getCode());
            throw new IllegalArgumentException(MENSAJE_CREDENCIALES_INVALIDAS);
        }

        if (!verifyPassword(request.getPassword(), client.getPassword())) {
            intentosDeCredencial.registrarFallo(cuenta, direccion);
            LOG.debug("Login rechazado: contrasena incorrecta"
                    + " | source=ClientAuthService.login() | cuenta=" + cuenta
                    + " | cliente=" + client.getCode());
            throw new IllegalArgumentException(MENSAJE_CREDENCIALES_INVALIDAS);
        }

        if (client.getStatus() != null && !client.getStatus()) {
            // Counted like any other rejected login (form-auth precedent), so a
            // disabled account cannot be probed for free. No equalizing hash is
            // needed here: the real verification above already ran.
            intentosDeCredencial.registrarFallo(cuenta, direccion);
            LOG.debug("Login rechazado: cuenta desactivada"
                    + " | source=ClientAuthService.login() | cuenta=" + cuenta
                    + " | cliente=" + client.getCode());
            throw new IllegalArgumentException(MENSAJE_CREDENCIALES_INVALIDAS);
        }

        AuthResponse response = buildAuthResponse(client);
        // Only after the tokens are actually built: a user who mistyped twice and
        // then got it right must not stay one mistake away from a lockout.
        intentosDeCredencial.registrarExito(cuenta, direccion);
        return response;
    }

    /**
     * Refreshes an access token using a valid refresh token.
     *
     * <p><b>Deliberately NOT throttled.</b> Unlike
     * {@link #login(LoginRequest, String)}, nothing is
     * guessed here: a refresh token is a 128-bit random bearer value, so the
     * attempt count does not measure a password-guessing rate. Replaying it is
     * governed by expiry and the {@code sha256} digest stored instead of the raw
     * token (see {@link #buildAuthResponse}).</p>
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
            throw new IllegalArgumentException(MENSAJE_CREDENCIALES_INVALIDAS);
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
     *
     * <p>Usa el EntityManager propio del bean; ver el comentario del campo
     * {@code em} para por que leerlo de {@code clientService} devolvia null.</p>
     */
    @Nullable
    Clientes findByEmail(@Nonnull String email) {
        try {
            TypedQuery<Clientes> query = em.createQuery(
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
            TypedQuery<Clientes> query = em.createQuery(
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
