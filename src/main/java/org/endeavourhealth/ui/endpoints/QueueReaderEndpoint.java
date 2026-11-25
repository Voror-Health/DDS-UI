package org.endeavourhealth.ui.endpoints;

import jakarta.annotation.security.RolesAllowed;
import org.endeavourhealth.ui.security.Roles;
import org.endeavourhealth.ui.utility.QueueServerSsh;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.endeavourhealth.common.config.ConfigManager;
import org.endeavourhealth.ui.security.SecurityUtils;
import org.endeavourhealth.common.utility.ExpiringCache;
import org.endeavourhealth.common.utility.XmlSerializer;
import org.endeavourhealth.core.configuration.ConfigDeserialiser;
import org.endeavourhealth.core.configuration.QueueReaderConfiguration;
import org.endeavourhealth.core.database.dal.DalProvider;
import org.endeavourhealth.core.database.dal.admin.ServiceDalI;
import org.endeavourhealth.core.database.dal.admin.models.Service;
import org.endeavourhealth.core.database.dal.audit.ApplicationHeartbeatDalI;
import org.endeavourhealth.core.database.dal.audit.UserAuditDalI;
import org.endeavourhealth.core.database.dal.audit.models.ApplicationHeartbeat;
import org.endeavourhealth.core.database.dal.audit.models.AuditAction;
import org.endeavourhealth.core.database.dal.audit.models.AuditModule;
import org.endeavourhealth.ui.framework.AbstractEndpoint;
import org.endeavourhealth.transform.common.TransformConfig;
import org.endeavourhealth.ui.json.JsonQueueReaderCommand;
import org.endeavourhealth.ui.json.JsonQueueReaderConfigRequest;
import org.endeavourhealth.ui.json.JsonMemoryDefaultsRequest;
import org.endeavourhealth.ui.json.JsonMemoryDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

