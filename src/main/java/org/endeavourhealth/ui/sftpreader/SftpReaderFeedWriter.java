package org.endeavourhealth.ui.sftpreader;

import org.endeavourhealth.ui.json.JsonAddFeedRequest;
import org.endeavourhealth.ui.json.JsonInterfaceType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VHP: the SFTP Reader page's reads and writes of feed configuration (interface types and Add Feed), on a connection
 * from {@link SftpReaderDb} (auto-commit off). Requests must already have been through {@link FeedValidator} /
 * {@link InterfaceTypeValidator}.
 *
 * <p>Each write is one transaction: the checks that need the database are done first with {@code SELECT}s (so a
 * duplicate is a 409 with a clear message), then the inserts. A duplicate key that still gets through (two admins at
 * once) is MySQL error 1062 and also a 409; any other SQL error rolls back and is rethrown (a 500).</p>
 */
public final class SftpReaderFeedWriter {

    /** MySQL ER_DUP_ENTRY */
    static final int MYSQL_DUPLICATE_KEY = 1062;
    /** MySQL ER_ROW_IS_REFERENCED_2: a delete blocked by a foreign key */
    static final int MYSQL_ROW_IS_REFERENCED = 1451;
    static final String SHARING_AGREEMENT_KEY = "SharingAgreementGuid";

    /** Test hook: run after {@code instance_configuration} is inserted, so a test can force a later-stage failure. */
    static volatile Runnable afterInstanceConfigurationForTest;

    private SftpReaderFeedWriter() {
    }

    // ----------------------------------------------------------------------------------------------- reads

    /** Every interface type, ordered by id, with its file types (empty if it has none). */
    public static List<JsonInterfaceType> listInterfaceTypes(Connection connection) throws SQLException {
        String sql = "SELECT t.interface_type_id, t.interface_type_name, t.data_frequency_days, f.file_type_identifier,"
                + " (SELECT COUNT(*) FROM configuration c WHERE c.interface_type_id = t.interface_type_id)"
                + " FROM interface_type t"
                + " LEFT OUTER JOIN interface_file_type f ON f.interface_type_id = t.interface_type_id"
                + " ORDER BY t.interface_type_id, f.file_type_identifier";
        Map<Integer, JsonInterfaceType> byId = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt(1);
                JsonInterfaceType t = byId.get(id);
                if (t == null) {
                    t = new JsonInterfaceType(id, rs.getString(2), rs.getInt(3), new ArrayList<>());
                    t.setConfigurationCount(rs.getInt(5));
                    byId.put(id, t);
                }
                String fileType = rs.getString(4);
                if (fileType != null) {
                    t.getFileTypes().add(fileType);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Every row of {@code instance}, including ones with no configuration yet. */
    public static List<String> listInstanceNames(Connection connection) throws SQLException {
        List<String> ret = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT instance_name FROM instance ORDER BY instance_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ret.add(rs.getString(1));
            }
        }
        return ret;
    }

    // ----------------------------------------------------------------------------------------------- writes

