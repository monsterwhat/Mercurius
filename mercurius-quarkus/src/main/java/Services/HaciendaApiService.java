package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Fallback;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.AppSettings;

@Named
@ApplicationScoped
public class HaciendaApiService {

    private static final String SANDBOX_BASE_URL = "https://api.comprobanteselectronicos.go.cr/recepcion-sandbox/v1";
    private static final String PRODUCTION_BASE_URL = "https://api.comprobanteselectronicos.go.cr/recepcion/v1";

    private final HaciendaCertificateService certificateService;

    @Inject
    public HaciendaApiService(HaciendaCertificateService certificateService) {
        this.certificateService = certificateService;
    }

    private String getCallbackUrl() {
        try {
            AppSettings settings = certificateService.getActiveSettings();
            if (settings != null && settings.getHaciendaCallbackUrl() != null
                    && !settings.getHaciendaCallbackUrl().isEmpty()) {
                return settings.getHaciendaCallbackUrl();
            }
        } catch (RuntimeException e) {
            // Log but don't break — callback URL is optional
        }
        return "";
    }

    public static class ApiResponse {
        public int statusCode;
        public String responseBody;
        public String errorMessage;

        public static ApiResponse ok(String body) {
            ApiResponse response = new ApiResponse();
            response.statusCode = 200;
            response.responseBody = body;
            return response;
        }

