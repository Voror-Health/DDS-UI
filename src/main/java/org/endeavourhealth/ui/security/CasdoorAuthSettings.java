package org.endeavourhealth.ui.security;

import com.fasterxml.jackson.databind.JsonNode;
import org.endeavourhealth.common.config.ConfigManager;

/**
 * The Casdoor settings from the config DB {@code auth} row (app_id {@code dds-ui}, falling back to
 * {@code global}) - the same row the CasdoorAuthenticatorValve reads.
 */
public record CasdoorAuthSettings(String endpoint, String organization, String application,
                                  String clientId, String clientSecret) {

    public static final String CONFIG_ID = "auth";

    /** @return the settings, or null if there is no usable {@code auth} row */
    public static CasdoorAuthSettings load() throws Exception {
        return fromJson(ConfigManager.getConfigurationAsJson(CONFIG_ID));
    }

    static CasdoorAuthSettings fromJson(JsonNode auth) {
        if (auth == null || auth.isNull()) {
            return null;
        }
        String endpoint = auth.path("casdoor-endpoint").asText("");
        if (endpoint.isBlank()) {
            return null;
        }
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return new CasdoorAuthSettings(endpoint,
                auth.path("org-name").asText(""),
                auth.path("app-name").asText(""),
                auth.path("client-id").asText(""),
                auth.path("client-secret").asText(""));
    }

    @Override
    public String toString() {
        //never log the secret
        return "CasdoorAuthSettings[endpoint=" + endpoint + ", organization=" + organization
                + ", application=" + application + ", clientId=" + clientId + "]";
    }
}
