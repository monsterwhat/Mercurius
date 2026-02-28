package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

@Named
@ApplicationScoped
public class HaciendaApiService {

    private static final String SANDBOX_BASE_URL = "https://api.hacienda.go.cr/fe/test";
    private static final String PRODUCTION_BASE_URL = "https://api.hacienda.go.cr/fe";

    private final HaciendaCertificateService certificateService;

    public HaciendaApiService(HaciendaCertificateService certificateService) {
        this.certificateService = certificateService;
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
        public String error;

        public boolean isSuccess() {
            return accessToken != null && !accessToken.isEmpty();
        }
    }

    private String getBaseUrl() {
        String environment = certificateService.getEnvironment();
        return "production".equalsIgnoreCase(environment) 
            ? PRODUCTION_BASE_URL 
            : SANDBOX_BASE_URL;
    }

    public TokenResponse getAccessToken() {
        try {
            if (!certificateService.isTokenExpired()) {
                TokenResponse cached = new TokenResponse();
                cached.accessToken = certificateService.getActiveSettings().getHaciendaApiKey();
                cached.expiresIn = 3600;
                cached.tokenType = "Bearer";
                return cached;
            }

            String apiKey = certificateService.getActiveSettings().getHaciendaApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                TokenResponse error = new TokenResponse();
                error.error = "No API key configured";
                return error;
            }

            URL url = new URL(getBaseUrl() + "/auth/realms/rut/protocol/openid-connect/token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            String postData = "grant_type=client_credentials&client_id=" + apiKey + "&client_secret=" + apiKey;
            
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = postData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();

                String responseStr = response.toString();
                if (responseStr.contains("access_token")) {
                    String token = extractJsonValue(responseStr, "access_token");
                    int expires = 3600;
                    try {
                        String expStr = extractJsonValue(responseStr, "expires_in");
                        expires = Integer.parseInt(expStr);
                    } catch (Exception ignored) {}

                    certificateService.saveTokenExpiry(LocalDateTime.now().plusSeconds(expires));

                    TokenResponse result = new TokenResponse();
                    result.accessToken = token;
                    result.expiresIn = expires;
                    result.tokenType = "Bearer";
                    return result;
                }
            }

            TokenResponse error = new TokenResponse();
            error.error = "Failed to get token, HTTP " + responseCode;
            return error;

        } catch (Exception e) {
            TokenResponse error = new TokenResponse();
            error.error = "Error getting token: " + e.getMessage();
            return error;
        }
    }

    public ApiResponse sendInvoice(String clave, String xmlContent) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/receptor/comprobantes";
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/xml");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = xmlContent.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(), 
                    StandardCharsets.UTF_8
                )
            );
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            return ApiResponse.ok(response.toString());

        } catch (Exception e) {
            return ApiResponse.error(500, "Error sending invoice: " + e.getMessage());
        }
    }

    public ApiResponse checkInvoiceStatus(String clave) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/receptor/comprobantes/" + clave + "/estado";
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);

            int responseCode = conn.getResponseCode();
            BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(), 
                    StandardCharsets.UTF_8
                )
            );
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            return ApiResponse.ok(response.toString());

        } catch (Exception e) {
            return ApiResponse.error(500, "Error checking status: " + e.getMessage());
        }
    }

    public ApiResponse acceptInvoice(String clave, String xmlAcceptance) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/receptor/comprobantes/" + clave + "/aceptar";
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/xml");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = xmlAcceptance.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(), 
                    StandardCharsets.UTF_8
                )
            );
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            return ApiResponse.ok(response.toString());

        } catch (Exception e) {
            return ApiResponse.error(500, "Error accepting invoice: " + e.getMessage());
        }
    }

    public ApiResponse rejectInvoice(String clave, String xmlRejection) {
        try {
            TokenResponse token = getAccessToken();
            if (!token.isSuccess()) {
                return ApiResponse.error(401, token.error);
            }

            String url = getBaseUrl() + "/receptor/comprobantes/" + clave + "/rechazar";
            URL apiUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/xml");
            conn.setRequestProperty("Authorization", "Bearer " + token.accessToken);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = xmlRejection.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(), 
                    StandardCharsets.UTF_8
                )
            );
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            return ApiResponse.ok(response.toString());

        } catch (Exception e) {
            return ApiResponse.error(500, "Error rejecting invoice: " + e.getMessage());
        }
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
        } catch (Exception e) {
            return null;
        }
    }
}
