package org.endeavourhealth.ui.endpoints;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.endeavourhealth.ui.security.SecurityUtils;
import org.endeavourhealth.core.database.dal.DalProvider;
import org.endeavourhealth.core.database.dal.audit.UserAuditDalI;
import org.endeavourhealth.core.database.dal.audit.models.AuditAction;
import org.endeavourhealth.core.database.dal.audit.models.AuditModule;
import org.endeavourhealth.ui.framework.AbstractEndpoint;
import org.endeavourhealth.ui.json.JsonSftpReaderIgnoreBatchSplitParameters;
import org.endeavourhealth.ui.json.JsonAddFeedRequest;
import org.endeavourhealth.ui.json.JsonInterfaceType;
import org.endeavourhealth.ui.security.Roles;
import org.endeavourhealth.ui.sftpreader.FeedConfigException;
import org.endeavourhealth.ui.sftpreader.FeedValidator;
import org.endeavourhealth.ui.sftpreader.InterfaceTypeValidator;
import org.endeavourhealth.ui.sftpreader.SftpReaderDb;
import org.endeavourhealth.ui.sftpreader.SftpReaderFeedEditor;
import org.endeavourhealth.ui.json.JsonEditFeedRequest;
import org.endeavourhealth.ui.sftpreader.SftpReaderFeedWriter;
import jakarta.annotation.security.RolesAllowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

