package org.endeavourhealth.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.endeavourhealth.ui.datasteward.DataStewardClient;
import org.endeavourhealth.ui.datasteward.ServiceTokenProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deploys the webapp (real web.xml and META-INF/context.xml) on an embedded Tomcat 11 and calls it over HTTP.
 *
 * <p>The Casdoor valve named in context.xml is replaced on the test classpath by a stand-in that takes the
 * user/roles from X-Test-* headers. DataSteward is a local HTTP stub. There is no config DB (surefire points
 * CONFIG_JDBC_URL at a closed port), so endpoints that need the Endeavour databases are only exercised up to
 * the point where they would hit them.</p>
 */
@Timeout(180)
class Tomcat11SmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Tomcat tomcat;
    private static HttpServer dataSteward;
    private static String base;
    private static final List<String> dataStewardCalls = new CopyOnWriteArrayList<>();
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start(@TempDir Path tempDir) throws Exception {
        dataSteward = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> answers = Map.of(
                "/ds/organisation/hasDPA", "true",
                "/ds/organisation/organisationsByOds", "[{\"uuid\":\"org-1\",\"name\":\"Test Practice\"}]",
                "/ds/organisation/dsasPublishing", "[{\"uuid\":\"dsa-1\",\"name\":\"Publisher DSA\"}]",
                "/ds/organisation/distributionProjects", "[{\"uuid\":\"p-1\",\"name\":\"Project\",\"configName\":\"subscriber_a\"}]",
                "/ds/organisation/dsasSubscribing", "[]");
        answers.forEach((path, body) -> dataSteward.createContext(path, exchange -> {
            dataStewardCalls.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }));
        dataSteward.start();

        // the SPA would normally be built into the WAR root; give the test webapp a minimal index.html
        Path webapp = tempDir.resolve("webapp");
        copyDir(new File("src/main/webapp").toPath(), webapp);
        Files.writeString(webapp.resolve("index.html"), "<!doctype html><title>DDS UI</title><div id=\"app\"></div>");

        tomcat = new Tomcat();
        tomcat.setBaseDir(tempDir.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context ctx = tomcat.addWebapp("/vhp-dds-ui", webapp.toAbsolutePath().toString());
        ctx.setParentClassLoader(Tomcat11SmokeTest.class.getClassLoader());
        tomcat.start();
        assertEquals("STARTED", ctx.getStateName(), "webapp failed to start - see log");

        base = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/vhp-dds-ui";

        // no config DB in tests, so StartupConfig could not configure DataSteward; point it at the stub
        ServiceTokenProvider tokens = new ServiceTokenProvider() {
            public String getAccessToken() {
                return "service-token";
            }

            public void invalidate() {
            }
        };
        String dsUrl = "http://127.0.0.1:" + dataSteward.getAddress().getPort() + "/ds";
        DataStewardClient.initialise(new DataStewardClient(HttpClient.newHttpClient(), dsUrl, tokens,
                Duration.ZERO, Duration.ofSeconds(5), Clock.systemUTC()));
    }

    private static void copyDir(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    @AfterAll
    static void stop() throws Exception {
        DataStewardClient.shutdown();
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        if (dataSteward != null) {
            dataSteward.stop(0);
        }
    }

    private static HttpRequest.Builder request(String path, String user, String roles) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(90));
        if (user != null) {
            b.header("X-Test-User", user);
        }
        if (roles != null) {
            b.header("X-Test-Roles", roles);
        }
        return b;
    }

    private static HttpResponse<String> send(HttpRequest r) throws Exception {
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher json(String s) {
        return HttpRequest.BodyPublishers.ofString(s);
    }

    @Test
    void apiWithoutTokenIsRejectedByValve() throws Exception {
        assertEquals(401, send(request("/api/service", null, null).GET().build()).statusCode());
        assertEquals(401, send(request("/machine-api/service", null, null).GET().build()).statusCode());
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        HttpResponse<String> health = send(request("/public/system/health", null, null).GET().build());
        assertEquals(200, health.statusCode(), health.body());
        assertTrue(health.body().contains("UP"));

        // no config DB, so no 'auth' row: the SPA is told login isn't configured rather than getting a 401/404
        HttpResponse<String> cfg = send(request("/public/system/auth-config", null, null).GET().build());
        assertEquals(503, cfg.statusCode(), cfg.body());

        HttpResponse<String> token = send(request("/public/system/auth-token", null, null)
                .header("Content-Type", "application/json")
                .POST(json("{\"grantType\":\"refresh_token\",\"refreshToken\":\"x\"}")).build());
        assertEquals(503, token.statusCode(), token.body());
    }

    @Test
    void spaRoutesServeIndexHtml() throws Exception {
        HttpResponse<String> deepLink = send(request("/exchangeAudit/abc/def", null, null).GET().build());
        assertEquals(200, deepLink.statusCode());
        assertTrue(deepLink.body().contains("<div id=\"app\">"), deepLink.body());
        // assets are relative (./assets/...), so deep links need the webapp root as <base>
        assertTrue(deepLink.body().contains("<base href=\"/vhp-dds-ui/\">"), deepLink.body());
        assertTrue(deepLink.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));

        HttpResponse<String> asset = send(request("/serviceEdit/edit/assets/missing.js", null, null).GET().build());
        assertEquals(404, asset.statusCode(), "files are never answered with index.html");

        HttpResponse<String> root = send(request("/", null, null).GET().build());
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("<div id=\"app\">"));
    }

    @Test
    void adminActionsNeedAdminRole() throws Exception {
        String[][] calls = {
                {"POST", "/api/configManager/saveRecord", "{\"appId\":\"a\",\"configId\":\"b\",\"configData\":\"c\"}"},
                {"GET", "/api/configManager/records", null},
                {"POST", "/api/exchangeAudit/postToExchange", "{}"},
                {"POST", "/api/queueServer/startQueue", "{\"serverNumber\":\"1\",\"queueName\":\"EdsInbound-A\"}"},
                {"POST", "/api/queueServer/shutdownServer", "{\"serverNumber\":\"1\"}"},
                {"POST", "/api/queueReader/stop", "{\"configIds\":[\"x\"]}"},
                {"POST", "/api/queueReader/queueReaderCommand", "{\"command\":\"sudo ./queueReader/deploy.sh\"}"},
                {"POST", "/api/library/saveLibraryItem", "{}"},
                {"POST", "/api/sftpReader/addFeed", "{}"},
                {"POST", "/api/sftpReader/interfaceTypes", "{}"},
                {"POST", "/api/sftpReader/ignore", "{\"configurationId\":\"x\",\"batchId\":1,\"batchSplitId\":1,\"reason\":\"r\"}"},
                {"POST", "/api/sftpReader/togglePause", "{\"configurationId\":\"x\"}"},
                {"POST", "/api/sftpReader/togglePauseAll", "{}"},
                {"POST", "/api/messageFormats", "{\"format\":\"NEWFMT\",\"transform\":true}"},
        };
        for (String[] c : calls) {
            HttpRequest.Builder b = request(c[1], "ui-user", "vhp_ui_user").header("Content-Type", "application/json");
            HttpRequest r = "GET".equals(c[0]) ? b.GET().build() : b.POST(json(c[2])).build();
            HttpResponse<String> resp = send(r);
            assertEquals(403, resp.statusCode(), c[0] + " " + c[1] + " should need vhp_ui_admin: " + resp.body());
        }
    }

    @Test
    void adminRolePassesTheRoleCheck() throws Exception {
        // gets past @RolesAllowed and fails later for want of a config DB (anything but 401/403/404)
        HttpResponse<String> r = send(request("/api/queueReader/stop", "admin", "vhp_ui_admin")
                .header("Content-Type", "application/json").POST(json("{\"configIds\":[]}")).build());
        assertEquals(400, r.statusCode(), "empty config ID list must be refused, not treated as 'all': " + r.body());
    }

    @Test
    void sftpReaderAdminCallsAreValidatedBeforeTheDatabase() throws Exception {
        // there is no config DB or SFTP Reader DB in tests, so a 400 with the validator's message shows the request
        // was routed, passed the role check and was refused before any connection was made
        String validFeed = "{\"instanceName\":\"EMIS99\",\"createInstance\":true,\"configurationId\":\"EMIS99\",\"interfaceTypeId\":1,"
                + "\"pollFrequencySeconds\":300,\"localRootPath\":\"EMIS99\",\"friendlyName\":\"EMIS99 test\",\"softwareContentType\":\"EMISCSV\","
                + "\"softwareVersion\":\"5.1\",\"hostname\":\"h\",\"port\":22,\"remotePath\":\"/\",\"username\":\"u\",\"clientPublicKey\":\"k\","
                + "\"clientPrivateKey\":\"k\",\"clientPrivateKeyPassword\":\"p\",\"hostPublicKey\":\"k\",\"fileExtensionFilter\":\".gpg\","
                + "\"senderPublicKey\":\"k\",\"recipientPublicKey\":\"k\",\"recipientPrivateKey\":\"k\",\"recipientPrivateKeyPassword\":\"p\"}";
        String[][] cases = {
                {"/api/sftpReader/addFeed", validFeed.replace("\"configurationId\":\"EMIS99\",", ""), "Configuration id is required"},
                {"/api/sftpReader/addFeed", validFeed.replace("\"interfaceTypeId\":1,", ""), "Interface type is required"},
                {"/api/sftpReader/addFeed", validFeed.replace("\"port\":22", "\"port\":70000"), "port"},
                {"/api/sftpReader/addFeed", validFeed.replace("EMISCSV", "NOPE"), "must be one of"},
                {"/api/sftpReader/interfaceTypes", "{\"interfaceTypeName\":\" \",\"dataFrequencyDays\":1,\"fileTypes\":[\"a\"]}", "name is required"},
                {"/api/sftpReader/interfaceTypes", "{\"interfaceTypeName\":\"t\",\"dataFrequencyDays\":0,\"fileTypes\":[\"a\"]}", "Data frequency"},
                {"/api/sftpReader/interfaceTypes", "{\"interfaceTypeName\":\"t\",\"dataFrequencyDays\":1,\"fileTypes\":[]}", "file type"},
                {"/api/messageFormats", "{\"format\":\"NEW FMT\",\"transform\":true}", "may only contain"},
                {"/api/messageFormats", "{\"format\":\"NEWFMT\"}", "or both"},
        };
        for (String[] c : cases) {
            HttpResponse<String> r = send(request(c[0], "admin", "vhp_ui_admin")
                    .header("Content-Type", "application/json").POST(json(c[1])).build());
            assertEquals(400, r.statusCode(), c[0] + " " + c[1] + ": " + r.body());
            assertTrue(r.body().contains("\"message\"") && r.body().contains(c[2]), c[0] + ": " + r.body());
            assertFalse(r.body().contains("\"p\""), "no request values are echoed back: " + r.body());
        }

        // deleting an interface type is admin-only; for an admin it is routed (then fails for want of a database)
        HttpResponse<String> delUser = send(request("/api/sftpReader/interfaceTypes/5", "ui-user", "vhp_ui_user").DELETE().build());
        assertEquals(403, delUser.statusCode(), delUser.body());
        HttpResponse<String> delAdmin = send(request("/api/sftpReader/interfaceTypes/5", "admin", "vhp_ui_admin").DELETE().build());
        assertNotEquals(403, delAdmin.statusCode(), delAdmin.body());
        assertNotEquals(404, delAdmin.statusCode(), delAdmin.body());
        assertNotEquals(405, delAdmin.statusCode(), delAdmin.body());

        // editing, retiring, reinstating and deleting feeds are admin-only
        HttpRequest[] feedCalls = {
                request("/api/sftpReader/feeds/EMIS99", "ui-user", "vhp_ui_user").GET().build(),
                request("/api/sftpReader/feeds/EMIS99", "ui-user", "vhp_ui_user").header("Content-Type", "application/json")
                        .PUT(json(validFeed)).build(),
                request("/api/sftpReader/feeds/EMIS99/retire", "ui-user", "vhp_ui_user").POST(json("")).build(),
                request("/api/sftpReader/feeds/EMIS99/reinstate", "ui-user", "vhp_ui_user").header("Content-Type", "application/json")
                        .POST(json("{\"instanceName\":\"EMIS99\"}")).build(),
                request("/api/sftpReader/feeds/EMIS99", "ui-user", "vhp_ui_user").DELETE().build(),
        };
        for (HttpRequest r : feedCalls) {
            HttpResponse<String> resp = send(r);
            assertEquals(403, resp.statusCode(), r.method() + " " + r.uri() + ": " + resp.body());
        }
        // an admin's edit is validated before the database: secrets may be left out, the rest is still checked
        String editNoSecrets = validFeed.replace("\"clientPrivateKey\":\"k\",", "").replace("\"clientPrivateKeyPassword\":\"p\",", "")
                .replace("\"port\":22", "\"port\":0");
        HttpResponse<String> badEdit = send(request("/api/sftpReader/feeds/EMIS99", "admin", "vhp_ui_admin")
                .header("Content-Type", "application/json").PUT(json(editNoSecrets)).build());
        assertEquals(400, badEdit.statusCode(), badEdit.body());
        assertTrue(badEdit.body().contains("port"), badEdit.body());

        // a valid body gets past validation and then fails for want of a database (not a 400/403/404)
        HttpResponse<String> valid = send(request("/api/sftpReader/addFeed", "admin", "vhp_ui_admin")
                .header("Content-Type", "application/json").POST(json(validFeed)).build());
        assertNotEquals(400, valid.statusCode(), valid.body());
        assertNotEquals(403, valid.statusCode(), valid.body());
        assertNotEquals(404, valid.statusCode(), valid.body());
    }

    @Test
    void serviceAccountsOnlyGetThePostToExchangeMachineCall() throws Exception {
        HttpResponse<String> other = send(request("/api/service", "queue-reader", "vhp_service").GET().build());
        assertEquals(403, other.statusCode(), other.body());
        HttpResponse<String> otherMachine = send(request("/machine-api/configManager/records", "queue-reader", "vhp_service").GET().build());
        assertEquals(403, otherMachine.statusCode(), otherMachine.body());

        // the call the queue reader's EmisCsvHelper makes gets past both role checks (then fails: no config DB)
        HttpResponse<String> post = send(request("/machine-api/exchangeAudit/postToExchange", "queue-reader", "vhp_service")
                .header("Content-Type", "application/json")
                .POST(json("{\"exchangeId\":\"6f1c2d3e-0000-4000-8000-000000000001\",\"serviceId\":\"6f1c2d3e-0000-4000-8000-000000000002\","
                        + "\"systemId\":\"6f1c2d3e-0000-4000-8000-000000000003\",\"exchangeName\":\"EdsInbound\",\"postMode\":\"This\"}")).build());
        assertNotEquals(401, post.statusCode(), post.body());
        assertNotEquals(403, post.statusCode(), post.body());
        assertNotEquals(404, post.statusCode(), post.body());

        // a UI user still gets everything they did before
        HttpResponse<String> ui = send(request("/api/service/tagNames", "ui-user", "vhp_ui_user").GET().build());
        assertNotEquals(403, ui.statusCode());
    }

    @Test
    void queueServerRejectsShellMetacharacters() throws Exception {
        HttpResponse<String> r = send(request("/api/queueReader/stop", "admin", "vhp_ui_admin")
                .header("Content-Type", "application/json").POST(json("{\"configIds\":[\"x; rm -rf /\"]}")).build());
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("Invalid queue name"), r.body());
    }

    @Test
    void dsmDetailsComeFromDataSteward() throws Exception {
        dataStewardCalls.clear();
        HttpResponse<String> r = send(request("/api/service/dsmDetails?odsCode=F82001", "ui-user", "vhp_ui_user").GET().build());
        assertEquals(200, r.statusCode(), r.body());

        JsonNode body = MAPPER.readTree(r.body());
        assertFalse(body.has("error"), r.body());
        assertTrue(body.path("hasDPA").asBoolean());
        assertEquals(1, body.path("countPublisherDSAs").asInt());
        assertEquals("subscriber_a", body.path("distributionProjects").get(0).path("configName").asText());
        assertEquals(0, body.path("countSubscriberDSAs").asInt());

        assertTrue(dataStewardCalls.stream().anyMatch(c -> c.startsWith("/ds/organisation/hasDPA?organisationId=F82001")), dataStewardCalls.toString());
        assertTrue(dataStewardCalls.stream().anyMatch(c -> c.startsWith("/ds/organisation/dsasPublishing?uuid=org-1")), dataStewardCalls.toString());
        assertTrue(dataStewardCalls.stream().allMatch(c -> c.endsWith("auth=Bearer service-token")), dataStewardCalls.toString());
    }

    @Test
    void messageFormatsFallBackToTheBuiltInListWithoutAConfigDb() throws Exception {
        HttpResponse<String> r = send(request("/api/messageFormats", "ui-user", "vhp_ui_user").GET().build());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals("default", body.get("source").asText());
        assertTrue(body.get("warning").asText().length() > 0);
        JsonNode first = body.get("formats").get(0);
        assertEquals("EMISCSV", first.get("format").asText());
        assertTrue(first.get("sftpReader").asBoolean() && first.get("transform").asBoolean(), first.toString());
        assertEquals(15, body.get("formats").size());
    }

    @Test
    void endpointsAreRegistered() throws Exception {
        // every page's endpoints must be routed (not 404) for a normal UI user
        String[] paths = {
                "/api/service/tagNames", "/api/exchangeAudit/getTransformErrorSummaries", "/api/subscribers/subscribers",
                "/api/remoteFiling/getRemoteFilers", "/api/queueReader/status?applicationName=queuereader",
                "/api/queueReader/instances", "/api/queueServer/servers", "/api/frailtyApi/subscriberFlags",
                "/api/hl7Receiver/channelStatus", "/api/hl7v2Api/status", "/api/sftpReader/instances",
                "/api/sftpReader/interfaceTypes", "/api/sftpReader/instanceNames",
                "/api/scheduledTask/rules", "/api/audit/modules", "/api/logging/getLoggingEvents?page=0",
                "/api/rabbit/routings", "/api/library/getSystems",
        };
        for (String p : paths) {
            HttpResponse<String> r = send(request(p, "ui-user", "vhp_ui_user").GET().build());
            assertNotEquals(404, r.statusCode(), p);
            assertNotEquals(401, r.statusCode(), p);
            assertNotEquals(403, r.statusCode(), p);
        }
    }
}
