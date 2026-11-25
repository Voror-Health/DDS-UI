package org.endeavourhealth.ui.datasteward;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DataStewardClientTest {

    private HttpServer server;
    private DataStewardClient client;
    private final AtomicInteger invalidated = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ds/organisation/distributionProjects", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            int code = "Bearer fresh".equals(auth) ? 200 : status;
            byte[] b = (code == 200 ? "[{\"configName\":\"" + ex.getRequestURI().getRawQuery() + "\"}]" : "no").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        server.start();
        ServiceTokenProvider tokens = new ServiceTokenProvider() {
            public String getAccessToken() {
                return invalidated.get() > 0 ? "fresh" : "stale";
            }

            public void invalidate() {
                invalidated.incrementAndGet();
            }
        };
        client = new DataStewardClient(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/ds/",
                tokens, Duration.ofMinutes(5), Duration.ofSeconds(5), Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void getJsonReturnsBodyAndEncodesValues() throws Exception {
        status = 200;
        String body = client.getJson("/organisation/distributionProjects?odsCode=" + DataStewardClient.encode("A B&C"));
        assertTrue(body.contains("odsCode=A+B%26C"), body);
    }

    @Test
    void getJsonRetriesOnceWithFreshTokenAfter401() throws Exception {
        status = 401;
        String body = client.getJson("/organisation/distributionProjects?odsCode=X");
        assertNotNull(body);
        assertEquals(1, invalidated.get());
    }

    @Test
    void getJsonReturnsNullOn404AndThrowsOnOtherErrors() throws Exception {
        assertNull(client.getJson("/organisation/unknown"));
        status = 500;
        DataStewardClient failing = client;
        // 500 with a token that never becomes "fresh" is an error
        assertThrows(Exception.class, () -> new DataStewardClient(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/ds", new ServiceTokenProvider() {
            public String getAccessToken() {
                return "stale";
            }

            public void invalidate() {
            }
        }, Duration.ZERO, Duration.ofSeconds(5), Clock.systemUTC()).getJson("/organisation/distributionProjects?odsCode=X"));
        assertNotNull(failing);
    }
}