@Path("/queueReader")
public class QueueReaderEndpoint extends AbstractEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(QueueReaderEndpoint.class);

    private static final UserAuditDalI userAudit = DalProvider.factoryUserAuditDal(AuditModule.EdsUiModule.Library);

    private static ExpiringCache<String, String> odsCodeToPublisherCache = new ExpiringCache<>(1000 * 60 * 5); //cache for five mins

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.instances")
    @Path("/instances")
    public Response getInstances(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get Queue Reader Instances");

        String ret = getQueueReaderInstancesJson();

        clearLogbackMarkers();

        return Response
                .ok()
                .entity(ret)
                .build();
    }

    private static String getQueueReaderInstancesJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode root = mapper.createArrayNode();

        ApplicationHeartbeatDalI dal = DalProvider.factoryApplicationHeartbeatDal();
        List<ApplicationHeartbeat> latest = dal.getLatest();

        for (ApplicationHeartbeat h : latest) {
            if (!h.getApplicationName().equals("queuereader")) {
                continue;
            }

            String configId = h.getApplicationInstanceName();
            if (Strings.isNullOrEmpty(configId)) {
                continue;
            }

            ObjectNode instanceNode = root.addObject();
            instanceNode.put("configId", configId);
            instanceNode.put("hostName", h.getHostName());
            instanceNode.put("applicationInstanceName", h.getApplicationInstanceName());
            instanceNode.put("applicationInstanceNumber", h.getApplicationInstanceNumber() != null ? h.getApplicationInstanceNumber() : 1);

            long now = System.currentTimeMillis();
            long lastHeartbeat = h.getTimestmp().getTime();
            boolean isRunning = (now - lastHeartbeat) < (1000 * 60 * 2);
            instanceNode.put("status", isRunning ? "running" : "stopped");
            instanceNode.put("lastHeartbeat", lastHeartbeat);

            if (h.getDtStarted() != null) {
                instanceNode.put("dtStarted", h.getDtStarted().getTime());
            }
            if (h.getDtJar() != null) {
                instanceNode.put("dtJar", h.getDtJar().getTime());
            }
            if (h.getMaxHeapMb() != null) {
                instanceNode.put("maxHeapMb", h.getMaxHeapMb());
                instanceNode.put("maxHeapDesc", FileUtils.byteCountToDisplaySize(h.getMaxHeapMb() * (1024L * 1024L)));
            }
            if (h.getCurrentHeapMb() != null) {
                instanceNode.put("currentHeapMb", h.getCurrentHeapMb());
                instanceNode.put("currentHeapDesc", FileUtils.byteCountToDisplaySize(h.getCurrentHeapMb() * (1024L * 1024L)));
            }
            if (h.getServerMemoryMb() != null) {
                instanceNode.put("physicalMemoryMb", h.getServerMemoryMb());
                instanceNode.put("physicalMemoryDesc", FileUtils.byteCountToDisplaySize(h.getServerMemoryMb() * (1024L * 1024L)));
            }
            if (h.getServerCpuUsagePercent() != null) {
                instanceNode.put("cpuLoad", h.getServerCpuUsagePercent());
            }
            instanceNode.put("isBusy", h.getBusy());

            String queueName = null;
            String queueType = null;
            String queueReaderConfigXml = ConfigManager.getConfiguration(configId, "queuereader");
            if (!Strings.isNullOrEmpty(queueReaderConfigXml)) {
                try {
                    QueueReaderConfiguration configuration = ConfigDeserialiser.deserialise(queueReaderConfigXml);
                    queueName = configuration.getQueue();
                    instanceNode.put("queueName", queueName);

                    queueType = QueueTypes.of(queueName); //VHP: Eds*, Vhp* or unprefixed queue names
                } catch (Exception ex) {
                    LOG.warn("Failed to parse queue reader config for " + configId, ex);
                }
            }

            instanceNode.put("queueType", queueType != null ? queueType : "inbound");

            JsonMemoryDefaults memoryDefaults = getMemoryDefaultsForQueueType(queueType != null ? queueType : "inbound");
            if (memoryDefaults != null) {
                ObjectNode defaultsNode = instanceNode.putObject("memoryDefaults");
                defaultsNode.put("maxHeap", memoryDefaults.getMaxHeap());
                defaultsNode.put("unit", memoryDefaults.getUnit());
            }
        }

        return mapper.writeValueAsString(root);
    }

    private static JsonMemoryDefaults getMemoryDefaultsForQueueType(String queueType) {
        String configKey = "queuereader-memory-defaults-" + queueType;
        try {
            String xml = ConfigManager.getConfiguration(configKey, "queuereader");
            if (!Strings.isNullOrEmpty(xml)) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode node = mapper.readTree(xml);
                JsonMemoryDefaults defaults = new JsonMemoryDefaults();
                if (node.has("maxHeap")) {
                    defaults.setMaxHeap(node.get("maxHeap").asInt());
                }
                if (node.has("unit")) {
                    defaults.setUnit(node.get("unit").asText());
                }
                return defaults;
            }
        } catch (Exception ex) {
            LOG.warn("Failed to load memory defaults for queue type: " + queueType, ex);
        }
        return null;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.getConfig")
    @Path("/config/{configId}")
    public Response getConfig(@Context SecurityContext sc, @PathParam("configId") String configId) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get Queue Reader Config", "ConfigId", configId);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();

        String configXml = ConfigManager.getConfiguration(configId, "queuereader");
        if (!Strings.isNullOrEmpty(configXml)) {
            result.put("configXml", configXml);

            try {
                QueueReaderConfiguration configuration = ConfigDeserialiser.deserialise(configXml);
                result.put("queueName", configuration.getQueue());
                result.put("exclusive", configuration.isExclusive() != null && configuration.isExclusive());

                if (configuration.getPipeline() != null) {
                    result.put("pipelineComponents", configuration.getPipeline().getPipelineComponents().size());
                }
            } catch (Exception ex) {
                LOG.warn("Failed to parse queue reader config for " + configId, ex);
            }
        } else {
            result.put("configXml", "");
        }

        String queueName = null;
        try {
            if (!Strings.isNullOrEmpty(configXml)) {
                QueueReaderConfiguration configuration = ConfigDeserialiser.deserialise(configXml);
                queueName = configuration.getQueue();
            }
        } catch (Exception ex) {
            // ignore
        }

        String queueType = determineQueueType(queueName);
        JsonMemoryDefaults memoryDefaults = getMemoryDefaultsForQueueType(queueType);
        
        ObjectNode memoryNode = result.putObject("memory");
        
        if (memoryDefaults != null) {
            memoryNode.put("defaultMaxHeap", memoryDefaults.getMaxHeap());
            memoryNode.put("defaultUnit", memoryDefaults.getUnit());
        } else {
            memoryNode.putNull("defaultMaxHeap");
            memoryNode.putNull("defaultUnit");
        }

        String overrideKey = "queuereader-memory-override-" + configId;
        //VHP: saveConfig writes the override under app_id "queuereader"; read it from there
        String overrideXml = ConfigManager.getConfiguration(overrideKey, "queuereader");
        if (!Strings.isNullOrEmpty(overrideXml)) {
            try {
                JsonNode overrideNode = mapper.readTree(overrideXml);
                if (overrideNode.has("maxHeap") && !overrideNode.get("maxHeap").isNull()) {
                    memoryNode.put("maxHeap", overrideNode.get("maxHeap").asInt());
                } else {
                    memoryNode.putNull("maxHeap");
                }
                if (overrideNode.has("unit") && !overrideNode.get("unit").isNull()) {
                    memoryNode.put("unit", overrideNode.get("unit").asText());
                } else {
                    memoryNode.putNull("unit");
                }
            } catch (Exception ex) {
                LOG.warn("Failed to parse memory override for " + configId, ex);
                memoryNode.putNull("maxHeap");
                memoryNode.putNull("unit");
            }
        } else {
            memoryNode.putNull("maxHeap");
            memoryNode.putNull("unit");
        }

        clearLogbackMarkers();

        return Response.ok().entity(mapper.writeValueAsString(result)).build();
    }

    @RolesAllowed(Roles.ADMIN)
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.saveConfig")
    @Path("/config")
    public Response saveConfig(@Context SecurityContext sc, JsonQueueReaderConfigRequest request) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Save Queue Reader Config", "ConfigId", request.getConfigId());

        String configXml = ConfigManager.getConfiguration(request.getConfigId(), "queuereader");
        
        if (Strings.isNullOrEmpty(configXml)) {
            clearLogbackMarkers();
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Configuration not found\"}")
                    .build();
        }

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode memoryOverride = mapper.createObjectNode();
        
        if (request.getMaxHeap() != null) {
            memoryOverride.put("maxHeap", request.getMaxHeap());
        } else {
            memoryOverride.putNull("maxHeap");
        }
        
        if (request.getUnit() != null) {
            memoryOverride.put("unit", request.getUnit());
        } else {
            memoryOverride.putNull("unit");
        }

        String overrideXml = mapper.writeValueAsString(memoryOverride);
        ConfigManager.setConfiguration("queuereader-memory-override-" + request.getConfigId(), "queuereader", overrideXml);

        clearLogbackMarkers();

        ObjectNode result = mapper.createObjectNode();
        result.put("message", "Configuration saved successfully");
        return Response.ok().entity(mapper.writeValueAsString(result)).build();
    }

    @RolesAllowed(Roles.ADMIN)
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.deleteConfig")
    @Path("/config/{configId}")
    public Response deleteConfig(@Context SecurityContext sc, @PathParam("configId") String configId) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Delete, "Delete Queue Reader Config", "ConfigId", configId);

        ApplicationHeartbeatDalI dal = DalProvider.factoryApplicationHeartbeatDal();
        List<ApplicationHeartbeat> latest = dal.getLatest();
        
        boolean isRunning = false;
        for (ApplicationHeartbeat h : latest) {
            if (h.getApplicationName().equals("queuereader") 
                    && configId.equals(h.getApplicationInstanceName())) {
                long now = System.currentTimeMillis();
                if ((now - h.getTimestmp().getTime()) < (1000 * 60 * 2)) {
                    isRunning = true;
                    break;
                }
            }
        }

        if (isRunning) {
            clearLogbackMarkers();
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"Cannot delete configuration for running instance. Stop the queue reader first.\"}")
                    .build();
        }

        ConfigManager.setConfiguration(configId, "queuereader", null);

        clearLogbackMarkers();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        result.put("message", "Configuration deleted successfully");
        return Response.ok().entity(mapper.writeValueAsString(result)).build();
    }

    @RolesAllowed(Roles.ADMIN)
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.stopInstance")
    @Path("/stop")
    public Response stopInstance(@Context SecurityContext sc, JsonQueueReaderCommand request) throws Exception {
        return signalQueueReaders(sc, request, "Stop", ".kill");
    }

    @RolesAllowed(Roles.ADMIN)
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.restartInstance")
    @Path("/restart")
    public Response restartInstance(@Context SecurityContext sc, JsonQueueReaderCommand request) throws Exception {
        return signalQueueReaders(sc, request, "Restart", ".restart");
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.getMemoryDefaults")
    @Path("/memoryDefaults")
    public Response getMemoryDefaults(@Context SecurityContext sc) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get Queue Reader Memory Defaults");

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode results = mapper.createArrayNode();

        String[] queueTypes = {"inbound", "protocol", "transform", "subscriber"};
        
        for (String queueType : queueTypes) {
            ObjectNode node = results.addObject();
            node.put("queueType", queueType);

            JsonMemoryDefaults defaults = getMemoryDefaultsForQueueType(queueType);
            if (defaults != null) {
                node.put("maxHeap", defaults.getMaxHeap());
                node.put("unit", defaults.getUnit());
            } else {
                node.putNull("maxHeap");
                node.putNull("unit");
            }
        }

        clearLogbackMarkers();

        return Response.ok().entity(mapper.writeValueAsString(results)).build();
    }

    @RolesAllowed(Roles.ADMIN)
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.saveMemoryDefaults")
    @Path("/memoryDefaults")
    public Response saveMemoryDefaults(@Context SecurityContext sc, JsonMemoryDefaultsRequest request) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, "Save Queue Reader Memory Defaults", "QueueType", request.getQueueType());

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode config = mapper.createObjectNode();
        config.put("maxHeap", request.getMaxHeap());
        config.put("unit", request.getUnit());

        String xml = mapper.writeValueAsString(config);
        ConfigManager.setConfiguration("queuereader-memory-defaults-" + request.getQueueType(), "queuereader", xml);

        clearLogbackMarkers();

        ObjectNode result = mapper.createObjectNode();
        result.put("message", "Memory defaults saved successfully");
        return Response.ok().entity(mapper.writeValueAsString(result)).build();
    }

    /**
     * VHP: shared by /stop and /restart (from the dds-ui port). Touches the queue reader's kill/restart file on
     * the server it is running on. The dds-ui version matched config IDs against server host names (so it never
     * found the server), read the IDs from "hostNames" while its page sent "configIds", treated an empty list as
     * "every queue reader", and connected over SSH without a key.
     */
    private Response signalQueueReaders(SecurityContext sc, JsonQueueReaderCommand request, String verb, String fileSuffix) throws Exception {
        super.setLogbackMarkers(sc);

        List<String> configIds = request == null ? null : request.getConfigIds();
        if (configIds == null || configIds.isEmpty()) {
            configIds = request == null ? null : request.getHostNames();
        }
        ObjectMapper mapper = new ObjectMapper();
        if (configIds == null || configIds.isEmpty()) {
            clearLogbackMarkers();
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"No queue reader config IDs given\"}").build();
        }
        for (String configId : configIds) {
            try {
                QueueServerSsh.checkQueueName(configId);
            } catch (IllegalArgumentException ex) {
                clearLogbackMarkers();
                return Response.status(Response.Status.BAD_REQUEST).entity(mapper.createObjectNode().put("error", ex.getMessage())).build();
            }
        }

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Save, verb + " Queue Reader",
                "ConfigIds", String.join(",", configIds));

        Map<String, String> servers = getServersByConfigId(configIds);
        ArrayNode results = mapper.createArrayNode();
        for (String configId : configIds) {
            ObjectNode result = results.addObject();
            result.put("configId", configId);
            String userAndIp = servers.get(configId);
            if (userAndIp == null) {
                result.put("success", false);
                result.put("error", "No running instance / server found for " + configId);
                continue;
            }
            try {
                String path = TransformConfig.instance().getKillFileLocation() + "/" + configId + fileSuffix;
                QueueServerSsh.exec(QueueServerSsh.Target.parse(userAndIp), "touch '" + path.replace("'", "") + "'");
                result.put("success", true);
                result.put("message", verb + " signal sent");
            } catch (Exception ex) {
                LOG.error("Failed to " + verb.toLowerCase() + " queue reader " + configId, ex);
                result.put("success", false);
                result.put("error", ex.getMessage());
            }
        }
        clearLogbackMarkers();
        return Response.ok().entity(mapper.writeValueAsString(results)).build();
    }

    /** config ID -> user@ip of the server its latest heartbeat came from */
    private Map<String, String> getServersByConfigId(List<String> configIds) throws Exception {
        Map<String, String> hostByConfigId = new HashMap<>();
        ApplicationHeartbeatDalI dal = DalProvider.factoryApplicationHeartbeatDal();
        for (ApplicationHeartbeat h : dal.getLatest()) {
            if (h.getApplicationName().equals("queuereader") && configIds.contains(h.getApplicationInstanceName())) {
                hostByConfigId.put(h.getApplicationInstanceName(), h.getHostName());
            }
        }

        Map<String, String> userAndIpByHost = new HashMap<>();
        JsonNode json = ConfigManager.getConfigurationAsJson("server_ips");
        if (json != null) {
            for (int i = 0; i < json.size(); i++) {
                JsonNode child = json.get(i);
                userAndIpByHost.put(child.path("hostName").asText(), child.path("userAndIp").asText());
            }
        }

        Map<String, String> ret = new HashMap<>();
        for (Map.Entry<String, String> e : hostByConfigId.entrySet()) {
            String userAndIp = userAndIpByHost.get(e.getValue());
            if (userAndIp != null) {
                ret.put(e.getKey(), userAndIp);
            }
        }
        return ret;
    }

    private static String determineQueueType(String queueName) {
        //VHP: Eds*, Vhp* or unprefixed queue names
        String queueType = QueueTypes.of(queueName);
        return queueType == null ? QueueTypes.INBOUND : queueType;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.status")
    @Path("/status")
    public Response getQueueReaderStatus(@Context SecurityContext sc, @QueryParam("applicationName") String applicationName) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Get Queue Reader Status");

        String ret = getQueueReaderStatusJson(applicationName);

        clearLogbackMarkers();

        return Response
                .ok()
                .entity(ret)
                .build();
    }

    private static String getQueueReaderStatusJson(String applicationName) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode root = new ArrayNode(mapper.getNodeFactory());

        //get all the latest heartbeat records from the DB
        ApplicationHeartbeatDalI dal = DalProvider.factoryApplicationHeartbeatDal();
        List<ApplicationHeartbeat> latest = dal.getLatest();

        //for each heartbeat record, we need to work out which ones are for queue readers and what RabbitMQ
        //queue each one is servicing, so we need to check the DB for that
        for (ApplicationHeartbeat h: latest) {
            String appId = h.getApplicationName();
            String appSubId = h.getApplicationInstanceName();

            //skip any for other applications
            if (!Strings.isNullOrEmpty(applicationName)
                    && !applicationName.equalsIgnoreCase(appId)) {
                continue;
            }

            //if any records for non-queue reader apps that don't have a sub-ID, skip them
            /*if (Strings.isNullOrEmpty(appSubId)) {
                continue;
            }*/

            //if it's a queue reader, find the queue name from the XML config
            String queueName = null;
            String queueReaderConfigXml = ConfigManager.getConfiguration(appSubId, appId);
            if (!Strings.isNullOrEmpty(queueReaderConfigXml)) {
                QueueReaderConfiguration configuration = ConfigDeserialiser.deserialise(queueReaderConfigXml);
                queueName = configuration.getQueue();
            }

            String maxHeapDesc = null;
            if (h.getMaxHeapMb() != null) {
                maxHeapDesc = FileUtils.byteCountToDisplaySize(h.getMaxHeapMb() * (1024L * 1024L));
            }

            String currentHeapDesc = null;
            if (h.getCurrentHeapMb() != null) {
                currentHeapDesc = FileUtils.byteCountToDisplaySize(h.getCurrentHeapMb() * (1024L * 1024L));
            }

            String physicalMemoryDesc = null;
            if (h.getServerMemoryMb() != null) {
                physicalMemoryDesc = FileUtils.byteCountToDisplaySize(h.getServerMemoryMb() * (1024L * 1024L));
            }

            String busyDetail = h.getIsBusyDetail();

            //extract various bits of info from the busy detail String
            //string format is: <data date> data for <ODS code> since <processing start time>
            Long busySince = null;
            String busyOdsCode = null;
            String busyDataDate = null;
            String busyPublisherConfigName = null;
            if (!Strings.isNullOrEmpty(busyDetail)) {
                String sinceStr = "since";
                String dataForStr = "data for";

                int dataForIndex = busyDetail.indexOf(dataForStr);
                int sinceIndex = busyDetail.indexOf(sinceStr);

                if (dataForIndex > -1) {
                    String s = busyDetail.substring(0, dataForIndex);
                    s = s.trim();
                    if (!Strings.isNullOrEmpty(s)) {
                        busyDataDate = s;
                    }
                }

                if (dataForIndex > -1
                        && sinceIndex > -1) {
                    String s = busyDetail.substring(dataForIndex + dataForStr.length(), sinceIndex);
                    s = s.trim();
                    if (!Strings.isNullOrEmpty(s)) {
                        busyOdsCode = s;
                        busyPublisherConfigName = findPublisherConfigName(busyOdsCode);
                    }
                }

                if (sinceIndex > -1) {
                    String s = busyDetail.substring(sinceIndex + sinceStr.length());
                    s = s.trim();
                    try {
                        Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(s);
                        busySince = new Long(d.getTime());

                    } catch (Exception ex) {
                        //if not a valid date, just leave it
                    }
                }
            }

            Long dtStartedTime = null;
            if (h.getDtStarted() != null) {
                dtStartedTime = h.getDtStarted().getTime();
            }

            Long dtJarTime = null;
            if (h.getDtJar() != null) {
                dtJarTime = h.getDtJar().getTime();
            }

            ObjectNode objectNode = root.addObject();
            objectNode.put("applicationName", h.getApplicationName());
            objectNode.put("applicationInstanceName", h.getApplicationInstanceName());
            objectNode.put("applicationInstanceNumber", h.getApplicationInstanceNumber());
            objectNode.put("timestmp", h.getTimestmp().getTime());
            objectNode.put("hostName", h.getHostName());
            objectNode.put("isBusy", h.getBusy());
            objectNode.put("maxHeapMb", h.getMaxHeapMb());
            objectNode.put("maxHeapDesc", maxHeapDesc);
            objectNode.put("currentHeapMb", h.getCurrentHeapMb());
            objectNode.put("currentHeapDesc", currentHeapDesc);
            objectNode.put("physicalMemoryMb", h.getServerMemoryMb());
            objectNode.put("physicalMemoryDesc", physicalMemoryDesc);
            objectNode.put("cpuLoad", h.getServerCpuUsagePercent());
            objectNode.put("isBusyDetail", busyDetail);
            objectNode.put("isBusySince", busySince);
            objectNode.put("isBusyOdsCode", busyOdsCode);
            objectNode.put("isBusyDataDate", busyDataDate);
            objectNode.put("isBusyPublisherConfigName", busyPublisherConfigName);
            objectNode.put("dtStarted", dtStartedTime);
            objectNode.put("dtJar", dtJarTime);
            objectNode.put("queueName", queueName);
        }

        return mapper.writeValueAsString(root);
    }

    /**
     * looks up the publisher config name (e.g. publisher_01) for the given ODS code
     */
    private static String findPublisherConfigName(String odsCode) throws Exception {
        String publisherConfigName = odsCodeToPublisherCache.get(odsCode);
        if (publisherConfigName == null) {
            ServiceDalI serviceDal = DalProvider.factoryServiceDal();
            Service service = serviceDal.getByLocalIdentifier(odsCode);
            publisherConfigName = service.getPublisherConfigName();
            odsCodeToPublisherCache.put(odsCode, publisherConfigName);
        }
        return publisherConfigName;
    }

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed(absolute = true, name="QueueReaderEndpoint.QueueReaderCommand")
    @Path("/queueReaderCommand")
    @RolesAllowed(Roles.ADMIN)
    public Response queueReaderCommand(@Context SecurityContext sc, JsonQueueReaderCommand command) throws Exception {
        super.setLogbackMarkers(sc);

        userAudit.save(SecurityUtils.getCurrentUserId(sc), getOrganisationUuidFromToken(sc), AuditAction.Load, "Queue Reader Command", "Command", command.getCommand());

        validateCommand(command);
        //TODO - validate command


        Map<String, String> hmServers = getIpAddresses(command);
        for (String hostName: hmServers.keySet()) {
            String userAndIp = hmServers.get(hostName);
            executeCommand(hostName, userAndIp, command.getCommand());
        }

        //TODO - generate response

        //String ret = getQueueReaderStatusJson(applicationName);
        String ret = "Deploy all";

        clearLogbackMarkers();

        return Response
                .ok()
                .entity(ret)
                .build();
    }

    private void validateCommand(JsonQueueReaderCommand command) {

        if (command.getCommand().equals("sudo ./queueReader/deploy.sh")
                || command.getCommand().equals("sudo ./queueReader/deployScripts.sh")) {
            //valid
            return;
        }

        throw new RuntimeException("Invalid command: " + command.getCommand());
    }

    /**
     * executes the specified SSH command on the Queue Reader server
     */
    private void executeCommand(String hostName, String userAndIp, String command) {
        //VHP: uses the shared SSH helper (configured key, host key checking); the original connected without a key
        try {
            QueueServerSsh.exec(QueueServerSsh.Target.parse(userAndIp), command);
            LOG.info("Executed command on " + hostName);
        } catch (Exception ex) {
            LOG.error("Failed to execute command on " + hostName, ex);
        }
    }

    /**
     * finds the IP addresses for the servers listed in the command (or all QRs if not specified)
     */
    private Map<String, String> getIpAddresses(JsonQueueReaderCommand command) throws Exception {

        Set<String> hostNames = new HashSet<>();
        ApplicationHeartbeatDalI dal = DalProvider.factoryApplicationHeartbeatDal();
        List<ApplicationHeartbeat> latest = dal.getLatest();
        for (ApplicationHeartbeat h: latest) {

            //only count servers running Queue Reader apps
            if (!h.getApplicationName().equals("queuereader")) {
                continue;
            }

            //only count servers in the request, if specified
            String hostName = h.getHostName();
            if (command.getHostNames() != null
                    && !command.getHostNames().isEmpty()
                    && !command.getHostNames().contains(hostName)) {
                continue;
            }

            hostNames.add(hostName);
        }

        Map<String, String> ret = new HashMap<>();

        JsonNode json = ConfigManager.getConfigurationAsJson("server_ips");
        for (int i=0; i<json.size(); i++) {
            JsonNode child = json.get(i);
            String hostName = child.get("hostName").asText();
            if (hostNames.contains(hostName)) {
                String userAndIp = child.get("userAndIp").asText();
                ret.put(hostName, userAndIp);
            }
        }

        return ret;
    }


}