        public static ApiResponse error(int code, String message) {
            ApiResponse response = new ApiResponse();
            response.statusCode = code;
            response.errorMessage = message;
            return response;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public static class TokenResponse {
        public String accessToken;
        public int expiresIn;
        public String tokenType;
        public String refreshToken;
        public int refreshExpiresIn;
        public String error;

        public boolean isSuccess() {
            return accessToken != null && !accessToken.isEmpty();
        }
    }

    // ── In-memory token cache (ApplicationScoped → singleton) ────────────────
    private String cachedAccessToken;
    private String cachedRefreshToken;
    private LocalDateTime tokenExpiry;
    private LocalDateTime refreshExpiry;

    private synchronized boolean isCacheValid() {
        // Refresh 30s before actual expiry to prevent 401 during API calls
        return cachedAccessToken != null
            && tokenExpiry != null
            && LocalDateTime.now().plusSeconds(30).isBefore(tokenExpiry);
    }

    private synchronized boolean hasValidRefreshToken() {
        return cachedRefreshToken != null
            && refreshExpiry != null
            && LocalDateTime.now().isBefore(refreshExpiry);
    }

    private synchronized void updateCache(TokenResponse token) {
        this.cachedAccessToken = token.accessToken;
        this.cachedRefreshToken = token.refreshToken;
        this.tokenExpiry = LocalDateTime.now().plusSeconds(token.expiresIn);
        this.refreshExpiry = token.refreshExpiresIn > 0
            ? LocalDateTime.now().plusSeconds(token.refreshExpiresIn)
            : null;
        certificateService.saveTokenExpiry(tokenExpiry);
    }

    public synchronized void clearTokenCache() {
        this.cachedAccessToken = null;
        this.cachedRefreshToken = null;
        this.tokenExpiry = null;
        this.refreshExpiry = null;
        certificateService.saveTokenExpiry(null);
    }

    private String getBaseUrl() {
        String environment = certificateService.getEnvironment();
        return "production".equalsIgnoreCase(environment) 
            ? PRODUCTION_BASE_URL 
            : SANDBOX_BASE_URL;
    }

    private String getTokenUrl() {
        String environment = certificateService.getEnvironment();
        String realm = "production".equalsIgnoreCase(environment) ? "rut" : "rut-stag";
        return "https://idp.comprobanteselectronicos.go.cr/auth/realms/" + realm + "/protocol/openid-connect/token";
    }

    private String getClientId() {
        String environment = certificateService.getEnvironment();
        return "production".equalsIgnoreCase(environment) ? "api-prod" : "api-stag";
    }

    private String buildIdpUsername(AppSettings settings) {
        if (settings == null) return null;
        String tipoId = settings.getTipoIdentificacion();
        String idNumber = settings.getIdentificacion();
        if (tipoId == null || tipoId.isEmpty() || idNumber == null || idNumber.isEmpty()) return null;

        String prefix;
        switch (tipoId) {
            case "01": prefix = "cpf"; break;  // Persona Física
            case "02": prefix = "cpj"; break;  // Persona Jurídica
            case "03": prefix = "ced"; break;  // DIMEX
            case "04": prefix = "nite"; break; // NITE
            default: prefix = "cpf"; break;
        }

        String environment = certificateService.getEnvironment();
        String envPrefix = "production".equalsIgnoreCase(environment) ? "prod" : "stag";
        return prefix + "-" + tipoId + "-" + idNumber + "@" + envPrefix + ".comprobanteselectronicos.go.cr";
    }

    /**
     * Returns a valid access token using the cache-first strategy:
     * 1. If cached token is still valid (with 30s buffer) → return it
     * 2. If refresh token is still valid → refresh access token
     * 3. Otherwise → full ROPC authentication
     */
    public synchronized TokenResponse getAccessToken() {
        // ── 1. Return cached token if still valid ────────────────────────────
        if (isCacheValid()) {
            TokenResponse result = new TokenResponse();
            result.accessToken = cachedAccessToken;
            result.expiresIn = (int) java.time.Duration.between(LocalDateTime.now(), tokenExpiry).getSeconds();
            result.tokenType = "Bearer";
            return result;
        }

        // ── 2. Try refresh if we have a valid refresh token ──────────────────
        if (hasValidRefreshToken()) {
            TokenResponse refreshResult = refreshAccessToken();
            if (refreshResult.isSuccess()) {
                return refreshResult;
            }
            // Refresh failed — fall through to full auth
        }

        // ── 3. Full ROPC authentication ──────────────────────────────────────
        try {
            AppSettings settings = certificateService.getActiveSettings();
            if (settings == null) {
                TokenResponse error = new TokenResponse();
                error.error = "No active settings configured";
                return error;
            }

            String password = certificateService.getDecryptedApiKey();
            if (password == null || password.isEmpty()) {
                TokenResponse error = new TokenResponse();
                error.error = "No ATV password configured (haciendaApiKey)";
                return error;
            }

            String username = buildIdpUsername(settings);
            if (username == null) {
                TokenResponse error = new TokenResponse();
                error.error = "Cannot build IDP username: missing TipoIdentificacion or Identificacion in settings";
                return error;
            }

            String clientId = getClientId();

            String postData = "grant_type=password"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

            TokenResponse token = executeTokenRequest(postData);
            if (token.isSuccess()) {
                updateCache(token);
            }
            return token;

        } catch (IOException | RuntimeException e) {
            TokenResponse error = new TokenResponse();
            error.error = "Error getting token: " + e.getMessage();
            return error;
        }
    }

    /**
     * Refreshes the access token using the cached refresh token.
     * POSTs to the same IDP token endpoint with grant_type=refresh_token.
     */
    private TokenResponse refreshAccessToken() {
        try {
            String clientId = getClientId();

            String postData = "grant_type=refresh_token"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&refresh_token=" + URLEncoder.encode(cachedRefreshToken, StandardCharsets.UTF_8);

            TokenResponse token = executeTokenRequest(postData);
            if (token.isSuccess()) {
                updateCache(token);
            }
            return token;

        } catch (IOException | RuntimeException e) {
            TokenResponse error = new TokenResponse();
            error.error = "Error refreshing token: " + e.getMessage();
            return error;
        }
    }

    /**
     * Executes a token POST request to the IDP and parses the JSON response.
     * Shared by both ROPC auth and refresh flows.
     */
    private TokenResponse executeTokenRequest(String postData) throws IOException {
        URL url = new URL(getTokenUrl());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = postData.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            String responseStr = readStream(conn.getInputStream());
            TokenResponse token = parseTokenJson(responseStr);
            if (token.isSuccess()) {
                return token;
            }
        }

        String errorBody = readStream(conn.getErrorStream());
        String responseDetail = errorBody != null && !errorBody.isEmpty()
            ? " - " + errorBody : "";

        TokenResponse error = new TokenResponse();
        error.error = "Failed to get token, HTTP " + responseCode + responseDetail;
        return error;
    }