@Path("/sftpReader")
public class SftpReaderEndpoint extends AbstractEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(SftpReaderEndpoint.class);

    private static final UserAuditDalI userAudit = DalProvider.factoryUserAuditDal(AuditModule.EdsUiModule.Library);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.instances")
    @Path("/instances")
    public Response getInstances(@Context SecurityContext sc,
                                 @QueryParam("includeRetired") boolean includeRetired) throws Exception { //VHP: includeRetired
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get SFTP Reader Instance Names");

        String ret = getSftpReaderInstances(includeRetired);

        clearLogbackMarkers();

        return Response
                .ok()
                .entity(ret)
                .build();
    }

    // ------------------------------------------------------------------ VHP: interface types and Add Feed

    /** VHP: every {@code interface_type} row with its file types (for the Interface Types section and Add Feed). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.interfaceTypes")
    @Path("/interfaceTypes")
    public Response getInterfaceTypes(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);
        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get SFTP Reader Interface Types");

        List<JsonInterfaceType> ret;
        try (Connection connection = SftpReaderDb.getConnection()) {
            ret = SftpReaderFeedWriter.listInterfaceTypes(connection);
        }
        clearLogbackMarkers();
        return Response.ok().entity(new ObjectMapper().writeValueAsString(ret)).build();
    }

    /** VHP: every {@code instance} row, including instances with no configuration (for Add Feed's instance list). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.instanceNames")
    @Path("/instanceNames")
    public Response getInstanceNames(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);
        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get SFTP Reader Instance Names");

        List<String> ret;
        try (Connection connection = SftpReaderDb.getConnection()) {
            ret = SftpReaderFeedWriter.listInstanceNames(connection);
        }
        clearLogbackMarkers();
        return Response.ok().entity(new ObjectMapper().writeValueAsString(ret)).build();
    }

    /** VHP: adds an interface type and its file types. 400 on bad input, 409 if the name or id is in use. */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.addInterfaceType")
    @Path("/interfaceTypes")
    @RolesAllowed(Roles.ADMIN)
    public Response addInterfaceType(@Context SecurityContext sc, JsonInterfaceType body) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            JsonInterfaceType valid = InterfaceTypeValidator.validate(body);
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Add Interface Type",
                    "Interface Type", valid.getInterfaceTypeName(), "Interface Type ID", valid.getInterfaceTypeId());

            JsonInterfaceType created;
            try (Connection connection = SftpReaderDb.getConnection()) {
                created = SftpReaderFeedWriter.addInterfaceType(connection, valid);
            }
            LOG.info("Added SFTP Reader interface type {} ({}) with {} file types", created.getInterfaceTypeId(),
                    created.getInterfaceTypeName(), created.getFileTypes().size());
            return Response.ok().entity(new ObjectMapper().writeValueAsString(created)).build();

        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    /** VHP: deletes an interface type and its file types. 404 if it doesn't exist, 409 while a feed uses it. */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.deleteInterfaceType")
    @Path("/interfaceTypes/{interfaceTypeId}")
    @RolesAllowed(Roles.ADMIN)
    public Response deleteInterfaceType(@Context SecurityContext sc, @PathParam("interfaceTypeId") int interfaceTypeId) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Delete, "Delete Interface Type",
                    "Interface Type ID", interfaceTypeId);
            String name;
            try (Connection connection = SftpReaderDb.getConnection()) {
                name = SftpReaderFeedWriter.deleteInterfaceType(connection, interfaceTypeId);
            }
            LOG.info("Deleted SFTP Reader interface type {} ({})", interfaceTypeId, name);

            ObjectNode ret = new ObjectMapper().createObjectNode();
            ret.put("interfaceTypeId", interfaceTypeId);
            ret.put("interfaceTypeName", name);
            return Response.ok().entity(ret.toString()).build();

        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    /**
     * VHP: adds a feed (the six rows of the deployment guide's SFTP Reader step) in one transaction. 400 on bad input,
     * 409 if the configuration id or friendly name is in use. Never echoes keys or passwords. The reader instance only
     * reads its configurations at start-up, so it must be restarted to pick the feed up.
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.addFeed")
    @Path("/addFeed")
    @RolesAllowed(Roles.ADMIN)
    public Response addFeed(@Context SecurityContext sc, JsonAddFeedRequest body) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            JsonAddFeedRequest valid = FeedValidator.validate(body);
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Add Feed",
                    "Configuration ID", valid.getConfigurationId(), "Instance", valid.getInstanceName());

            try (Connection connection = SftpReaderDb.getConnection()) {
                SftpReaderFeedWriter.addFeed(connection, valid);
            }
            LOG.info("Added SFTP Reader feed {} to instance {}{}", valid.getConfigurationId(), valid.getInstanceName(),
                    valid.getCreateInstance() ? " (new instance)" : "");

            ObjectNode ret = new ObjectMapper().createObjectNode();
            ret.put("instanceName", valid.getInstanceName());
            ret.put("configurationId", valid.getConfigurationId());
            ret.put("restartRequired", true);
            return Response.ok().entity(ret.toString()).build();

        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    // ------------------------------------------------------------------ VHP: edit / retire / reinstate / delete a feed
    // (docs/dds-ui-sftp-feed-edit-delete.md). All admin-only; writes take the reader's per-feed advisory lock.

    /** VHP: one feed's settings for the edit form; private keys and passwords are replaced by "...Set" flags. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.getFeed")
    @Path("/feeds/{configurationId}")
    @RolesAllowed(Roles.ADMIN)
    public Response getFeed(@Context SecurityContext sc, @PathParam("configurationId") String configurationId) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get SFTP Reader Feed",
                    "Configuration ID", configurationId);
            try (Connection connection = SftpReaderDb.getConnection()) {
                return Response.ok().entity(SftpReaderFeedEditor.getFeed(connection, configurationId).toString()).build();
            }
        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    /** VHP: edits a feed. Body: the Add Feed fields (secrets optional) plus the {@code version} from {@link #getFeed}. */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.updateFeed")
    @Path("/feeds/{configurationId}")
    @RolesAllowed(Roles.ADMIN)
    public Response updateFeed(@Context SecurityContext sc, @PathParam("configurationId") String configurationId,
                               JsonEditFeedRequest body) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            String version = body == null ? null : body.getVersion();
            JsonAddFeedRequest valid = FeedValidator.validateEdit(body, configurationId);

            SftpReaderFeedEditor.EditResult result;
            try (Connection connection = SftpReaderDb.getConnection()) {
                result = SftpReaderFeedEditor.updateFeed(connection, valid, version);
            }
            // field names only, never values (some are keys and passwords)
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Edit Feed",
                    "Configuration ID", configurationId, "Changed", String.join(", ", result.changedFields()));
            LOG.info("Edited SFTP Reader feed {}: {}", configurationId, result.changedFields());

            ObjectNode ret = new ObjectMapper().createObjectNode();
            ret.put("configurationId", configurationId);
            ret.put("instanceName", valid.getInstanceName());
            ret.putPOJO("changedFields", result.changedFields());
            ret.putPOJO("restartInstances", result.restartInstances());
            ret.put("restartRequired", !result.restartInstances().isEmpty());
            return Response.ok().entity(new ObjectMapper().writeValueAsString(ret)).build();

        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    /** VHP: retires a feed (moves it to the NOT_USED instance and pauses notifying); history is kept. */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.retireFeed")
    @Path("/feeds/{configurationId}/retire")
    @RolesAllowed(Roles.ADMIN)
    public Response retireFeed(@Context SecurityContext sc, @PathParam("configurationId") String configurationId) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            String oldInstance;
            try (Connection connection = SftpReaderDb.getConnection()) {
                oldInstance = SftpReaderFeedEditor.retireFeed(connection, configurationId);
            }
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Retire Feed",
                    "Configuration ID", configurationId, "Instance", oldInstance);
            LOG.info("Retired SFTP Reader feed {} (was on instance {})", configurationId, oldInstance);

            ObjectNode ret = new ObjectMapper().createObjectNode();
            ret.put("configurationId", configurationId);
            ret.put("previousInstanceName", oldInstance);
            ret.put("restartRequired", oldInstance != null);
            return Response.ok().entity(ret.toString()).build();

        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    /** VHP: gives a retired feed an instance again (body {@code {"instanceName": ...}}). It stays paused. */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.reinstateFeed")
    @Path("/feeds/{configurationId}/reinstate")
    @RolesAllowed(Roles.ADMIN)
    public Response reinstateFeed(@Context SecurityContext sc, @PathParam("configurationId") String configurationId,
                                  Map<String, String> body) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            String instanceName = body == null ? null : body.get("instanceName");
            try (Connection connection = SftpReaderDb.getConnection()) {
                SftpReaderFeedEditor.reinstateFeed(connection, configurationId, instanceName);
            }
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Reinstate Feed",
                    "Configuration ID", configurationId, "Instance", instanceName);
            LOG.info("Reinstated SFTP Reader feed {} on instance {}", configurationId, instanceName);

            ObjectNode ret = new ObjectMapper().createObjectNode();
            ret.put("configurationId", configurationId);
            ret.put("instanceName", instanceName.trim());
            ret.put("restartRequired", true);
            return Response.ok().entity(ret.toString()).build();

        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    /** VHP: deletes a feed that has never received a batch (409 otherwise: retire it instead). */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.deleteFeed")
    @Path("/feeds/{configurationId}")
    @RolesAllowed(Roles.ADMIN)
    public Response deleteFeed(@Context SecurityContext sc, @PathParam("configurationId") String configurationId) throws Exception {
        super.setLogbackMarkers(sc);
        try {
            Map<String, Integer> deleted;
            try (Connection connection = SftpReaderDb.getConnection()) {
                deleted = SftpReaderFeedEditor.deleteFeed(connection, configurationId);
            }
            userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Delete, "Delete Feed",
                    "Configuration ID", configurationId, "Rows", deleted);
            LOG.info("Deleted SFTP Reader feed {}: {}", configurationId, deleted);

            ObjectNode ret = new ObjectMapper().createObjectNode();
            ret.put("configurationId", configurationId);
            ret.putPOJO("deletedRows", deleted);
            return Response.ok().entity(new ObjectMapper().writeValueAsString(ret)).build();

        } catch (FeedConfigException ex) {
            return refused(ex);
        } finally {
            clearLogbackMarkers();
        }
    }

    private static Response refused(FeedConfigException ex) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("message", ex.getMessage());
        return Response.status(ex.getStatus()).entity(body.toString()).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.status")
    @Path("/status")
    public Response getChannelStatus(@Context SecurityContext sc, @QueryParam("configurationId") String configurationId) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get SFTP Reader Instance Status", "ConfigurationId", configurationId);

        String ret = getSftpReaderStatus(configurationId);

        clearLogbackMarkers();

        return Response
                .ok()
                .entity(ret)
                .build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="SftpReaderEndpoint.history")
    @Path("/history")
    public Response getChannelHistory(@Context SecurityContext sc,
                                      @QueryParam("from") Long fromDateMillis,
                                      @QueryParam("to") Long toDateMillis,
                                      @QueryParam("configurationId") String configurationId) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get SFTP Reader Instance History");

        Date dFrom = new Date(fromDateMillis);
        Date dTo = new Date(toDateMillis);

        String ret = getSftpReaderHistory(configurationId, dFrom, dTo);

        clearLogbackMarkers();

        return Response
                .ok()
                .entity(ret)
                .build();
    }



    private String getSftpReaderHistory(String configurationId, Date dFrom, Date dTo) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode root = new ArrayNode(mapper.getNodeFactory());

        Connection connection = SftpReaderDb.getConnection();
        PreparedStatement ps = null;
        try {

            //get the individual orgs within each batch and stick in a map
            String sql = null;
            sql = "SELECT b.batch_id, s.batch_split_id, s.organisation_id, s.have_notified, s.is_bulk, m.inbound, m.error_text"
                    + " FROM batch b"
                    + " INNER JOIN batch_split s"
                    + " ON s.batch_id = b.batch_id"
                    + " LEFT OUTER JOIN notification_message m"
                    + " on m.batch_id = s.batch_id"
                    + " and m.batch_split_id = s.batch_split_id"
                    + " and not exists ("
                    + " select 1"
                    + " from notification_message m2"
                    + " where m2.batch_id = m.batch_id"
                    + " and m2.batch_split_id = m.batch_split_id"
                    + " and m2.timestamp > m.timestamp"
                    + " )"
                    + " WHERE b.configuration_id = ?"
                    + " AND b.insert_date >= ?"
                    + " AND b.insert_date <= ?"
                    + " ORDER BY s.organisation_id";

            ps = connection.prepareStatement(sql);

            int col = 1;
            ps.setString(col++, configurationId);
            ps.setTimestamp(col++, new Timestamp(dFrom.getTime()));
            ps.setTimestamp(col++, new Timestamp(dTo.getTime()));

            Map<Integer, List<BatchSplit>> hmOrgsByBatch = new HashMap<>(); //bit nasty having a map of maps, but quick

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                col = 1;
                int batchId = rs.getInt(col++);
                int batchSplitId = rs.getInt(col++);
                String orgId = rs.getString(col++);
                boolean notified = rs.getBoolean(col++);
                boolean isBulk = rs.getBoolean(col++);
                String notificationResult = rs.getString(col++);
                String errorText = rs.getString(col++);

                BatchSplit s = new BatchSplit();
                s.setBatchSplitId(batchSplitId);
                s.setOrgId(orgId);
                s.setNotified(notified);
                s.setBulk(isBulk);
                s.setNotificationResult(notificationResult);
                s.setErrorText(errorText);

                List<BatchSplit> l = hmOrgsByBatch.get(new Integer(batchId));
                if (l == null) {
                    l = new ArrayList<>();
                    hmOrgsByBatch.put(new Integer(batchId), l);
                }
                l.add(s);
            }

            ps.close();
            ps = null;

            //get the details of the batches and files
            sql = null;
            sql = "SELECT b.batch_id, b.insert_date, b.batch_identifier, b.sequence_number, b.is_complete, b.extract_date, b.extract_cutoff, count(f.batch_file_id), sum(remote_size_bytes)"
                    + " FROM batch b"
                    + " INNER JOIN batch_file f"
                    + " ON f.batch_id = b.batch_id"
                    + " WHERE b.configuration_id = ?"
                    + " AND b.insert_date >= ?"
                    + " AND b.insert_date <= ?"
                    + " GROUP BY b.batch_id, b.insert_date, b.batch_identifier, b.sequence_number, b.is_complete, b.extract_date, b.extract_cutoff"
                    + " ORDER BY b.insert_date desc";

            ps = connection.prepareStatement(sql);

            col = 1;
            ps.setString(col++, configurationId);
            ps.setTimestamp(col++, new Timestamp(dFrom.getTime()));
            ps.setTimestamp(col++, new Timestamp(dTo.getTime()));

            rs = ps.executeQuery();
            while (rs.next()) {
                col = 1;
                int batchId = rs.getInt(col++);
                Date dReceived = new Date(rs.getTimestamp(col++).getTime());
                String batchIdentifier = rs.getString(col++);
                int sequenceNumber = rs.getInt(col++);
                boolean isComplete = rs.getBoolean(col++);

                java.sql.Timestamp ts = rs.getTimestamp(col++);
                Date extractDate = null;
                if (ts != null) {
                    extractDate = new Date(ts.getTime());
                }

                ts = rs.getTimestamp(col++);
                Date extractCutoff = null;
                if (ts != null) {
                    extractCutoff = new Date(ts.getTime());
                }

                int numFiles = rs.getInt(col++);
                long sizeBytes = rs.getLong(col++);
                String sizeDesc = FileUtils.byteCountToDisplaySize(sizeBytes);

                ObjectNode obj = root.addObject();
                obj.put("id", batchId);
                obj.put("received", dReceived.getTime());
                obj.put("identifier", batchIdentifier);
                obj.put("sequenceNumber", sequenceNumber);
                obj.put("complete", isComplete);
                if (extractDate != null) {
                    obj.put("extractDate", extractDate.getTime());
                }
                if (extractCutoff != null) {
                    obj.put("extractCutoff", extractCutoff.getTime());
                }
                obj.put("fileCount", numFiles);
                obj.put("sizeBytes", sizeBytes);
                obj.put("sizeDesc", sizeDesc);
                ArrayNode orgsArr = obj.putArray("batchContents");

                List<BatchSplit> batchSplits = hmOrgsByBatch.get(new Integer(batchId));
                if (batchSplits != null) {
                    for (BatchSplit s: batchSplits) {

                        ObjectNode orgNode = orgsArr.addObject();
                        orgNode.put("batchSplitId", s.getBatchSplitId());
                        orgNode.put("orgId", s.getOrgId());
                        orgNode.put("notified", s.isNotified());
                        orgNode.put("isBulk", s.isBulk);
                        orgNode.put("result", s.getNotificationResult());
                        orgNode.put("error", s.getErrorText());
                    }
                }
            }
            ps.close();

        } finally {
            if (ps != null) {
                ps.close();
            }
            connection.close();
        }

        return mapper.writeValueAsString(root);
    }


    /**
     * VHP: the feed list. hasBatches (Edit/Delete need it) was added; retired feeds (instance NOT_USED) are dropped in
     * code unless includeRetired. Public so the opt-in MySQL test can run it against the reader's schema.
     */
    public static final String INSTANCES_SQL = "SELECT c.configuration_id, c.configuration_friendly_name, i.instance_name, p.dt_paused, "
            + "EXISTS (SELECT 1 FROM batch b WHERE b.configuration_id = c.configuration_id) "
            + "FROM configuration c "
            + "INNER JOIN instance_configuration i "
            + "ON i.configuration_id = c.configuration_id "
            + "LEFT OUTER JOIN configuration_paused_notifying p "
            + "ON p.configuration_id = c.configuration_id";

    private String getSftpReaderInstances(boolean includeRetired) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode root = new ArrayNode(mapper.getNodeFactory());

        Connection connection = SftpReaderDb.getConnection();
        PreparedStatement ps = null;
        try {

            String sql = null;
            sql = INSTANCES_SQL;
            ps = connection.prepareStatement(sql);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int col = 1;
                String configurationId = rs.getString(col++);
                String friendlyName = rs.getString(col++);
                String instanceName = rs.getString(col++);
                Timestamp tsPaused = rs.getTimestamp(col++);
                boolean hasBatches = rs.getBoolean(col++);

                //configurations that are no longer in use are assigned to a special instance name
                boolean retired = SftpReaderFeedEditor.RETIRED_INSTANCE.equals(instanceName);
                if (instanceName == null
                    || (retired && !includeRetired)) {
                    continue;
                }

                ObjectNode obj = root.addObject();
                obj.put("instanceName", instanceName);
                obj.put("configurationId", configurationId);
                obj.put("friendlyName", friendlyName);
                obj.put("hasBatches", hasBatches);
                obj.put("retired", retired);
                if (tsPaused != null) {
                    obj.put("dtPaused", tsPaused.getTime());
                }
            }
            ps.close();

        } finally {
            if (ps != null) {
                ps.close();
            }
            connection.close();
        }

        return mapper.writeValueAsString(root);
    }


    /**
     * all the below should be moved to core or similar if anything like it is needed elsewhere
     */
    private String getSftpReaderStatus(String configurationId) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = new ObjectNode(mapper.getNodeFactory());

        Connection connection = SftpReaderDb.getConnection();
        PreparedStatement ps = null;
        try {

            String sql = null;
            sql = "SELECT c.poll_frequency_seconds, t.data_frequency_days"
                    + " FROM configuration c"
                    + " LEFT OUTER JOIN interface_type t"
                    + " ON c.interface_type_id = t.interface_type_id"
                    + " WHERE c.configuration_id = ?";

            ps = connection.prepareStatement(sql);
            ps.setString(1, configurationId);

            ResultSet rs = ps.executeQuery();
            rs.next();

            int col = 1;
            int freq = rs.getInt(col++);
            int dataFrequencyDays = rs.getInt(col++);

            root.put("id", configurationId);
            root.put("pollFrequencySeconds", freq);
            root.put("dataFrequencyDays", dataFrequencyDays);

            ps.close();


            //get the latest polling attempt
            sql = "SELECT attempt_started, attempt_finished, exception_text, files_downloaded,"
                    + " batches_completed, batch_splits_notified_ok, batch_splits_notified_failure"
                    + " FROM configuration_polling_attempt"
                    + " WHERE configuration_id = ?"
                    + " ORDER BY attempt_started DESC"
                    + " LIMIT 1";
            ps = connection.prepareStatement(sql);
            ps.setString(1, configurationId);

            rs = ps.executeQuery();
            if (rs.next()) {
                col = 1;
                Date attemptStartDate = new Date(rs.getTimestamp(col++).getTime());
                Date attemptEndDate = new Date(rs.getTimestamp(col++).getTime());
                String exceptionText = rs.getString(col++);
                int filesDownloaded = rs.getInt(col++);
                int batchesCompleted = rs.getInt(col++);
                int batchSplitsNotifiedOk = rs.getInt(col++);
                int batchSplitsNotifiedFailure = rs.getInt(col++);

                root.put("latestPollingStart", attemptStartDate.getTime());
                root.put("latestPollingEnd", attemptEndDate.getTime());
                root.put("latestPollingException", exceptionText);
                root.put("latestPollingFilesDownloaded", filesDownloaded);
                root.put("latestPollingBatchesCompleted", batchesCompleted);
                root.put("latestPollingBatchSplitsNotifiedOk", batchSplitsNotifiedOk);
                root.put("latestPollingBatchSplitsNotifiedFailure", batchSplitsNotifiedFailure);
            }

            ps.close();


            //get the latest batch received
            sql = "select b.batch_id, b.batch_identifier, b.insert_date, b.sequence_number, b.is_complete, b.extract_date, b.extract_cutoff, count(1), sum(f.remote_size_bytes)"
                    + " from batch b"
                    + " left outer join batch_file f"
                    + " on f.batch_id = b.batch_id"
                    + " where b.configuration_id = ?"
                    + " group by b.batch_id, b.batch_identifier, b.insert_date, b.sequence_number, b.is_complete, b.extract_date, b.extract_cutoff"
                    + " order by b.insert_date desc"
                    + " limit 1";
            ps = connection.prepareStatement(sql);
            ps.setString(1, configurationId);

            rs = ps.executeQuery();
            if (rs.next()) {
                col = 1;
                int batchId = rs.getInt(col++);
                String batchIdentifier = rs.getString(col++);
                Date insertDate = new Date(rs.getTimestamp(col++).getTime());
                int sequenceNumber = rs.getInt(col++);
                boolean isComplete = rs.getBoolean(col++);

                java.sql.Timestamp ts = rs.getTimestamp(col++);
                Date extractDate = null;
                if (ts != null) {
                    extractDate = new Date(ts.getTime());
                }

                ts = rs.getTimestamp(col++);
                Date extractCutoff = null;
                if (ts != null) {
                    extractCutoff = new Date(ts.getTime());
                }

                int fileCount = rs.getInt(col++);
                long extractSize = rs.getLong(col++);

                String totalSizeReadable = FileUtils.byteCountToDisplaySize(extractSize);

                root.put("latestBatchId", batchId);
                root.put("latestBatchIdentifier", batchIdentifier);
                root.put("latestBatchReceived", insertDate.getTime());
                if (extractDate != null) {
                    root.put("latestBatchExtractDate", extractDate.getTime());
                }
                if (extractCutoff != null) {
                    root.put("latestBatchExtractCutoff", extractCutoff.getTime());
                }
                root.put("latestBatchSequenceNumber", sequenceNumber);
                root.put("latestBatchComplete", isComplete);
                root.put("latestBatchFileCount", fileCount);
                root.put("latestBatchSizeBytes", totalSizeReadable);
            }

            ps.close();

            //find the latest complete batch
            sql = "SELECT batch_id, batch_identifier, insert_date, sequence_number, complete_date, extract_date, extract_cutoff"
                    + " FROM batch"
                    + " WHERE configuration_id = ? AND is_complete = true"
                    + " ORDER BY sequence_number desc"
                    + " LIMIT 1";
            ps = connection.prepareStatement(sql);
            ps.setString(1, configurationId);

            Integer latestCompleteBatchId = null;

            rs = ps.executeQuery();
            if (rs.next()) {
                col = 1;
                latestCompleteBatchId = rs.getInt(col++);
                String batchIdentifier = rs.getString(col++);
                Date insertDate = new Date(rs.getTimestamp(col++).getTime());
                int sequenceNumber = rs.getInt(col++);

                java.sql.Timestamp ts = rs.getTimestamp(col++);
                Date completeDate = null;
                if (ts != null) {
                    completeDate = new Date(ts.getTime());
                }

                ts = rs.getTimestamp(col++);
                Date extractDate = null;
                if (ts != null) {
                    extractDate = new Date(ts.getTime());
                }

                ts = rs.getTimestamp(col++);
                Date extractCutoff = null;
                if (ts != null) {
                    extractCutoff = new Date(ts.getTime());
                }

                root.put("completeBatchId", latestCompleteBatchId);
                if (completeDate != null) {
                    root.put("completeBatchCompletionDate", completeDate.getTime());
                }
                root.put("completeBatchIdentifier", batchIdentifier);
                root.put("completeBatchReceived", insertDate.getTime());
                if (extractDate != null) {
                    root.put("completeBatchExtractDate", extractDate.getTime());
                }
                if (extractCutoff != null) {
                    root.put("completeBatchExtractCutoff", extractCutoff.getTime());
                }
                root.put("completeBatchSequenceNumber", sequenceNumber);
            }

            ps.close();

            if (latestCompleteBatchId != null) {

                //get any errors for orgs in this configuration - since errors posting to the Messaging API
                //will most likely be on past exchanges and be blocking the latest exchange
                Map<String, String> hmErrorsPerOrg = new HashMap<>();

                sql = "SELECT organisation_id, error_text"
                        + " FROM batch_split bs"
                        + " INNER JOIN batch b"
                        + " ON b.batch_id = bs.batch_id"
                        + " INNER JOIN notification_message m"
                        + " on m.batch_id = bs.batch_id"
                        + " and m.batch_split_id = bs.batch_split_id"
                        + " and not exists ("
                        + " select 1"
                        + " from notification_message m2"
                        + " where m2.batch_id = m.batch_id"
                        + " and m2.batch_split_id = m.batch_split_id"
                        + " and m2.timestamp < m.timestamp"
                        + " )"
                        + " WHERE b.configuration_id = ?"
                        + " AND b.is_complete = true"
                        + " AND bs.have_notified = false"
                        + " and m.error_text is not null";
                ps = connection.prepareStatement(sql);
                ps.setString(1, configurationId);

                rs = ps.executeQuery();

                while (rs.next()) {

                    col = 1;
                    String orgId = rs.getString(col++);
                    String notificationError = rs.getString(col++);

                    hmErrorsPerOrg.put(orgId, notificationError);
                }

                ps.close();

                //get the batch splits for the complete batch
                sql = "select s.batch_split_id, s.organisation_id, s.have_notified, s.is_bulk, m.inbound, m.error_text"
                        + " from batch_split s"
                        + " left outer join notification_message m"
                        + " on m.batch_id = s.batch_id"
                        + " and m.batch_split_id = s.batch_split_id"
                        + " and not exists ("
                        + " select 1"
                        + " from notification_message m2"
                        + " where m2.batch_id = m.batch_id"
                        + " and m2.batch_split_id = m.batch_split_id"
                        + " and m2.timestamp > m.timestamp"
                        + " )"
                        + " where s.batch_id = ?"
                        + " ORDER BY s.organisation_id";
                ps = connection.prepareStatement(sql);

                ps.setInt(1, latestCompleteBatchId);

                rs = ps.executeQuery();

                ArrayNode arr = root.putArray("completeBatchContents");

                while (rs.next()) {

                    col = 1;
                    int batchSplitId = rs.getInt(col++);
                    String orgId = rs.getString(col++);
                    boolean haveNotified = rs.getBoolean(col++);
                    boolean isBulk = rs.getBoolean(col++);
                    String notificationResult = rs.getString(col++);
                    String notificationError = rs.getString(col++);

                    //if no error message for this record, then look in the map because there should be one
                    //for this org, but for an earlier batch, that's blocking this one for the org
                    if (!haveNotified
                            && Strings.isNullOrEmpty(notificationError)) {
                        notificationError = hmErrorsPerOrg.get(orgId);
                    }

                    ObjectNode orgNode = arr.addObject();
                    orgNode.put("batchSplitId", batchSplitId);
                    orgNode.put("orgId", orgId);
                    orgNode.put("notified", haveNotified);
                    orgNode.put("isBulk", isBulk);
                    orgNode.put("result", notificationResult);
                    orgNode.put("error", notificationError);
                }

                ps.close();
            }

        } finally {
            if (ps != null) {
                ps.close();
            }
            connection.close();
        }

        return mapper.writeValueAsString(root);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/ignore")
    @RolesAllowed(Roles.ADMIN) //VHP: writes to the SFTP Reader DB; was open to any UI user in the original
    public Response ignoreBatchSplit(@Context SecurityContext sc,
                                     JsonSftpReaderIgnoreBatchSplitParameters parameters) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Ignore", "Configuration ID", parameters.getConfigurationId(), "Batch ID", parameters.getBatchId(), "Batch Split ID", parameters.getBatchSplitId());

        ignoreBatchSplitImpl(parameters.getConfigurationId(), parameters.getBatchId(), parameters.getBatchSplitId(), parameters.getReason());

        clearLogbackMarkers();

        return Response
                .ok()
                .build();
    }

    private void ignoreBatchSplitImpl(String configurationId, int batchId, int batchSplitId, String reason) throws Exception {
        Connection connection = SftpReaderDb.getConnection();
        PreparedStatement psInsert = null;
        PreparedStatement psUpdate = null;
        try {
            String sql = null;
            sql = "INSERT INTO notification_message (batch_id, batch_split_id, configuration_id, message_uuid, timestamp, inbound, was_success)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";
            psInsert = connection.prepareStatement(sql);

            Date d = new Date();

            int col = 1;
            psInsert.setInt(col++, batchId);
            psInsert.setInt(col++, batchSplitId);
            psInsert.setString(col++, configurationId);
            psInsert.setString(col++, UUID.randomUUID().toString());
            psInsert.setTimestamp(col++, new java.sql.Timestamp(d.getTime()));
            psInsert.setString(col++, reason);
            psInsert.setBoolean(col++, true);
            psInsert.executeUpdate();

            sql = "UPDATE batch_split"
                    + " SET have_notified = ?, notification_date = ?"
                    + " WHERE batch_split_id = ?";
            psUpdate = connection.prepareStatement(sql);

            col = 1;
            psUpdate.setBoolean(col++, true);
            psUpdate.setTimestamp(col++, new java.sql.Timestamp(d.getTime()));
            psUpdate.setInt(col++, batchSplitId);
            psUpdate.executeUpdate();

            connection.commit();

        } finally {
            if (psInsert != null) {
                psInsert.close();
            }
            if (psUpdate != null) {
                psUpdate.close();
            }
            connection.close();
        }
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/togglePause")
    @RolesAllowed(Roles.ADMIN) //VHP: writes to the SFTP Reader DB; was open to any UI user in the original
    public Response togglePause(@Context SecurityContext sc,
                                     JsonSftpReaderIgnoreBatchSplitParameters parameters) throws Exception { //just re-using this object since it's already got the field we need
        super.setLogbackMarkers(sc);

        String configurationId = parameters.getConfigurationId();
        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "TogglePause", "Configuration ID", configurationId);

        Map<String, Date> pausedState = getPausedState();
        boolean isPaused = pausedState.get(configurationId) != null;

        Connection connection = SftpReaderDb.getConnection();
        PreparedStatement ps = null;
        try {
            String sql = null;
            if (isPaused) {
                sql = "DELETE FROM configuration_paused_notifying WHERE configuration_id = ?";
            } else {
                sql = "INSERT INTO configuration_paused_notifying (configuration_id) VALUES (?)";
            }
            ps = connection.prepareStatement(sql);
            ps.setString(1, configurationId);
            ps.executeUpdate();
            connection.commit();

        } catch (Exception ex) {
            connection.rollback();
            throw ex;

        } finally {
            if (ps != null) {
                ps.close();
            }
            connection.close();
        }

        clearLogbackMarkers();

        return Response
                .ok()
                .build();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/togglePauseAll")
    @RolesAllowed(Roles.ADMIN) //VHP: writes to the SFTP Reader DB; was open to any UI user in the original
    public Response togglePauseAll(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "TogglePauseAll");

        //work out whether to pause or not based on which is most
        List<String> paused = new ArrayList<>();
        List<String> notPaused = new ArrayList<>();

        Map<String, Date> pausedState = getPausedState();
        for (String s: pausedState.keySet()) {
            boolean isPaused = pausedState.get(s) != null;
            if (isPaused) {
                paused.add(s);
            } else {
                notPaused.add(s);
            }
        }

        Connection connection = SftpReaderDb.getConnection();
        PreparedStatement ps = null;
        try {
            String sql = null;
            List<String> configs = null;

            if (paused.size() > notPaused.size()) {
                configs = paused;

                sql = "DELETE FROM configuration_paused_notifying WHERE configuration_id = ?";
            } else {
                configs = notPaused;

                sql = "INSERT INTO configuration_paused_notifying (configuration_id) VALUES (?)";
            }
            ps = connection.prepareStatement(sql);

            for (String configurationId: configs) {
                ps.setString(1, configurationId);
                ps.addBatch();
            }
            ps.executeBatch();

            connection.commit();

        } catch (Exception ex) {
            connection.rollback();
            throw ex;

        } finally {
            if (ps != null) {
                ps.close();
            }
            connection.close();
        }

        clearLogbackMarkers();

        return Response
                .ok()
                .build();
    }

    private Map<String, Date> getPausedState() throws Exception {

        Connection connection = SftpReaderDb.getConnection();
        PreparedStatement ps = null;
        try {
            String sql = null;
            sql = "SELECT c.configuration_id, p.dt_paused FROM configuration c LEFT OUTER JOIN configuration_paused_notifying p ON p.configuration_id = c.configuration_id;";
            ps = connection.prepareStatement(sql);

            Map<String, Date> ret = new HashMap<>();

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String configurationId = rs.getString(1);
                Date dtPaused = null;

                Timestamp ts = rs.getTimestamp(2);
                if (ts != null) {
                    dtPaused = new java.util.Date(ts.getTime());
                }

                ret.put(configurationId, dtPaused);
            }

            return ret;

        } finally {
            if (ps != null) {
                ps.close();
            }
            connection.close();
        }
    }

    /**
     * temporary helper class
     */
    static class BatchSplit {
        private int batchSplitId;
        private String orgId;
        private boolean notified;
        private boolean isBulk;
        private String notificationResult;
        private String errorText;

        public int getBatchSplitId() {
            return batchSplitId;
        }

        public void setBatchSplitId(int batchSplitId) {
            this.batchSplitId = batchSplitId;
        }

        public String getOrgId() {
            return orgId;
        }

        public void setOrgId(String orgId) {
            this.orgId = orgId;
        }

        public boolean isNotified() {
            return notified;
        }

        public void setNotified(boolean notified) {
            this.notified = notified;
        }

        public boolean isBulk() {
            return isBulk;
        }

        public void setBulk(boolean bulk) {
            isBulk = bulk;
        }

        public String getNotificationResult() {
            return notificationResult;
        }

        public void setNotificationResult(String notificationResult) {
            this.notificationResult = notificationResult;
        }

        public String getErrorText() {
            return errorText;
        }

        public void setErrorText(String errorText) {
            this.errorText = errorText;
        }
    }
}
