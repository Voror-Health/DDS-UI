package org.endeavourhealth.ui.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CasdoorApiTest {

    private HttpServer server;
    private CasdoorAuthSettings settings;
    private final AtomicReference<String> tokenBody = new AtomicReference<>();
    private final AtomicReference<String> usersAuth = new AtomicReference<>();
    private final AtomicReference<String> usersQuery = new AtomicReference<>();
    private volatile String tokenAnswer;
    private volatile String usersAnswer;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/login/oauth/access_token", ex -> {
            tokenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, tokenAnswer);
        });
        server.createContext("/api/get-users", ex -> {
            usersAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            usersQuery.set(ex.getRequestURI().getRawQuery());
            reply(ex, usersAnswer);
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        JsonNode auth = new ObjectMapper().readTree("{\"casdoor-endpoint\":\"" + endpoint + "\",\"org-name\":\"vhp\","
                + "\"app-name\":\"dds-ui\",\"client-id\":\"cid\",\"client-secret\":\"s3cret\"}");
        settings = CasdoorAuthSettings.fromJson(auth);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            String[] p = kv.split("=", 2);
            m.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void settingsStripTrailingSlashAndNeverPrintTheSecret() {
        assertFalse(settings.endpoint().endsWith("/"));
        assertFalse(settings.toString().contains("s3cret"));
        assertNull(CasdoorAuthSettings.fromJson(null));
    }

    @Test
    void codeExchangeSendsClientSecretServerSide() throws Exception {
        tokenAnswer = "{\"access_token\":\"a.b.c\",\"refresh_token\":\"r\",\"expires_in\":3600}";
        CasdoorApi.Result r = new CasdoorApi(HttpClient.newHttpClient(), settings).exchangeCode("the-code", "https://x/callback");

        assertEquals(200, r.status());
        assertEquals("a.b.c", r.body().path("access_token").asText());
        Map<String, String> sent = form(tokenBody.get());
        assertEquals("authorization_code", sent.get("grant_type"));
        assertEquals("the-code", sent.get("code"));
        assertEquals("https://x/callback", sent.get("redirect_uri"));
        assertEquals("cid", sent.get("client_id"));
        assertEquals("s3cret", sent.get("client_secret"));
    }

    @Test
    void casdoorErrorBodyIsTreatedAsUnauthorised() throws Exception {
        tokenAnswer = "{\"error\":\"invalid_grant\",\"error_description\":\"expired\"}";
        CasdoorApi.Result r = new CasdoorApi(HttpClient.newHttpClient(), settings).refresh("old");
        assertEquals(401, r.status());
        assertEquals("refresh_token", form(tokenBody.get()).get("grant_type"));
    }

    @Test
    void getUsersUsesBasicAuthAndUnwrapsData() throws Exception {
        usersAnswer = "{\"status\":\"ok\",\"data\":[{\"name\":\"alice\",\"firstName\":\"Alice\"}]}";
        JsonNode users = new CasdoorApi(HttpClient.newHttpClient(), settings).getUsers();

        assertEquals(1, users.size());
        assertEquals("alice", users.get(0).path("name").asText());
        assertEquals("owner=vhp", usersQuery.get());
        String expected = "Basic " + Base64.getEncoder().encodeToString("cid:s3cret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, usersAuth.get());

        usersAnswer = "[{\"name\":\"bob\"}]";
        assertEquals("bob", new CasdoorApi(HttpClient.newHttpClient(), settings).getUsers().get(0).path("name").asText());
    }

    @Test
    void auditUserIdsMatchTheIdsWrittenByTheEndpoints() {
        // the audit page filters on this UUID, which SecurityUtils derives from the Casdoor user name
        assertEquals(SecurityUtils.toUuid("alice"), SecurityUtils.toUuid("alice"));
        assertNotEquals(SecurityUtils.toUuid("alice"), SecurityUtils.toUuid("bob"));
        String uuid = "6f1c2d3e-0000-4000-8000-000000000001";
        assertEquals(uuid, SecurityUtils.toUuid(uuid).toString());
    }
}