    /**
     * Parses the IDP JSON response into a TokenResponse.
     * Expected fields: access_token, expires_in, refresh_token,
     * refresh_expires_in, token_type.
     */
    private TokenResponse parseTokenJson(String json) {
        TokenResponse result = new TokenResponse();
        if (json == null || !json.contains("access_token")) {
            result.error = "No access_token in response";
            return result;
        }

        result.accessToken = extractJsonValue(json, "access_token");
        result.tokenType = "Bearer";

        try {
            String expStr = extractJsonValue(json, "expires_in");
            result.expiresIn = expStr != null ? Integer.parseInt(expStr) : 300;
        } catch (NumberFormatException e) {
            result.expiresIn = 300;
        }

        result.refreshToken = extractJsonValue(json, "refresh_token");

        try {
            String refreshExpStr = extractJsonValue(json, "refresh_expires_in");
            result.refreshExpiresIn = refreshExpStr != null ? Integer.parseInt(refreshExpStr) : 0;
        } catch (NumberFormatException e) {
            result.refreshExpiresIn = 0;
        }

        return result;
    }

    @Retry(maxRetries = 3, delay = 7200000, maxDuration = 14400000)
    @Fallback(fallbackMethod = "sendInvoiceFallback")
    public ApiResponse sendInvoice(String clave, String xmlContent, 
                                    String emisorTipoId, String emisorNumeroId,
                                    String receptorTipoId, String receptorNumeroId) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/recepcion";
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);
            conn.setDoOutput(true);

