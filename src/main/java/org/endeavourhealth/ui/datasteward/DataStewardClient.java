package org.endeavourhealth.ui.datasteward;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for the vhp-datasteward API. Replaces the direct read of the old Data Sharing Manager tables
 * ({@code DataProcessingAgreementDalI.getDataProcessingAgreementsForOrganisation}) for DPA checks.
 *
 * <p>Calls {@code GET <url>/organisation/hasDPA?organisationId=<id>}, which answers {@code true}/{@code false}
 * as plain text. Results (both answers) are cached for {@link DataStewardConfig#cacheTtl()}. Failures are
 * never cached and are thrown, so a DataSteward outage shows up as an error rather than as "no DPA".</p>
 */
public class DataStewardClient {

    private static final Logger LOG = LoggerFactory.getLogger(DataStewardClient.class);

    private static volatile DataStewardClient instance;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ServiceTokenProvider tokenProvider;
    private final Duration cacheTtl;
    private final Duration timeout;
    private final Clock clock;
    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    private record CachedAnswer(boolean hasDpa, Instant expires) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, CachedProjects> distributionCache = new ConcurrentHashMap<>();

    /** {@code projects} is null for an ODS code DataSteward doesn't know. */
    private record CachedProjects(List<DistributionProject> projects, Instant expires) {
    }

    /**
     * One of a publisher's valid distribution projects, as DataSteward returns it (dates ISO-8601, or null).
     * {@code configName} is the subscriber config. Same record as vhp-queuereader's client.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DistributionProject(String uuid, String name, String configName, Short projectTypeId,
                                      Short projectStatusId, String startDate, String endDate,
                                      String authorisedBy, String authorisedDate) {
    }

    public DataStewardClient(HttpClient httpClient, String baseUrl, ServiceTokenProvider tokenProvider,
                             Duration cacheTtl, Duration timeout, Clock clock) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokenProvider = tokenProvider;
        this.cacheTtl = cacheTtl;
        this.timeout = timeout;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------
    // Singleton lifecycle (set up in StartupConfig, used from OrganisationCache)
    // ------------------------------------------------------------------------------------------

    public static DataStewardClient create(DataStewardConfig config) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ServiceTokenProvider tokens = new CasdoorServiceTokenProvider(
                http, config.casdoorEndpoint(), config.clientId(), config.clientSecret(),
                config.username(), config.password(), config.timeout(), Clock.systemUTC());
        return new DataStewardClient(http, config.url(), tokens, config.cacheTtl(), config.timeout(), Clock.systemUTC());
    }

    public static void initialise(DataStewardClient client) {
        instance = client;
    }

    public static void shutdown() {
        instance = null;
    }

    public static DataStewardClient getInstance() {
        DataStewardClient client = instance;
        if (client == null) {
            throw new IllegalStateException("DataStewardClient is not initialised - check the 'datasteward' config row"
                    + " for this app (see StartupConfig logging)");
        }
        return client;
    }

    // ------------------------------------------------------------------------------------------
    // DPA check
    // ------------------------------------------------------------------------------------------

    /**
     * @param organisationId ODS code (or DataSteward organisation UUID). Parent/child identifiers such as
     *                       {@code YDDH3_09A} are checked against the parent ({@code YDDH3}), as DataSteward does.
     */
    public boolean hasDpa(String organisationId) throws Exception {
        if (organisationId == null || organisationId.isBlank()) {
            throw new IllegalArgumentException("organisationId is required for a DPA check");
        }
        String key = normalise(organisationId);

        CachedAnswer cached = cache.get(key);
        if (cached != null && clock.instant().isBefore(cached.expires())) {
            return cached.hasDpa();
        }

        boolean hasDpa = callHasDpa(key);
        cache.put(key, new CachedAnswer(hasDpa, clock.instant().plus(cacheTtl)));
        LOG.debug("DataSteward DPA check for {} -> {}", key, hasDpa);
        return hasDpa;
    }

    public void evict(String organisationId) {
        if (organisationId != null) {
            cache.remove(normalise(organisationId));
        }
    }

    public void clearCache() {
        cache.clear();
        distributionCache.clear();
    }

    private boolean callHasDpa(String organisationId) throws Exception {
        URI uri = URI.create(baseUrl + "/organisation/hasDPA?organisationId="
                + URLEncoder.encode(organisationId, StandardCharsets.UTF_8));

        HttpResponse<String> response = send(uri, tokenProvider.getAccessToken());
        if (response.statusCode() == 401) {
            //token may have been revoked or the signing key rotated - get a fresh one and retry once
            tokenProvider.invalidate();
            response = send(uri, tokenProvider.getAccessToken());
        }

        if (response.statusCode() != 200) {
            throw new Exception("DataSteward DPA check for " + organisationId + " failed: HTTP "
                    + response.statusCode() + " from " + uri + " - " + abbreviate(response.body()));
        }

        String body = response.body() == null ? "" : response.body().trim();
        if (body.equalsIgnoreCase("true")) {
            return true;
        }
        if (body.equalsIgnoreCase("false")) {
            return false;
        }
        throw new Exception("Unexpected DataSteward DPA response for " + organisationId + ": " + abbreviate(body));
    }

    /**
     * The publisher's valid distribution projects ({@code GET /organisation/distributionProjects?odsCode=}), used
     * by the ProjectCache override (SubscriberHelper's subscriber lookup). Cached like the DPA answers.
     *
     * @return null when DataSteward doesn't know the ODS code (HTTP 404), as the old DSM lookup returned null
     */
    public List<DistributionProject> getValidDistributionProjects(String publisherOdsCode) throws Exception {
        if (publisherOdsCode == null || publisherOdsCode.isBlank()) {
            throw new IllegalArgumentException("publisherOdsCode is required for a distribution project lookup");
        }
        CachedProjects cached = distributionCache.get(publisherOdsCode);
        if (cached != null && clock.instant().isBefore(cached.expires())) {
            return cached.projects();
        }
        String body = getJson("/organisation/distributionProjects?odsCode=" + encode(publisherOdsCode));
        List<DistributionProject> projects;
        try {
            projects = body == null ? null : List.copyOf(Arrays.asList(JSON.readValue(body, DistributionProject[].class)));
        } catch (Exception ex) {
            throw new Exception("Unexpected DataSteward distribution project response for " + publisherOdsCode + ": "
                    + abbreviate(body), ex);
        }
        distributionCache.put(publisherOdsCode, new CachedProjects(projects, clock.instant().plus(cacheTtl)));
        return projects;
    }

    /**
     * GETs a DataSteward resource and returns the JSON body (not cached). Used by the publisher editor's
     * "DSM details" (dsasPublishing, distributionProjects, ...), which used to read the DSM tables directly.
     *
     * @param pathAndQuery e.g. {@code /organisation/distributionProjects?odsCode=X} (values already encoded)
     * @return the body, or null on HTTP 404
     */
    public String getJson(String pathAndQuery) throws Exception {
        URI uri = URI.create(baseUrl + pathAndQuery);
        HttpResponse<String> response = send(uri, tokenProvider.getAccessToken(), "application/json");
        if (response.statusCode() == 401) {
            tokenProvider.invalidate();
            response = send(uri, tokenProvider.getAccessToken(), "application/json");
        }
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new Exception("DataSteward call failed: HTTP " + response.statusCode() + " from " + uri + " - "
                    + abbreviate(response.body()));
        }
        return response.body();
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(URI uri, String token) throws Exception {
        return send(uri, token, "text/plain");
    }

    private HttpResponse<String> send(URI uri, String token, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", accept)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Same normalisation DataSteward applies: strip braces, and use the parent of a parent_child code. */
    static String normalise(String organisationId) {
        String normalised = organisationId.trim().replaceAll("[{}]", "");
        int underscore = normalised.indexOf('_');
        if (underscore != -1) {
            normalised = normalised.substring(0, underscore);
        }
        return normalised;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
