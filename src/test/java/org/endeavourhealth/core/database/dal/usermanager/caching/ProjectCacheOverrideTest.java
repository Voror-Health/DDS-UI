package org.endeavourhealth.core.database.dal.usermanager.caching;

import com.sun.net.httpserver.HttpServer;
import org.endeavourhealth.core.database.rdbms.datasharingmanager.models.ProjectEntity;
import org.endeavourhealth.ui.datasteward.ServiceTokenProvider;
import org.endeavourhealth.ui.datasteward.DataStewardClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubscriberHelper.getSubscriberConfigNamesImpl (Exchange Audit's protocol list, Subscribers pages) gets the
 * publisher's subscriber config names from ProjectCache.getValidDistributionProjectsForPublisher. Check that the
 * webapp's copy (WEB-INF/classes) is the loaded one and that it answers from DataSteward, not the DSM tables.
 */
class ProjectCacheOverrideTest {

    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String body = "[]";
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/machine-api/organisation/distributionProjects", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/machine-api";
    }

    @AfterEach
    void stop() {
        DataStewardClient.shutdown();
        server.stop(0);
    }

    private void installClient() {
        ServiceTokenProvider tokens = new ServiceTokenProvider() {
            @Override
            public String getAccessToken() {
                return "t";
            }

            @Override
            public void invalidate() {
            }
        };
        DataStewardClient.initialise(new DataStewardClient(HttpClient.newHttpClient(), baseUrl, tokens,
                Duration.ofMinutes(5), Duration.ofSeconds(5), Clock.systemUTC()));
    }

    @Test
    void overrideShadowsLibraryClass() throws Exception {
        String location = ProjectCache.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertFalse(location.endsWith(".jar"), "ProjectCache loaded from " + location);
        assertNotNull(ProjectCache.class.getDeclaredField("VHP_OVERRIDE"));
    }

    @Test
    void distributionProjectsComeFromDataSteward() throws Exception {
        body = "[{\"uuid\":\"p1\",\"name\":\"Dist 1\",\"projectTypeId\":4,\"projectStatusId\":0,"
                + "\"startDate\":\"2026-01-01\",\"endDate\":\"2027-01-01\",\"configName\":\"subscriber_a\","
                + "\"authorisedBy\":\"u\",\"authorisedDate\":\"2026-01-02T09:30\"},"
                + "{\"uuid\":\"p2\",\"name\":\"Dist 2\",\"projectTypeId\":4,\"projectStatusId\":0,"
                + "\"startDate\":\"2026-01-01\",\"configName\":\"subscriber_b\",\"authorisedBy\":\"u\","
                + "\"authorisedDate\":\"2026-01-02T09:30:15\"}]";
        installClient();

        List<ProjectEntity> projects = ProjectCache.getValidDistributionProjectsForPublisher("F82001");

        assertEquals(List.of("odsCode=F82001"), queries);
        assertEquals(List.of("subscriber_a", "subscriber_b"), projects.stream().map(ProjectEntity::getConfigName).toList());
        ProjectEntity p1 = projects.get(0);
        assertEquals("p1", p1.getUuid());
        assertEquals(Short.valueOf((short) 4), p1.getProjectTypeId());
        assertEquals(Date.valueOf("2026-01-01"), p1.getStartDate());
        assertEquals(Date.valueOf("2027-01-01"), p1.getEndDate());
        assertEquals(Timestamp.valueOf("2026-01-02 09:30:00"), p1.getAuthorisedDate());
        assertNull(projects.get(1).getEndDate());
    }

    @Test
    void unknownOdsCodeIsNullAsBefore() throws Exception {
        //SubscriberHelper treats null as "no subscribers"
        status = 404;
        installClient();
        assertNull(ProjectCache.getValidDistributionProjectsForPublisher("NOPE"));
    }

    @Test
    void flushCacheClearsDataStewardAnswers() throws Exception {
        installClient();
        ProjectCache.getValidDistributionProjectsForPublisher("F82001");
        ProjectCache.getValidDistributionProjectsForPublisher("F82001");
        assertEquals(1, queries.size());

        ProjectCache.flushCache();
        ProjectCache.getValidDistributionProjectsForPublisher("F82001");
        assertEquals(2, queries.size());
    }

    @Test
    void notConfiguredIsAnError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ProjectCache.getValidDistributionProjectsForPublisher("F82001"));
        assertTrue(ex.getMessage().contains("datasteward"));
    }
}
