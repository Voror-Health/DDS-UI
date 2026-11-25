package org.endeavourhealth.ui.endpoints;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.endeavourhealth.core.database.dal.DalProvider;
import org.endeavourhealth.core.database.dal.audit.UserAuditDalI;
import org.endeavourhealth.core.database.dal.audit.models.AuditAction;
import org.endeavourhealth.core.database.dal.audit.models.AuditModule;
import org.endeavourhealth.ui.framework.AbstractEndpoint;
import org.endeavourhealth.ui.json.JsonMessageFormat;
import org.endeavourhealth.ui.messageformats.MessageFormats;
import org.endeavourhealth.ui.security.Roles;
import org.endeavourhealth.ui.security.SecurityUtils;
import org.endeavourhealth.ui.sftpreader.FeedConfigException;

/**
 * VHP: the list of message formats that can be processed ({@link MessageFormats}), for the System Config page's
 * Message Format and the SFTP Reader feed form's Software content type.
 *
 * <p>{@code GET} answers {@code {"source": "config"|"default", "warning": ..., "formats": [...]}}; it is reference data,
 * so it is not audited. {@code POST} (admin) adds one format and answers the same shape.</p>
 */
@Path("/messageFormats")
public class MessageFormatEndpoint extends AbstractEndpoint {

    private static final UserAuditDalI userAudit = DalProvider.factoryUserAuditDal(AuditModule.EdsUiModule.Library);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name = "MessageFormatEndpoint.list")
    public Response list(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            return Response.ok().entity(toJson(MessageFormats.load())).build();
        } finally {
            clearLogbackMarkers();
        }
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name = "MessageFormatEndpoint.add")
    @RolesAllowed(Roles.ADMIN)
    public Response add(@Context SecurityContext sc, JsonMessageFormat body) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            MessageFormats.Snapshot after = MessageFormats.add(body);
            JsonMessageFormat added = after.formats.get(after.formats.size() - 1);
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Add Message Format",
                    "Format", added.getFormat(), "SFTP Reader", String.valueOf(added.isSftpReader()),
                    "Transform", String.valueOf(added.isTransform()));
            return Response.ok().entity(toJson(after)).build();
        } catch (FeedConfigException ex) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("message", ex.getMessage());
            return Response.status(ex.getStatus()).entity(err.toString()).type(MediaType.APPLICATION_JSON_TYPE).build();
        } finally {
            clearLogbackMarkers();
        }
    }

    private static String toJson(MessageFormats.Snapshot s) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("source", s.source);
        root.put("warning", s.warning);
        root.set("formats", MAPPER.valueToTree(s.formats));
        return MAPPER.writeValueAsString(root);
    }
}
