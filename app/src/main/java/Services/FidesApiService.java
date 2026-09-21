package Services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * HTTP client for the Fides e-invoicing API.
 * <p>
 * Handles the full document lifecycle in Fides:
 * {@code create invoice → sign document → submit to Hacienda → poll for result}.
 * <p>
 * Fides must be running and configured with a user that has Hacienda credentials
 * set up via {@code POST /api/v1/credentials}. The tenant must be registered.
 * <p>
 * Configuration is read from the database {@link Models.AppSettings} entity
 * (fields prefixed {@code fides*}) via {@link AppSettingsService}.
 */
@Named
@ApplicationScoped
public class FidesApiService {

    private static final int TOKEN_EXPIRY_BUFFER_SECONDS = 30;

    // ── DB-backed config ───────────────────────────────────────────────
    @Inject
    private AppSettingsService appSettingsService;

    private String fidesApiUrl;
    private String fidesAuthEmail;
    private String fidesAuthPassword;
    private String fidesTenantId;
    private String fidesUserId;

    // ── JSON mapper ────────────────────────────────────────────────────
    private final ObjectMapper mapper = new ObjectMapper();

    // ── Token cache ─────────────────────────────────────────────────────
    private String cachedToken;
    private LocalDateTime tokenExpiry;

    // ── API response wrapper ────────────────────────────────────────────
    public static class FidesResponse {
        public boolean success;
        public int httpStatus;
        public String body;
        public String errorMessage;

        public static FidesResponse ok(int status, String body) {
            FidesResponse r = new FidesResponse();
            r.success = true;
            r.httpStatus = status;
            r.body = body;
            return r;
        }

        public static FidesResponse error(int status, String message) {
            FidesResponse r = new FidesResponse();
            r.success = false;
            r.httpStatus = status;
            r.errorMessage = message;
            return r;
        }

        public static FidesResponse connectionError(String message) {
            FidesResponse r = new FidesResponse();
            r.success = false;
            r.httpStatus = 0;
            r.errorMessage = "Fides connection error: " + message;
            return r;
        }
    }

    // ── Invoice data transfer ──────────────────────────────────────────
    public static class InvoiceData {
        public String issuerTaxId;
        public String issuerName;
        public String receiverTaxId;
        public String receiverName;
        public String receiverEmail;
        /** Optional pre-generated Hacienda access key (clave). If null, Fides generates one. */
        public String accessKey;
        public List<ItemData> items;
        public String total;

        public static class ItemData {
            public String code;
            public String description;
            public String quantity;
            public String unitPrice;
            public String taxRate;
        }
    }

    // ── Document result ─────────────────────────────────────────────────
    public static class DocumentResult {
        public String id;
        public String accessKey;
        public String status;
    }

    // ── Submission result ───────────────────────────────────────────────
    public static class SubmissionResult {
        public String id;
        public String state;
        public String responseMessage;
    }

