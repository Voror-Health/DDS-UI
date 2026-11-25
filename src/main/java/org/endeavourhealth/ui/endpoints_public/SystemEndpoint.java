package org.endeavourhealth.ui.endpoints_public;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.endeavourhealth.ui.security.CasdoorApi;
import org.endeavourhealth.ui.security.CasdoorAuthSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unauthenticated endpoints the SPA needs before it holds a Casdoor token ({@code /public/system/*}).
 * Replaces keycloak.json / common/api's WellKnownEndpoint. Same contract as vhp-datasteward's
 * SystemEndpoint, so the SPA's casdoor.ts is shared.
 */
@Path("/system")
public class SystemEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(SystemEndpoint.class);

    /** Client-side OIDC settings. The client secret is deliberately not included. */
    @GET
    @Path("/auth-config")
    @Produces(MediaType.APPLICATION_JSON)
    public Response authConfig() throws Exception {
        CasdoorAuthSettings settings = CasdoorAuthSettings.load();
        if (settings == null) {
            LOG.error("No usable 'auth' config row for this app - the UI cannot log in until it is added");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "auth config not configured")).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint", settings.endpoint());
        body.put("organization", settings.organization());
        body.put("application", settings.application());
        body.put("clientId", settings.clientId());
        return Response.ok(body).build();
    }

    public record TokenRequest(String grantType, String code, String redirectUri, String refreshToken) {
    }

    /** Server-side code / refresh-token exchange, so the client secret stays on the server. */
    @POST
    @Path("/auth-token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response authToken(TokenRequest req) throws Exception {
        CasdoorAuthSettings settings = CasdoorAuthSettings.load();
        if (settings == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "auth config not configured")).build();
        }
        if (req == null || req.grantType() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "grantType is required")).build();
        }

        CasdoorApi api = CasdoorApi.create(settings);
        CasdoorApi.Result result;
        if ("authorization_code".equals(req.grantType()) && req.code() != null) {
            result = api.exchangeCode(req.code(), req.redirectUri());
        } else if ("refresh_token".equals(req.grantType()) && req.refreshToken() != null) {
            result = api.refresh(req.refreshToken());
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "unsupported grantType")).build();
        }

        if (result.status() != 200) {
            String error = result.body() == null ? "" : result.body().path("error").asText("");
            LOG.warn("Casdoor token exchange failed (grantType={}): HTTP {} {}", req.grantType(), result.status(), error);
            return Response.status(result.status() >= 400 ? result.status() : 401)
                    .entity(Map.of("error", "token exchange failed")).build();
        }

        JsonNode t = result.body();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", t.path("access_token").asText());
        body.put("refresh_token", t.path("refresh_token").asText(""));
        body.put("expires_in", t.path("expires_in").asLong(3600));
        body.put("token_type", t.path("token_type").asText("Bearer"));
        return Response.ok(body).build();
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok(Map.of("status", "UP", "service", "vhp-dds-ui")).build();
    }
}
