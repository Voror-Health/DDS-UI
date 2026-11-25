package org.endeavourhealth.ui.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.endeavourhealth.core.database.dal.DalProvider;
import org.endeavourhealth.core.database.dal.audit.UserAuditDalI;
import org.endeavourhealth.core.database.dal.audit.models.AuditAction;
import org.endeavourhealth.core.database.dal.audit.models.AuditModule;
import org.endeavourhealth.core.database.dal.audit.models.UserEvent;
import org.endeavourhealth.ui.framework.AbstractEndpoint;
import org.endeavourhealth.ui.json.JsonUserEvent;
import org.endeavourhealth.ui.security.CasdoorApi;
import org.endeavourhealth.ui.security.CasdoorAuthSettings;
import org.endeavourhealth.ui.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Jakarta port of common/api's {@code org.endeavourhealth.coreui.endpoints.AuditEndpoint}, which eds-ui
 * served at {@code /api/audit} (the Audit page). The user list now comes from Casdoor instead of the Keycloak
 * admin API; each user's {@code uuid} is the same name-based UUID {@link SecurityUtils} writes to the audit
 * table, so the page's user filter matches.
 */
@Path("/audit")
public final class AuditEndpoint extends AbstractEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(AuditEndpoint.class);
    private static final UserAuditDalI userAuditRepository = DalProvider.factoryUserAuditDal(AuditModule.EdsUiModule.Audit);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/")
    public Response getAudit(@Context SecurityContext sc,
                             @QueryParam("module") String module,
                             @QueryParam("userId") UUID userId,
                             @QueryParam("month") Long monthLong,
                             @QueryParam("organisationId") UUID organisationId) throws Exception {
        super.setLogbackMarkers(sc);

        Date month = new Date(monthLong == null ? System.currentTimeMillis() : monthLong);

        userAuditRepository.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load,
                "Audit",
                "User Id", userId,
                "Organisation Id", organisationId,
                "Module", module);

        List<UserEvent> events = userAuditRepository.load(module, userId, month, organisationId);
        List<JsonUserEvent> entity = events.stream()
                .map(JsonUserEvent::new)
                .collect(Collectors.toList());

        clearLogbackMarkers();
        return Response.ok().entity(entity).build();
    }

    /**
     * Users for the filter drop-down: {@code [{uuid, username, forename, surname}]}. Returns an empty list
     * (and logs) if Casdoor can't be asked, so the page still works.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/users")
    public Response getUsers(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);
        userAuditRepository.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Users");

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode ret = mapper.createArrayNode();
        try {
            CasdoorAuthSettings settings = CasdoorAuthSettings.load();
            JsonNode users = settings == null ? null : CasdoorApi.create(settings).getUsers();
            if (users != null && users.isArray()) {
                for (JsonNode user : users) {
                    String name = user.path("name").asText("");
                    if (name.isEmpty()) {
                        continue;
                    }
                    ObjectNode o = ret.addObject();
                    o.put("uuid", SecurityUtils.toUuid(name).toString());
                    o.put("username", name);
                    o.put("forename", user.path("firstName").asText(""));
                    o.put("surname", user.path("lastName").asText(""));
                    o.put("displayName", user.path("displayName").asText(name));
                }
            }
        } catch (Exception ex) {
            LOG.error("Could not list Casdoor users for the audit page", ex);
        }

        clearLogbackMarkers();
        return Response.ok().entity(ret).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/modules")
    public Response getAuditModules(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);
        userAuditRepository.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Modules");
        clearLogbackMarkers();
        return Response.ok().entity(AuditModule.getModuleList()).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/submodules")
    public Response getAuditSubModules(@Context SecurityContext sc, @QueryParam("module") String module) throws Exception {
        super.setLogbackMarkers(sc);
        userAuditRepository.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load,
                "SubModules", "Module", module);
        clearLogbackMarkers();
        return Response.ok().entity(AuditModule.getSubModuleList(module)).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/actions")
    public Response getAuditActions(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);
        userAuditRepository.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Actions");
        clearLogbackMarkers();
        return Response.ok().entity(AuditAction.getActionList()).build();
    }
}
