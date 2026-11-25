package org.endeavourhealth.ui.sftpreader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.endeavourhealth.ui.json.JsonAddFeedRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * VHP: edit, retire, reinstate and delete an existing SFTP Reader feed (see {@code docs/dds-ui-sftp-feed-edit-delete.md}).
 *
 * <p>Every write runs in one transaction on a {@link SftpReaderDb} connection and, first, takes the advisory lock the
 * reader takes for each polling run ({@code GET_LOCK('SftpReader-<configurationId>')}) with a 0-second wait, so a feed
 * is never changed while it is being polled (409 if it is). The reader only reads its configurations at start-up, so
 * the results say which reader instance(s) must be restarted.</p>
 *
 * <p>Private keys and passwords are never returned: {@link #describe} gives {@code ...Set} flags instead, and an edit
 * that leaves one out keeps the stored value.</p>
 */
public final class SftpReaderFeedEditor {

    /** The instance the original moved feeds to when they were no longer used; no reader runs it. */
    public static final String RETIRED_INSTANCE = "NOT_USED";
    static final String LOCK_PREFIX = "SftpReader-";

    private SftpReaderFeedEditor() {
    }

    /** Everything stored for a feed, secrets included (never sent to the browser as is). */
    record StoredFeed(String configurationId, int interfaceTypeId, int pollFrequencySeconds, String localRootPath,
                      String friendlyName, String softwareContentType, String softwareVersion, String instanceName,
                      String hostname, Integer port, String remotePath, String username, String clientPublicKey,
                      String clientPrivateKey, String clientPrivateKeyPassword, String hostPublicKey,
                      String fileExtensionFilter, String senderPublicKey, String recipientPublicKey,
                      String recipientPrivateKey, String recipientPrivateKeyPassword, String sharingAgreementGuid,
                      boolean paused, boolean hasBatches) {

        boolean retired() {
            return RETIRED_INSTANCE.equals(instanceName);
        }

        /**
         * A hash of every stored value an edit can change (secrets included, so a concurrent key change is seen too;
         * nothing can be read back from it). Not the paused flag or batches, which change without an edit.
         */
        String version() {
            Object[] values = {configurationId, interfaceTypeId, pollFrequencySeconds, localRootPath, friendlyName,
                    softwareContentType, softwareVersion, instanceName, hostname, port, remotePath, username,
                    clientPublicKey, clientPrivateKey, clientPrivateKeyPassword, hostPublicKey, fileExtensionFilter,
                    senderPublicKey, recipientPublicKey, recipientPrivateKey, recipientPrivateKeyPassword,
                    sharingAgreementGuid};
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (Object v : values) {
                    // length-prefixed, with null distinct from "", so values can't run into each other
                    String s = v == null ? "\u0000null" : String.valueOf(v);
                    md.update((s.length() + ":" + s + ";").getBytes(StandardCharsets.UTF_8));
                }
                return HexFormat.of().formatHex(md.digest());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /** What {@code GET /feeds/{id}} returns: the feed without its private keys and passwords. */
    static ObjectNode describe(StoredFeed f) {
        ObjectNode o = new ObjectMapper().createObjectNode();
        o.put("configurationId", f.configurationId());
        o.put("instanceName", f.instanceName());
        o.put("retired", f.retired());
        o.put("paused", f.paused());
        o.put("hasBatches", f.hasBatches());
        o.put("interfaceTypeId", f.interfaceTypeId());
        o.put("pollFrequencySeconds", f.pollFrequencySeconds());
        o.put("localRootPath", f.localRootPath());
        o.put("friendlyName", f.friendlyName());
        o.put("softwareContentType", f.softwareContentType());
        o.put("softwareVersion", f.softwareVersion());
        o.put("hostname", f.hostname());
        if (f.port() != null) {
            o.put("port", f.port());
        }
        o.put("remotePath", f.remotePath());
        o.put("username", f.username());
        o.put("clientPublicKey", f.clientPublicKey());
        o.put("clientPrivateKeySet", f.clientPrivateKey() != null);
        o.put("clientPrivateKeyPasswordSet", f.clientPrivateKeyPassword() != null);
        o.put("hostPublicKey", f.hostPublicKey());
        o.put("fileExtensionFilter", f.fileExtensionFilter());
        o.put("senderPublicKey", f.senderPublicKey());
        o.put("recipientPublicKey", f.recipientPublicKey());
        o.put("recipientPrivateKeySet", f.recipientPrivateKey() != null);
        o.put("recipientPrivateKeyPasswordSet", f.recipientPrivateKeyPassword() != null);
        o.put("sharingAgreementGuid", f.sharingAgreementGuid());
        o.put("version", f.version());
        return o;
    }

    // ----------------------------------------------------------------------------------------------- read

    /** The feed, or a 404. {@code forUpdate} locks its rows for the rest of the transaction. */
    static StoredFeed load(Connection c, String configurationId, boolean forUpdate) throws Exception {
        String sql = "SELECT c.interface_type_id, c.poll_frequency_seconds, c.local_root_path, c.configuration_friendly_name,"
                + " c.software_content_type, c.software_version, ic.instance_name,"
                + " s.hostname, s.port, s.remote_path, s.username, s.client_public_key, s.client_private_key,"
                + " s.client_private_key_password, s.host_public_key,"
                + " p.file_extension_filter, p.sender_public_key, p.recipient_public_key, p.recipient_private_key,"
                + " p.recipient_private_key_password, k.`value`"
                + " FROM configuration c"
                + " LEFT OUTER JOIN instance_configuration ic ON ic.configuration_id = c.configuration_id"
                + " LEFT OUTER JOIN configuration_sftp s ON s.configuration_id = c.configuration_id"
                + " LEFT OUTER JOIN configuration_pgp p ON p.configuration_id = c.configuration_id"
                + " LEFT OUTER JOIN configuration_kvp k ON k.configuration_id = c.configuration_id AND k.`key` = ?"
                + " WHERE c.configuration_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, SftpReaderFeedWriter.SHARING_AGREEMENT_KEY);
            ps.setString(2, configurationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw FeedConfigException.notFound("There is no feed with configuration id '" + configurationId + "'");
                }
                int port = rs.getInt(9);
                Integer portOrNull = rs.wasNull() ? null : port;
                return new StoredFeed(configurationId, rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), portOrNull, rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getString(14), rs.getString(15),
                        rs.getString(16), rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20),
                        rs.getString(21),
                        exists(c, "SELECT 1 FROM configuration_paused_notifying WHERE configuration_id = ?", configurationId),
                        exists(c, "SELECT 1 FROM batch WHERE configuration_id = ? LIMIT 1", configurationId));
            }
        }
    }

    public static ObjectNode getFeed(Connection c, String configurationId) throws Exception {
        return describe(load(c, configurationId, false));
    }

    // ----------------------------------------------------------------------------------------------- edit

    /** What an edit changed, and which reader instances must be restarted for it to take effect. */
    public record EditResult(List<String> changedFields, List<String> restartInstances) {
    }

    /**
     * Applies an edit that has been through {@link FeedValidator#validateEdit}. Null private keys and passwords keep
     * the stored values. 409 if the feed changed since {@code version} was read, if it is being polled, or if the
     * interface type or local root path is changed after batches were received.
     */
    public static EditResult updateFeed(Connection c, JsonAddFeedRequest r, String version) throws Exception {
        String id = r.getConfigurationId();
        lock(c, id);
        try {
            StoredFeed s = load(c, id, true);
            if (version == null || !version.equals(s.version())) {
                throw FeedConfigException.conflict("Feed '" + id + "' has been changed since it was opened; reload it and try again");
            }

            // stored secrets are kept unless a new value was sent
            String clientPrivateKey = keep(r.getClientPrivateKey(), s.clientPrivateKey(), "Client private key");
            String clientPassword = keep(r.getClientPrivateKeyPassword(), s.clientPrivateKeyPassword(), "Client private key password");
            String recipientPrivateKey = keep(r.getRecipientPrivateKey(), s.recipientPrivateKey(), "Recipient private key");
            String recipientPassword = keep(r.getRecipientPrivateKeyPassword(), s.recipientPrivateKeyPassword(), "Recipient private key password");

            List<String> changed = new ArrayList<>();
            boolean config = diff(changed, "interfaceTypeId", s.interfaceTypeId(), r.getInterfaceTypeId())
                    | diff(changed, "pollFrequencySeconds", s.pollFrequencySeconds(), r.getPollFrequencySeconds())
                    | diff(changed, "localRootPath", s.localRootPath(), r.getLocalRootPath())
                    | diff(changed, "friendlyName", s.friendlyName(), r.getFriendlyName())
                    | diff(changed, "softwareContentType", s.softwareContentType(), r.getSoftwareContentType())
                    | diff(changed, "softwareVersion", s.softwareVersion(), r.getSoftwareVersion());
            boolean sftp = diff(changed, "hostname", s.hostname(), r.getHostname())
                    | diff(changed, "port", s.port(), r.getPort())
                    | diff(changed, "remotePath", s.remotePath(), r.getRemotePath())
                    | diff(changed, "username", s.username(), r.getUsername())
                    | diff(changed, "clientPublicKey", s.clientPublicKey(), r.getClientPublicKey())
                    | diff(changed, "clientPrivateKey", s.clientPrivateKey(), clientPrivateKey)
                    | diff(changed, "clientPrivateKeyPassword", s.clientPrivateKeyPassword(), clientPassword)
                    | diff(changed, "hostPublicKey", s.hostPublicKey(), r.getHostPublicKey());
            boolean pgp = diff(changed, "fileExtensionFilter", s.fileExtensionFilter(), r.getFileExtensionFilter())
                    | diff(changed, "senderPublicKey", s.senderPublicKey(), r.getSenderPublicKey())
                    | diff(changed, "recipientPublicKey", s.recipientPublicKey(), r.getRecipientPublicKey())
                    | diff(changed, "recipientPrivateKey", s.recipientPrivateKey(), recipientPrivateKey)
                    | diff(changed, "recipientPrivateKeyPassword", s.recipientPrivateKeyPassword(), recipientPassword);
            boolean kvp = diff(changed, "sharingAgreementGuid", s.sharingAgreementGuid(), r.getSharingAgreementGuid());
            boolean move = diff(changed, "instanceName", s.instanceName(), r.getInstanceName());

            if (changed.isEmpty()) {
                c.commit();
                return new EditResult(changed, List.of());
            }

            // checks that need the database
            if (s.hasBatches() && changed.contains("interfaceTypeId")) {
                throw FeedConfigException.conflict("The interface type can't be changed: this feed has received batches,"
                        + " and they are recorded against its current interface type");
            }
            if (s.hasBatches() && changed.contains("localRootPath")) {
                throw FeedConfigException.conflict("The local root path can't be changed: this feed has received batches,"
                        + " and their stored files are found through the current path");
            }
            if (changed.contains("friendlyName") && exists(c,
                    "SELECT 1 FROM configuration WHERE configuration_friendly_name = ? AND configuration_id <> ?",
                    r.getFriendlyName(), id)) {
                throw FeedConfigException.conflict("Another configuration is already called '" + r.getFriendlyName() + "'");
            }
            if (changed.contains("interfaceTypeId")) {
                SftpReaderFeedWriter.checkInterfaceTypeUsable(c, r.getInterfaceTypeId());
            }
            if (move) {
                if (s.retired()) {
                    throw FeedConfigException.badRequest("This feed is retired; use Reinstate to give it an instance again");
                }
                if (RETIRED_INSTANCE.equals(r.getInstanceName())) {
                    throw FeedConfigException.badRequest("Use Retire to stop using a feed, not the instance field");
                }
                if (!exists(c, "SELECT 1 FROM instance WHERE instance_name = ?", r.getInstanceName())) {
                    throw FeedConfigException.badRequest("There is no instance called '" + r.getInstanceName() + "'");
                }
            }

            if (config) {
                update(c, "UPDATE configuration SET interface_type_id = ?, poll_frequency_seconds = ?, local_root_path = ?,"
                                + " configuration_friendly_name = ?, software_content_type = ?, software_version = ?"
                                + " WHERE configuration_id = ?",
                        r.getInterfaceTypeId(), r.getPollFrequencySeconds(), r.getLocalRootPath(), r.getFriendlyName(),
                        r.getSoftwareContentType(), r.getSoftwareVersion(), id);
            }
            if (sftp) {
                update(c, "INSERT INTO configuration_sftp (configuration_id, hostname, port, remote_path, username,"
                                + " client_public_key, client_private_key, client_private_key_password, host_public_key)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE hostname = VALUES(hostname), port = VALUES(port),"
                                + " remote_path = VALUES(remote_path), username = VALUES(username),"
                                + " client_public_key = VALUES(client_public_key), client_private_key = VALUES(client_private_key),"
                                + " client_private_key_password = VALUES(client_private_key_password),"
                                + " host_public_key = VALUES(host_public_key)",
                        id, r.getHostname(), r.getPort(), r.getRemotePath(), r.getUsername(), r.getClientPublicKey(),
                        clientPrivateKey, clientPassword, r.getHostPublicKey());
            }
            if (pgp) {
                update(c, "INSERT INTO configuration_pgp (configuration_id, file_extension_filter, sender_public_key,"
                                + " recipient_public_key, recipient_private_key, recipient_private_key_password)"
                                + " VALUES (?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE file_extension_filter = VALUES(file_extension_filter),"
                                + " sender_public_key = VALUES(sender_public_key), recipient_public_key = VALUES(recipient_public_key),"
                                + " recipient_private_key = VALUES(recipient_private_key),"
                                + " recipient_private_key_password = VALUES(recipient_private_key_password)",
                        id, r.getFileExtensionFilter(), r.getSenderPublicKey(), r.getRecipientPublicKey(),
                        recipientPrivateKey, recipientPassword);
            }
            if (kvp) {
                if (r.getSharingAgreementGuid() == null) {
                    update(c, "DELETE FROM configuration_kvp WHERE configuration_id = ? AND `key` = ?",
                            id, SftpReaderFeedWriter.SHARING_AGREEMENT_KEY);
                } else {
                    update(c, "INSERT INTO configuration_kvp (configuration_id, `key`, `value`) VALUES (?, ?, ?)"
                                    + " ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)",
                            id, SftpReaderFeedWriter.SHARING_AGREEMENT_KEY, r.getSharingAgreementGuid());
                }
            }
            if (move) {
                setInstance(c, s, r.getInstanceName());
            }
            c.commit();

            List<String> restart = new ArrayList<>();
            if (s.instanceName() != null && !s.retired()) {
                restart.add(s.instanceName());
            }
            if (move && !restart.contains(r.getInstanceName())) {
                restart.add(r.getInstanceName());
            }
            return new EditResult(changed, restart);

        } catch (Exception ex) {
            c.rollback();
            throw SftpReaderFeedWriter.mapDuplicate(ex, "Another configuration already uses that friendly name");
        } finally {
            unlock(c, id);
        }
    }

    // ----------------------------------------------------------------------------------------------- retire / reinstate

    /** Moves the feed to {@value #RETIRED_INSTANCE} and pauses notifying; returns the instance it was on (may be null). */
    public static String retireFeed(Connection c, String configurationId) throws Exception {
        lock(c, configurationId);
        try {
            StoredFeed s = load(c, configurationId, true);
            if (s.retired()) {
                throw FeedConfigException.conflict("Feed '" + configurationId + "' is already retired");
            }
            update(c, "INSERT IGNORE INTO instance (instance_name) VALUES (?)", RETIRED_INSTANCE);
            setInstance(c, s, RETIRED_INSTANCE);
            // read by the reader on every run, so nothing more is posted even before its instance is restarted
            update(c, "INSERT IGNORE INTO configuration_paused_notifying (configuration_id) VALUES (?)", configurationId);
            c.commit();
            return s.instanceName();
        } catch (Exception ex) {
            c.rollback();
            throw ex;
        } finally {
            unlock(c, configurationId);
        }
    }

    /** Moves a retired feed to an existing instance. Leaves it paused. */
    public static void reinstateFeed(Connection c, String configurationId, String instanceName) throws Exception {
        String instance = FeedValidator.trimToNull(instanceName);
        if (instance == null || RETIRED_INSTANCE.equals(instance)) {
            throw FeedConfigException.badRequest("Choose the instance to reinstate the feed to");
        }
        lock(c, configurationId);
        try {
            StoredFeed s = load(c, configurationId, true);
            if (!s.retired()) {
                throw FeedConfigException.conflict("Feed '" + configurationId + "' is not retired");
            }
            if (!exists(c, "SELECT 1 FROM instance WHERE instance_name = ?", instance)) {
                throw FeedConfigException.badRequest("There is no instance called '" + instance + "'");
            }
            setInstance(c, s, instance);
            c.commit();
        } catch (Exception ex) {
            c.rollback();
            throw ex;
        } finally {
            unlock(c, configurationId);
        }
    }

    // ----------------------------------------------------------------------------------------------- delete

    /**
     * Deletes a feed that has never received a batch, children first; returns the rows deleted per table.
     * 409 if it has batches (retire it instead). The {@code instance} row and any stored files are left alone.
     */
    public static Map<String, Integer> deleteFeed(Connection c, String configurationId) throws Exception {
        lock(c, configurationId);
        try {
            StoredFeed s = load(c, configurationId, true);
            if (s.hasBatches()) {
                throw FeedConfigException.conflict("Feed '" + configurationId + "' has received batches, so its history"
                        + " would be lost; retire it instead");
            }
            Map<String, Integer> deleted = new LinkedHashMap<>();
            for (String table : DELETE_ORDER) {
                // table names come from the constant list, never from the request
                deleted.put(table, update(c, "DELETE FROM " + table + " WHERE configuration_id = ?", configurationId));
            }
            c.commit();
            return deleted;
        } catch (Exception ex) {
            c.rollback();
            throw SftpReaderFeedWriter.mapInUse(ex, "Feed '" + configurationId + "' is still referenced (it may have"
                    + " received a batch just now); retire it instead");
        } finally {
            unlock(c, configurationId);
        }
    }

    /** Children before parents ({@code batch} and its children are never deleted: a feed with batches is refused). */
    static final List<String> DELETE_ORDER = List.of(
            "configuration_polling_attempt", "unknown_file", "adastra_organisation_map", "configuration_paused_notifying",
            "configuration_kvp", "configuration_pgp", "configuration_sftp", "instance_configuration", "configuration");

    // ----------------------------------------------------------------------------------------------- helpers

    /** The reader's per-configuration advisory lock, without waiting; 409 if a polling run holds it. */
    static void lock(Connection c, String configurationId) throws Exception {
        if (configurationId == null || configurationId.isBlank()
                || configurationId.length() > FeedValidator.MAX_CONFIGURATION_ID) {
            // MySQL refuses lock names over 64 characters, and the reader could never have polled such a feed
            throw FeedConfigException.notFound("There is no feed with configuration id '" + configurationId + "'");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            ps.setString(1, LOCK_PREFIX + configurationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Object result = rs.getObject(1);
                checkLockResult(result == null ? null : ((Number) result).intValue(), configurationId);
            }
        }
    }

    /** {@code GET_LOCK} answers 1 when it got the lock, 0 when another session holds it, NULL on an error. */
    static void checkLockResult(Integer result, String configurationId) throws FeedConfigException {
        if (result == null || result != 1) {
            throw FeedConfigException.conflict("The SFTP Reader is polling feed '" + configurationId
                    + "' right now; try again in a minute");
        }
    }

    static void unlock(Connection c, String configurationId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, LOCK_PREFIX + configurationId);
            ps.executeQuery().close();
        } catch (SQLException ex) {
            // the lock is released anyway when the connection closes; a pooled one is closed on error by Hikari
        }
    }

    private static void setInstance(Connection c, StoredFeed s, String instanceName) throws SQLException {
        if (s.instanceName() == null) {
            update(c, "INSERT INTO instance_configuration (instance_name, configuration_id) VALUES (?, ?)",
                    instanceName, s.configurationId());
        } else {
            update(c, "UPDATE instance_configuration SET instance_name = ? WHERE configuration_id = ?",
                    instanceName, s.configurationId());
        }
    }

    /** the new secret, or the stored one when none was sent; 400 if neither exists */
    private static String keep(String sent, String stored, String label) throws FeedConfigException {
        if (sent != null) {
            return sent;
        }
        if (stored == null) {
            throw FeedConfigException.badRequest(label + " is required (none is stored for this feed)");
        }
        return stored;
    }

    private static boolean diff(List<String> changed, String field, Object stored, Object sent) {
        if (!Objects.equals(stored, sent)) {
            changed.add(field);
            return true;
        }
        return false;
    }

    private static boolean exists(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
