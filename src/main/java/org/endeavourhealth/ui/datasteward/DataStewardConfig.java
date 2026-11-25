package org.endeavourhealth.ui.datasteward;

import com.fasterxml.jackson.databind.JsonNode;
import org.endeavourhealth.common.config.ConfigManager;

import java.time.Duration;

/**
 * Settings for calling the vhp-datasteward API, read from the config database.
 *
 * <p>Config row {@code datasteward} (app_id {@code dds-ui}, or {@code global}):</p>
 * <pre>
 * {
 *   "url": "https://&lt;host&gt;/vhp-datasteward/machine-api",   (required)
 *   "cache-seconds": 300,                                     (optional, default 300)
 *   "timeout-seconds": 30,                                    (optional, default 30)
 *   "username": "...", "password": "...",                     (optional - Casdoor service user)
 *   "casdoor-endpoint": "...", "client-id": "...", "client-secret": "..."   (optional)
 * }
 * </pre>
 *
 * <p>If the Casdoor endpoint/client fields are not in the {@code datasteward} row they are taken from the
 * {@code auth} row, the same row the CasdoorAuthenticatorValve reads. With a {@code username} the token is
 * obtained with the password grant as that service user (who needs a role DataSteward accepts, e.g.
 * {@code vhp_service}); without one, with the client_credentials grant.</p>
 */
public record DataStewardConfig(String url,
                                String casdoorEndpoint,
                                String clientId,
                                String clientSecret,
                                String username,
                                String password,
                                Duration cacheTtl,
                                Duration timeout) {

    public static final String CONFIG_ID = "datasteward";
    public static final String AUTH_CONFIG_ID = "auth";

    public static DataStewardConfig fromConfigManager() throws Exception {
        JsonNode ds = ConfigManager.getConfigurationAsJson(CONFIG_ID);
        if (ds == null) {
            throw new IllegalStateException("No '" + CONFIG_ID + "' config found for app_id " + ConfigManager.getAppId()
                    + " - DPA checks cannot be made against the DataSteward API");
        }
        JsonNode auth = ConfigManager.getConfigurationAsJson(AUTH_CONFIG_ID);
        return fromJson(ds, auth);
    }

    static DataStewardConfig fromJson(JsonNode ds, JsonNode auth) {
        String url = required(ds, "url", CONFIG_ID);
        String endpoint = firstNonBlank(ds, auth, "casdoor-endpoint");
        String clientId = firstNonBlank(ds, auth, "client-id");
        String clientSecret = firstNonBlank(ds, auth, "client-secret");
        if (endpoint == null || clientId == null || clientSecret == null) {
            throw new IllegalStateException("Casdoor client credentials (casdoor-endpoint, client-id, client-secret) must be set in the '"
                    + CONFIG_ID + "' or '" + AUTH_CONFIG_ID + "' config");
        }
        //service user credentials are only taken from the datasteward row, never the shared auth row
        String username = blankToNull(ds.path("username").asText(""));
        String password = blankToNull(ds.path("password").asText(""));
        if (username != null && password == null) {
            throw new IllegalStateException("'password' must be set in the '" + CONFIG_ID + "' config when 'username' is");
        }
        Duration cacheTtl = Duration.ofSeconds(ds.path("cache-seconds").asLong(300));
        Duration timeout = Duration.ofSeconds(ds.path("timeout-seconds").asLong(30));
        return new DataStewardConfig(url, endpoint, clientId, clientSecret, username, password, cacheTtl, timeout);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String required(JsonNode node, String field, String configId) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("'" + field + "' must be set in the '" + configId + "' config");
        }
        return value;
    }

    private static String firstNonBlank(JsonNode primary, JsonNode fallback, String field) {
        String value = primary.path(field).asText("");
        if (value.isBlank() && fallback != null) {
            value = fallback.path(field).asText("");
        }
        return value.isBlank() ? null : value;
    }

    @Override
    public String toString() {
        //never log the client secret or password
        return "DataStewardConfig[url=" + url + ", casdoorEndpoint=" + casdoorEndpoint + ", clientId=" + clientId
                + ", grant=" + (username == null ? "client_credentials" : "password as " + username)
                + ", cacheTtl=" + cacheTtl + ", timeout=" + timeout + "]";
    }
}
