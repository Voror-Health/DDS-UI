package org.endeavourhealth.ui.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The few Casdoor calls the UI's backend makes on the browser's behalf, using the application's client
 * secret (which must never reach the browser):
 * <ul>
 *     <li>authorization-code and refresh-token exchange (replaces keycloak-js doing it in the browser)</li>
 *     <li>listing the organisation's users, for the Audit page's user filter (replaces the Keycloak admin
 *     API call in common/api's AuditEndpoint)</li>
 * </ul>
 */
public class CasdoorApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final CasdoorAuthSettings settings;

    public CasdoorApi(HttpClient httpClient, CasdoorAuthSettings settings) {
        this.httpClient = httpClient;
        this.settings = settings;
    }

    public static CasdoorApi create(CasdoorAuthSettings settings) {
        return new CasdoorApi(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), settings);
    }

    public record Result(int status, JsonNode body) {
    }

    public Result exchangeCode(String code, String redirectUri) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        return tokenCall(form);
    }

    public Result refresh(String refreshToken) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return tokenCall(form);
    }

    private Result tokenCall(Map<String, String> form) throws Exception {
        form.put("client_id", settings.clientId());
        form.put("client_secret", settings.clientSecret());
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue() == null ? "" : e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.endpoint() + "/api/login/oauth/access_token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = parse(response.body());
        int status = response.statusCode();
        //Casdoor answers 200 with {"error": ...} for a bad code or refresh token
        if (status == 200 && (json == null || json.hasNonNull("error") || !json.hasNonNull("access_token"))) {
            status = 401;
        }
        return new Result(status, json);
    }

    /** {@code GET /api/get-users?owner=<organization>}, authenticated as the application (HTTP Basic). */
    public JsonNode getUsers() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (settings.clientId() + ":" + settings.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.endpoint() + "/api/get-users?owner=" + enc(settings.organization())))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Exception("Casdoor get-users failed: HTTP " + response.statusCode());
        }
        JsonNode json = parse(response.body());
        //newer Casdoor versions wrap the list as {"status":"ok","data":[...]}
        if (json != null && json.has("data") && json.get("data").isArray()) {
            return json.get("data");
        }
        if (json != null && json.has("status") && "error".equals(json.path("status").asText())) {
            throw new Exception("Casdoor get-users failed: " + json.path("msg").asText());
        }
        return json;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