    /** Adds an interface type and its file types; returns it with its id. */
    public static JsonInterfaceType addInterfaceType(Connection connection, JsonInterfaceType t) throws Exception {
        try {
            if (exists(connection, "SELECT 1 FROM interface_type WHERE interface_type_name = ?", t.getInterfaceTypeName())) {
                throw FeedConfigException.conflict("An interface type called '" + t.getInterfaceTypeName() + "' already exists");
            }
            Integer id = t.getInterfaceTypeId();
            if (id == null) {
                // FOR UPDATE locks the index range, so two concurrent adds don't both pick the same id
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COALESCE(MAX(interface_type_id), 0) + 1 FROM interface_type FOR UPDATE");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getInt(1);
                }
            } else if (exists(connection, "SELECT 1 FROM interface_type WHERE interface_type_id = ?", id)) {
                throw FeedConfigException.conflict("Interface type id " + id + " is already in use");
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO interface_type (interface_type_id, interface_type_name, data_frequency_days) VALUES (?, ?, ?)")) {
                ps.setInt(1, id);
                ps.setString(2, t.getInterfaceTypeName());
                ps.setInt(3, t.getDataFrequencyDays());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO interface_file_type (interface_type_id, file_type_identifier) VALUES (?, ?)")) {
                for (String f : t.getFileTypes()) {
                    ps.setInt(1, id);
                    ps.setString(2, f);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            return new JsonInterfaceType(id, t.getInterfaceTypeName(), t.getDataFrequencyDays(), new ArrayList<>(t.getFileTypes()));

        } catch (Exception ex) {
            connection.rollback();
            throw mapDuplicate(ex, "That interface type name or id is already in use");
        }
    }

    /**
     * Deletes an interface type and its file types; returns its name. 404 if there is no such type, 409 while any
     * configuration uses it (its batches reference the configuration, so they are covered by that check too).
     */
    public static String deleteInterfaceType(Connection connection, int interfaceTypeId) throws Exception {
        try {
            String name;
            // FOR UPDATE: a feed added for this type at the same moment waits, then fails its foreign key
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT interface_type_name FROM interface_type WHERE interface_type_id = ? FOR UPDATE")) {
                ps.setInt(1, interfaceTypeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw FeedConfigException.notFound("Interface type " + interfaceTypeId + " does not exist");
                    }
                    name = rs.getString(1);
                }
            }
            List<String> users = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT configuration_id FROM configuration WHERE interface_type_id = ? ORDER BY configuration_id")) {
                ps.setInt(1, interfaceTypeId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        users.add(rs.getString(1));
                    }
                }
            }
            if (!users.isEmpty()) {
                String list = users.size() <= 5 ? String.join(", ", users)
                        : String.join(", ", users.subList(0, 5)) + " and " + (users.size() - 5) + " more";
                throw FeedConfigException.conflict("Interface type '" + name + "' is used by " + users.size()
                        + (users.size() == 1 ? " feed" : " feeds") + " (" + list + "), so it can't be deleted");
            }
            update(connection, "DELETE FROM interface_file_type WHERE interface_type_id = ?", interfaceTypeId);
            update(connection, "DELETE FROM interface_type WHERE interface_type_id = ?", interfaceTypeId);
            connection.commit();
            return name;

        } catch (Exception ex) {
            connection.rollback();
            throw mapInUse(ex, "Interface type " + interfaceTypeId + " is in use, so it can't be deleted");
        }
    }

    /**
     * Adds a feed: {@code instance} (only if {@code createInstance}), {@code configuration}, {@code instance_configuration},
     * {@code configuration_sftp}, {@code configuration_pgp} and, if given, {@code configuration_kvp}, in that order.
     */
    public static void addFeed(Connection connection, JsonAddFeedRequest r) throws Exception {
        try {
            String configurationId = r.getConfigurationId();
            if (exists(connection, "SELECT 1 FROM configuration WHERE configuration_id = ?", configurationId)) {
                throw FeedConfigException.conflict("Configuration id '" + configurationId + "' already exists");
            }
            if (exists(connection, "SELECT 1 FROM configuration WHERE configuration_friendly_name = ?", r.getFriendlyName())) {
                throw FeedConfigException.conflict("Another configuration is already called '" + r.getFriendlyName() + "'");
            }
            checkInterfaceTypeUsable(connection, r.getInterfaceTypeId());
            boolean instanceExists = exists(connection, "SELECT 1 FROM instance WHERE instance_name = ?", r.getInstanceName());
            if (r.getCreateInstance() && instanceExists) {
                throw FeedConfigException.conflict("Instance '" + r.getInstanceName() + "' already exists; untick 'New instance' to add the feed to it");
            }
            if (!r.getCreateInstance() && !instanceExists) {
                throw FeedConfigException.badRequest("There is no instance called '" + r.getInstanceName() + "'");
            }

            if (r.getCreateInstance()) {
                update(connection, "INSERT INTO instance (instance_name) VALUES (?)", r.getInstanceName());
            }
            update(connection, "INSERT INTO configuration (configuration_id, interface_type_id, poll_frequency_seconds,"
                            + " local_root_path, configuration_friendly_name, software_content_type, software_version)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    configurationId, r.getInterfaceTypeId(), r.getPollFrequencySeconds(), r.getLocalRootPath(),
                    r.getFriendlyName(), r.getSoftwareContentType(), r.getSoftwareVersion());
            update(connection, "INSERT INTO instance_configuration (instance_name, configuration_id) VALUES (?, ?)",
                    r.getInstanceName(), configurationId);

            Runnable hook = afterInstanceConfigurationForTest;
            if (hook != null) {
                hook.run();
            }

            update(connection, "INSERT INTO configuration_sftp (configuration_id, hostname, port, remote_path, username,"
                            + " client_public_key, client_private_key, client_private_key_password, host_public_key)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    configurationId, r.getHostname(), r.getPort(), r.getRemotePath(), r.getUsername(),
                    r.getClientPublicKey(), r.getClientPrivateKey(), r.getClientPrivateKeyPassword(), r.getHostPublicKey());
            update(connection, "INSERT INTO configuration_pgp (configuration_id, file_extension_filter, sender_public_key,"
                            + " recipient_public_key, recipient_private_key, recipient_private_key_password)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    configurationId, r.getFileExtensionFilter(), r.getSenderPublicKey(), r.getRecipientPublicKey(),
                    r.getRecipientPrivateKey(), r.getRecipientPrivateKeyPassword());
            if (r.getSharingAgreementGuid() != null) {
                update(connection, "INSERT INTO configuration_kvp (configuration_id, `key`, `value`) VALUES (?, ?, ?)",
                        configurationId, SHARING_AGREEMENT_KEY, r.getSharingAgreementGuid());
            }
            connection.commit();

        } catch (Exception ex) {
            connection.rollback();
            throw mapDuplicate(ex, "That configuration id or friendly name is already in use");
        }
    }

    // ----------------------------------------------------------------------------------------------- helpers

    /** 400 unless the interface type exists and has at least one file type. */
    static void checkInterfaceTypeUsable(Connection connection, int interfaceTypeId) throws Exception {
        int fileTypes = count(connection, "SELECT COUNT(f.file_type_identifier) FROM interface_type t"
                + " LEFT OUTER JOIN interface_file_type f ON f.interface_type_id = t.interface_type_id"
                + " WHERE t.interface_type_id = ? GROUP BY t.interface_type_id", interfaceTypeId);
        if (fileTypes < 0) {
            throw FeedConfigException.badRequest("Interface type " + interfaceTypeId + " does not exist");
        }
        if (fileTypes == 0) {
            throw FeedConfigException.badRequest("Interface type " + interfaceTypeId
                    + " has no file types, so the SFTP Reader could not validate its files");
        }
    }

    /** MySQL 1062 (duplicate key) is a 409; everything else (including foreign key failures) is passed on. */
    static Exception mapDuplicate(Exception ex, String message) {
        if (ex instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_KEY) {
            return FeedConfigException.conflict(message);
        }
        return ex;
    }

    /** MySQL 1451 (a row still references this one) is a 409; everything else is passed on. */
    static Exception mapInUse(Exception ex, String message) {
        if (ex instanceof SQLException sql && sql.getErrorCode() == MYSQL_ROW_IS_REFERENCED) {
            return FeedConfigException.conflict(message);
        }
        return ex;
    }

    private static boolean exists(Connection c, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** the single count the query returns, or -1 if it returns no row */
    private static int count(Connection c, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static void update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
