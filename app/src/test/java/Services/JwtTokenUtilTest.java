package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT token families must not be interchangeable, and a weak key must not boot.
 *
 * <p>Behavioral pin for two fixes in {@link JwtTokenUtil}:</p>
 *
 * <ol>
 *   <li><b>Zero-padding of short secrets.</b> A secret shorter than 32 bytes was
 *       padded with zeros to reach the 256-bit minimum HS256 needs, with only a
 *       warning. A 10-byte operator secret therefore became 10 bytes of entropy
 *       plus 22 zero bytes — the documented "256-bit minimum" was never actually
 *       enforced, and the HMAC key was brute-forceable. Now it fails the
 *       constructor.</li>
 *   <li><b>Cross-family token acceptance.</b> Both token shapes were signed with
 *       the same key and carried no {@code iss}/{@code aud}, and neither
 *       validator read the {@code type} claim, so a marketplace token was
 *       accepted by {@code validateApiToken}. In {@code PublicApiJwtFilter} that
 *       matters concretely: the API branch looks the client up by subject, a
 *       marketplace subject is an integer that matches no {@code ClientesApi},
 *       and the rate-limit block is guarded by {@code if (client != null)} — so
 *       such a token reached {@code /api/v1/**} with no limiter applied at all.</li>
 * </ol>
 */
@DisplayName("JwtTokenUtil: familias de token y minima entropia de clave")
class JwtTokenUtilTest {

    /** 32 bytes of real entropy, Base64 encoded (44 chars). */
    private static final String SECRETO_VALIDO =
            Base64.getEncoder().encodeToString(
                    "clave-de-prueba-de-32-bytes-entera!!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static JwtTokenUtil util() {
        return new JwtTokenUtil(SECRETO_VALIDO, 15, 7);
    }

    // ── minima entropia ───────────────────────────────────────────────────

    @Test
    @DisplayName("un secreto de menos de 32 bytes impide el arranque")
    void secretoCortoFallaElArranque() {
        assertThatThrownBy(() -> new JwtTokenUtil("corto", 15, 7))
                .as("rellenar con ceros no es cumplir el minimo de 256 bits")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bits");

        assertThatThrownBy(() -> new JwtTokenUtil(
                Base64.getEncoder().encodeToString("quince bytes de clave".getBytes()),
                15, 7))
                .as("tambien en base64: 19 bytes no alcanzan 32")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un secreto de exactamente 32 bytes si se acepta")
    void secretoDe32BytesSeAcepta() {
        String exacto = Base64.getEncoder().encodeToString(new byte[32]);
        assertThat(new JwtTokenUtil(exacto, 15, 7)).isNotNull();
    }

    @Test
    @DisplayName("el secreto por defecto de dev/test arranca (no es corto)")
    void elSecretoPorDefectoArranca() {
        String porDefecto = "changeme-this-is-not-a-secure-key-in-production-256bits!!";
        assertThat(porDefecto.length())
                .as("si esto dejara de cumplir el minimo, %dev y %test no arrancarian")
                .isGreaterThanOrEqualTo(32);
        assertThat(new JwtTokenUtil(porDefecto, 15, 7)).isNotNull();
    }

    // ──Round trip de cada familia ─────────────────────────────────────────

    @Test
    @DisplayName("un token de mercado se valida como token de mercado")
    void tokenDeMercadoValida() {
        JwtTokenUtil u = util();
        String token = u.generateAccessToken(42);

        assertThat(u.validateAccessToken(token)).isEqualTo(42);
    }

    @Test
    @DisplayName("un token de API se valida como token de API con sus scopes")
    void tokenDeApiValida() {
        JwtTokenUtil u = util();
        String token = u.generateApiAccessToken("mercurius-frontend", Set.of("mercatus", "accounting"));

        Claims claims = u.validateApiToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("mercurius-frontend");
        assertThat(claims.get("scope").toString()).contains("mercatus").contains("accounting");
    }

    // ── las familias no se intercambian ───────────────────────────────────

    @Test
    @DisplayName("un token de mercado NO se acepta como token de API")
    void tokenDeMercadoNoSirveParaLaApi() {
        JwtTokenUtil u = util();
        String token = u.generateAccessToken(42);

        assertThat(u.validateApiToken(token))
                .as("si se aceptara, entraba a /api/v1/** sin limitador de tasa")
                .isNull();
    }

    @Test
    @DisplayName("un token de API NO se acepta como token de mercado")
    void tokenDeApiNoSirveParaElMercado() {
        JwtTokenUtil u = util();
        String token = u.generateApiAccessToken("mercurius-frontend", Set.of("mercatus"));

        assertThat(u.validateAccessToken(token))
                .as("un token de cliente API no es un comprador del mercado")
                .isNull();
    }

    // ── el claim type se exige de verdad ──────────────────────────────────

    @Test
    @DisplayName("un token con la firma correcta pero sin issuer/audience se rechaza")
    void tokenSinIssuerNiAudienceSeRechaza() {
        // Firmado con la MISMA clave que el servicio usa, pero construido sin
        // iss/aud: imita un token emitido por otra version del servicio o por
        // otro despliegue que comparta el secreto. La firma valida, asi que
        // solo el chequeo de emisor/audiencia puede rechazarlo.
        JwtTokenUtil u = util();
        String fabricado = firmarSinIssuer(SECRETO_VALIDO, "7");

        assertThat(u.validateAccessToken(fabricado))
                .as("una firma valida no basta: deben validar emisor y audiencia")
                .isNull();
        assertThat(u.validateApiToken(fabricado)).isNull();
    }

    @Test
    @DisplayName("un token de otro secreto se rechaza")
    void tokenDeOtroSecretoSeRechaza() {
        String otroSecreto = Base64.getEncoder().encodeToString(
                "otra-clave-distinta-de-32-bytes-ok!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JwtTokenUtil atacante = new JwtTokenUtil(otroSecreto, 15, 7);
        String token = atacante.generateAccessToken(1);

        assertThat(util().validateAccessToken(token)).isNull();
    }

    @Test
    @DisplayName("un token corrupto o vacio devuelve null, no lanza")
    void tokenInvalidoNoLanza() {
        JwtTokenUtil u = util();
        assertThat(u.validateAccessToken("no-es-un-jwt")).isNull();
        assertThat(u.validateAccessToken("")).isNull();
        assertThat(u.validateApiToken("a.b.c")).isNull();
    }

    /** Signs a token with the given secret but WITHOUT iss/aud/type. */
    private static String firmarSinIssuer(String secretoBase64, String subject) {
        byte[] clave = Base64.getDecoder().decode(secretoBase64);
        String token = io.jsonwebtoken.Jwts.builder()
                .subject(subject)
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(clave))
                .compact();
        return token;
    }
}