    // ── Config loader ──────────────────────────────────────────────────
    /** Load Fides configuration from the database AppSettings entity. */
    private void loadConfig() {
        Models.AppSettings settings = appSettingsService.returnCurrent();
        if (settings != null) {
            fidesApiUrl = settings.getFidesApiUrl() != null ? settings.getFidesApiUrl() : "http://localhost:8080";
            fidesAuthEmail = settings.getFidesAuthEmail() != null ? settings.getFidesAuthEmail() : "";
            fidesAuthPassword = settings.getFidesAuthPassword() != null ? settings.getFidesAuthPassword() : "";
            fidesTenantId = settings.getFidesTenantId() != null ? settings.getFidesTenantId() : "";
            fidesUserId = settings.getFidesUserId() != null ? settings.getFidesUserId() : "";
        } else {
            fidesApiUrl = "http://localhost:8080";
            fidesAuthEmail = "";
            fidesAuthPassword = "";
            fidesTenantId = "";
            fidesUserId = "";
        }
        // Strip trailing slash
        if (fidesApiUrl != null && fidesApiUrl.endsWith("/")) {
            fidesApiUrl = fidesApiUrl.substring(0, fidesApiUrl.length() - 1);
        }
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Public API — full document lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Run the full Fides pipeline: create invoice → sign → submit → poll.
     *
     * @param invoice the invoice data extracted from Mercurius domain model
     * @return FidesResponse with the submission result in body
     */
    public FidesResponse submitToHaciendaViaFides(@Nonnull InvoiceData invoice) {
        // Reload config from database on every call (settings may change at runtime)
        loadConfig();
        try {
            String token = getAccessToken();
            if (token == null) {
                return FidesResponse.error(401, "Fides authentication failed");
            }

            // 2. Create invoice in Fides (generates XML, stores as Draft)
            FidesResponse createResp = createInvoice(token, invoice);
            if (!createResp.success) {
                return createResp;
            }

            DocumentResult doc = parseDocumentResult(createResp.body);
            if (doc == null || doc.id == null) {
                return FidesResponse.error(500, "Failed to parse Fides document result");
            }

            // 3. Sign the document
            FidesResponse signResp = signDocument(token, doc.id);
            if (!signResp.success) {
                return FidesResponse.error(signResp.httpStatus,
                        "Fides signing failed: " + signResp.errorMessage);
            }

            // 4. Submit to Hacienda
            String submissionId = doSubmission(token, doc.id, doc.accessKey);
            if (submissionId == null) {
                return FidesResponse.error(500, "Fides submission failed");
            }

            // 5. Poll for result (up to ~60s, same as original submitAndWait)
            SubmissionResult result = pollSubmission(token, submissionId, 20, 3000);
            if (result == null) {
                return FidesResponse.error(408,
                        "Fides submission polling timed out for document: " + doc.id);
            }

            // 6. Map Fides state back to Mercurius-style response
            switch (result.state) {
                case "Completed":
                    return FidesResponse.ok(200, "{\"estado\":\"ACEPTADO\"}");
                case "Failed":
                    String reason = result.responseMessage != null ? result.responseMessage : "Unknown error";
                    return FidesResponse.error(400, "Document rejected by Fides/Hacienda: " + reason);
                default:
                    return FidesResponse.error(202,
                            "Document accepted for processing (pending final status). Submission ID: " + result.id);
            }

        } catch (RuntimeException e) {
            return FidesResponse.error(500, "Fides integration error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Fides API calls
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Authenticate with Fides using password grant and cache the JWT.
     */
    private String getAccessToken() {
        // Check cache first
        if (cachedToken != null && tokenExpiry != null
                && LocalDateTime.now().plusSeconds(TOKEN_EXPIRY_BUFFER_SECONDS).isBefore(tokenExpiry)) {
            return cachedToken;
        }

        if (fidesAuthEmail == null || fidesAuthEmail.isEmpty()
                || fidesAuthPassword == null || fidesAuthPassword.isEmpty()) {
            return null;
        }

        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("grant_type", "password");
            payload.put("client_id", fidesAuthEmail);
            payload.put("client_secret", fidesAuthPassword);
            String jsonPayload = mapper.writeValueAsString(payload);

            String url = fidesApiUrl + "/api/v1/auth/token";
            HttpURLConnection conn = openConnection(url, "POST", "application/json; charset=UTF-8");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readStream(conn.getInputStream());
                JsonNode json = mapper.readTree(body);
                if (json.has("access_token")) {
                    cachedToken = json.get("access_token").asText();
                    int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt(300) : 300;
                    tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn);
                    return cachedToken;
                }
            }
        } catch (IOException e) {
            // Fall through to null
        }
        return null;
    }

    /**
     * POST /api/v1/invoices — creates an invoice in Fides.
     */
    private FidesResponse createInvoice(String token, @Nonnull InvoiceData data) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("tenant_id", fidesTenantId);

            ObjectNode issuer = mapper.createObjectNode();
            issuer.put("tax_id", data.issuerTaxId);
            issuer.put("name", data.issuerName);
            payload.set("issuer", issuer);

            ObjectNode receiver = mapper.createObjectNode();
            receiver.put("tax_id", data.receiverTaxId);
            receiver.put("name", data.receiverName);
            if (data.receiverEmail != null && !data.receiverEmail.isEmpty()) {
                receiver.put("email", data.receiverEmail);
            }
            payload.set("receiver", receiver);

            ArrayNode items = mapper.createArrayNode();
            if (data.items != null) {
                for (InvoiceData.ItemData item : data.items) {
                    ObjectNode itemNode = mapper.createObjectNode();
                    itemNode.put("code", item.code != null ? item.code : "");
                    itemNode.put("description", item.description != null ? item.description : "");
                    itemNode.put("quantity", item.quantity != null ? item.quantity : "1");
                    itemNode.put("unit_price", item.unitPrice != null ? item.unitPrice : "0");
                    if (item.taxRate != null && !item.taxRate.isEmpty()) {
                        itemNode.put("tax_rate", item.taxRate);
                    }
                    items.add(itemNode);
                }
            }
            payload.set("items", items);
            payload.put("total", data.total != null ? data.total : "0");
            if (data.accessKey != null && !data.accessKey.isEmpty()) {
                payload.put("clave", data.accessKey);
            }

            String jsonPayload = mapper.writeValueAsString(payload);
            String url = fidesApiUrl + "/api/v1/invoices";
            return doPostJson(url, token, jsonPayload);

        } catch (IOException e) {
            return FidesResponse.connectionError(e.getMessage());
        }
    }

    /**
     * POST /api/v1/documents/{id}/sign — signs a generated document.
     */
    private FidesResponse signDocument(String token, @Nonnull String documentId) {
        String url = fidesApiUrl + "/api/v1/documents/" + documentId + "/sign";
        return doPostJson(url, token, "");
    }

    /**
     * POST /api/v1/submissions — submits a signed document to Hacienda.
     * Returns the submission ID on success, or null on failure.
     */
    private String doSubmission(String token, @Nonnull String documentId, @Nonnull String accessKey) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("document_id", documentId);
            payload.put("tenant_id", fidesTenantId);
            payload.put("access_key", accessKey);

            String jsonPayload = mapper.writeValueAsString(payload);
            String url = fidesApiUrl + "/api/v1/submissions";
            FidesResponse resp = doPostJson(url, token, jsonPayload);
            if (!resp.success) {
                return null;
            }

            JsonNode json = mapper.readTree(resp.body);
            JsonNode data = json.get("data");
            if (data != null && data.has("id")) {
                return data.get("id").asText();
            }
            if (json.has("id")) {
                return json.get("id").asText();
            }
            return null;

        } catch (IOException e) {
            return null;
        }
    }

    /**
     * GET /api/v1/submissions/{id} — checks submission status.
     */
    private SubmissionResult checkSubmission(String token, @Nonnull String submissionId) {
        try {
            String url = fidesApiUrl + "/api/v1/submissions/" + submissionId;
            HttpURLConnection conn = openConnection(url, "GET", null);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readStream(conn.getInputStream());
                JsonNode json = mapper.readTree(body);

                // Navigate the standard Fides envelope: { "success": true, "data": { ... } }
                JsonNode data = json.get("data");
                if (data == null) {
                    data = json;
                }

                SubmissionResult result = new SubmissionResult();
                result.id = data.has("id") ? data.get("id").asText() : submissionId;

                // Fides uses "state" field which is a SubmissionState struct (enum with string representation)
                if (data.has("state")) {
                    JsonNode stateNode = data.get("state");
                    if (stateNode.isObject() && stateNode.has("type")) {
                        result.state = stateNode.get("type").asText();
                    } else {
                        result.state = stateNode.asText();
                    }
                } else if (data.has("status")) {
                    result.state = data.get("status").asText();
                } else {
                    result.state = "Unknown";
                }

                result.responseMessage = data.has("response_message") && !data.get("response_message").isNull()
                        ? data.get("response_message").asText()
                        : null;

                return result;
            }
        } catch (IOException e) {
            // Fall through
        }
        return null;
    }

    /**
     * Poll submission status until terminal state or timeout.
     */
    private SubmissionResult pollSubmission(String token, @Nonnull String submissionId,
                                            int maxAttempts, long sleepMs) {
        SubmissionResult lastResult = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            lastResult = checkSubmission(token, submissionId);
            if (lastResult == null) {
                continue;
            }

            // Terminal states
            if ("Completed".equals(lastResult.state)
                    || lastResult.state != null && lastResult.state.startsWith("Failed")) {
                return lastResult;
            }
        }
        return lastResult;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HTTP helpers
    // ═══════════════════════════════════════════════════════════════════

    private FidesResponse doPostJson(String url, String token, String jsonPayload) {
        try {
            HttpURLConnection conn = openConnection(url, "POST", "application/json; charset=UTF-8");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);

            if (jsonPayload != null && !jsonPayload.isEmpty()) {
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }

            int code = conn.getResponseCode();
            String body = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code >= 200 && code < 300) {
                return FidesResponse.ok(code, body);
            } else if (code == 401) {
                cachedToken = null;
                return FidesResponse.error(401, "Fides unauthorized — token expired or invalid");
            } else {
                return FidesResponse.error(code, "Fides HTTP " + code + ": " + body);
            }

        } catch (IOException e) {
            return FidesResponse.connectionError(e.getMessage());
        }
    }

    private HttpURLConnection openConnection(String url, String method, String contentType) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }
        return conn;
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private DocumentResult parseDocumentResult(String jsonBody) {
        try {
            JsonNode json = mapper.readTree(jsonBody);
            JsonNode data = json.get("data");
            if (data == null) data = json;

            DocumentResult result = new DocumentResult();
            result.id = data.has("id") ? data.get("id").asText() : null;
            result.accessKey = data.has("access_key") ? data.get("access_key").asText() : null;
            result.status = data.has("status") ? data.get("status").asText() : null;
            return result;
        } catch (IOException e) {
            return null;
        }
    }
}