            String jsonPayload = buildRecepcionPayload(clave, emisorTipoId, emisorNumeroId, 
                receptorTipoId, receptorNumeroId, xmlContent);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readStream(responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (responseCode == 200 || responseCode == 201 || responseCode == 202) {
                return ApiResponse.ok(responseBody);
            } else if (responseCode == 400) {
                String errorCause = conn.getHeaderField("X-Error-Cause");
                return ApiResponse.error(400, errorCause != null ? errorCause : responseBody);
            } else if (responseCode == 401) {
                return ApiResponse.error(401, "Unauthorized: Token expired or invalid");
            } else {
                return ApiResponse.error(responseCode, "HTTP " + responseCode + ": " + responseBody);
            }

        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Error sending invoice: " + e.getMessage(), e);
        }
    }

    public ApiResponse sendInvoiceFallback(String clave, String xmlContent, 
                                            String emisorTipoId, String emisorNumeroId,
                                            String receptorTipoId, String receptorNumeroId) {
        return ApiResponse.error(503, "No se pudo enviar la factura a Hacienda después de varios intentos. "
            + "Puede reenviarla manualmente desde la sección de Consultas. Clave: " + clave);
    }

    public ApiResponse checkInvoiceStatus(String clave) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/recepcion/" + clave;
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);

            int responseCode = conn.getResponseCode();
            String responseBody = readStream(responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (responseCode == 200) {
                return ApiResponse.ok(responseBody);
            } else if (responseCode == 401) {
                return ApiResponse.error(401, "Unauthorized: Token expired or invalid");
            } else if (responseCode == 404) {
                return ApiResponse.error(404, "Document not found: " + clave);
            } else {
                return ApiResponse.error(responseCode, "HTTP " + responseCode + ": " + responseBody);
            }

        } catch (IOException | RuntimeException e) {
            return ApiResponse.error(500, "Error checking status: " + e.getMessage());
        }
    }

    public ApiResponse acceptInvoice(String clave, String xmlAcceptance,
                                      String emisorTipoId, String emisorNumeroId,
                                      String receptorTipoId, String receptorNumeroId) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/recepcion";
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);
            conn.setDoOutput(true);

            String jsonPayload = buildRecepcionPayload(clave, emisorTipoId, emisorNumeroId,
                receptorTipoId, receptorNumeroId, xmlAcceptance);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readStream(responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (responseCode == 200 || responseCode == 201 || responseCode == 202) {
                return ApiResponse.ok(responseBody);
            } else if (responseCode == 400) {
                String errorCause = conn.getHeaderField("X-Error-Cause");
                return ApiResponse.error(400, errorCause != null ? errorCause : responseBody);
            } else if (responseCode == 401) {
                return ApiResponse.error(401, "Unauthorized: Token expired or invalid");
            } else {
                return ApiResponse.error(responseCode, "HTTP " + responseCode + ": " + responseBody);
            }

        } catch (IOException | RuntimeException e) {
            return ApiResponse.error(500, "Error accepting invoice: " + e.getMessage());
        }
    }

    public ApiResponse rejectInvoice(String clave, String xmlRejection,
                                      String emisorTipoId, String emisorNumeroId,
                                      String receptorTipoId, String receptorNumeroId) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/recepcion";
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);
            conn.setDoOutput(true);

            String jsonPayload = buildRecepcionPayload(clave, emisorTipoId, emisorNumeroId,
                receptorTipoId, receptorNumeroId, xmlRejection);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readStream(responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (responseCode == 200 || responseCode == 201 || responseCode == 202) {
                return ApiResponse.ok(responseBody);
            } else if (responseCode == 400) {
                String errorCause = conn.getHeaderField("X-Error-Cause");
                return ApiResponse.error(400, errorCause != null ? errorCause : responseBody);
            } else if (responseCode == 401) {
                return ApiResponse.error(401, "Unauthorized: Token expired or invalid");
            } else {
                return ApiResponse.error(responseCode, "HTTP " + responseCode + ": " + responseBody);
            }

        } catch (IOException | RuntimeException e) {
            return ApiResponse.error(500, "Error rejecting invoice: " + e.getMessage());
        }
    }

    /**
     * Sends a document to Hacienda and polls until a terminal state is reached.
     * Hacienda POST /recepcion returns 202 Accepted — the actual result (ACEPTADO/RECHAZADO)
     * must be obtained by polling GET /recepcion/{clave} until terminal state.
     *
     * Polling interval: 3s, timeout: 60s.
     */
    public ApiResponse submitAndWait(String clave, String xmlContent,
                                      String emisorTipoId, String emisorNumeroId,
                                      String receptorTipoId, String receptorNumeroId) {
        ApiResponse sendResult = sendInvoice(clave, xmlContent,
            emisorTipoId, emisorNumeroId, receptorTipoId, receptorNumeroId);

        if (!sendResult.isSuccess()) {
            return sendResult;
        }

        int maxAttempts = 20;
        int attempt = 0;

        while (attempt < maxAttempts) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApiResponse.error(500, "Polling interrupted for " + clave + ": " + e.getMessage());
            }

            ApiResponse statusResult = checkInvoiceStatus(clave);
            if (!statusResult.isSuccess()) {
                return statusResult;
            }

            String estado = extractJsonValue(statusResult.responseBody, "indEstado");
            if (estado == null) {
                estado = extractJsonValue(statusResult.responseBody, "estado");
            }

            if ("ACEPTADO".equalsIgnoreCase(estado)) {
                return ApiResponse.ok(statusResult.responseBody);
            } else if ("RECHAZADO".equalsIgnoreCase(estado)) {
                return ApiResponse.error(400, "Document rejected by Hacienda. Clave: " + clave
                    + ". Response: " + statusResult.responseBody);
            } else if ("ERROR".equalsIgnoreCase(estado)) {
                return ApiResponse.error(500, "Hacienda processing error for clave: " + clave);
            }

            attempt++;
        }

        return ApiResponse.error(408, "Hacienda processing timeout after 60s for clave: " + clave
            + ". Check status manually via Consultas.");
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private String buildRecepcionPayload(String clave, String emisorTipoId, String emisorNumeroId,
                                          String receptorTipoId, String receptorNumeroId,
                                          String xmlContent) {
        String fecha = java.time.ZonedDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"));
        String base64Xml = java.util.Base64.getEncoder().encodeToString(
            xmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        StringBuilder payload = new StringBuilder();
        payload.append("{\"clave\":\"").append(clave).append("\",");
        payload.append("\"fecha\":\"").append(fecha).append("\",");
        payload.append("\"emisor\":{\"tipoIdentificacion\":\"").append(emisorTipoId)
               .append("\",\"numeroIdentificacion\":\"").append(emisorNumeroId).append("\"},");
        payload.append("\"receptor\":{\"tipoIdentificacion\":\"").append(receptorTipoId)
               .append("\",\"numeroIdentificacion\":\"").append(receptorNumeroId).append("\"},");
        // Include optional callbackUrl for TRIBU-CR async notification
        String cbUrl = getCallbackUrl();
        if (!cbUrl.isEmpty()) {
            payload.append("\"callbackUrl\":\"").append(cbUrl).append("\",");
        }
        payload.append("\"comprobanteXml\":\"").append(base64Xml).append("\"}");

        return payload.toString();
    }

    private String extractJsonValue(String json, String key) {
        try {
            int keyIndex = json.indexOf("\"" + key + "\"");
            if (keyIndex == -1) return null;
            int colonIndex = json.indexOf(":", keyIndex);
            if (colonIndex == -1) return null;
            int startQuote = json.indexOf("\"", colonIndex);
            int endQuote = json.indexOf("\"", startQuote + 1);
            if (startQuote == -1 || endQuote == -1) return null;
            return json.substring(startQuote + 1, endQuote);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
