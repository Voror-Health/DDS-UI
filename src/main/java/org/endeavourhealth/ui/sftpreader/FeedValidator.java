package org.endeavourhealth.ui.sftpreader;

import org.endeavourhealth.ui.json.JsonAddFeedRequest;
import org.endeavourhealth.ui.messageformats.MessageFormats;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * VHP: checks the body of {@code POST /api/sftpReader/addFeed} before the database is touched. Checks that need the
 * database (duplicates, the interface type, the instance) are in {@link SftpReaderFeedWriter}.
 *
 * <p>Lengths are from vhp-sftpreader's {@code schema.sql}. {@code configuration.configuration_id} is varchar(255) but
 * every child table has varchar(100), and the reader's lock name caps it lower still, at 53 ({@link #MAX_CONFIGURATION_ID}).</p>
 */
public final class FeedValidator {

    /**
     * Instance names become the reader webapp's name ({@code vhp-sftpreader#<instance>}) and configuration ids are
     * used in file paths, so both are kept to safe characters.
     */
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.\\-]+");

    static final int MAX_SHORT = 100;
    /**
     * The reader locks each configuration with {@code GET_LOCK('SftpReader-' + configurationId)}, and MySQL lock names
     * are at most 64 characters, so a longer id would make every polling run fail. 64 - "SftpReader-".length() = 53.
     */
    static final int MAX_CONFIGURATION_ID = 64 - "SftpReader-".length();
    static final int MAX_LONG = 1000;
    /** the key columns are mediumtext (16 MB); this keeps a pasted file from filling the request */
    static final int MAX_KEY = 65536;

    private FeedValidator() {
    }

    /** Trims the fields that are trimmed (not passwords) and checks everything, or throws a 400. */
    public static JsonAddFeedRequest validate(JsonAddFeedRequest r) throws FeedConfigException {
        return validate(r, false);
    }

    /**
     * For an edit: the same checks, except that the private keys and passwords may be left out (null or empty means
     * "keep the stored value"; they come back as null) and {@code createInstance} is ignored. The configuration id is
     * taken from the URL, not the body.
     */
    public static JsonAddFeedRequest validateEdit(JsonAddFeedRequest r, String configurationId) throws FeedConfigException {
        if (r == null) {
            throw FeedConfigException.badRequest("No feed was sent");
        }
        r.setConfigurationId(configurationId);
        r.setCreateInstance(Boolean.FALSE);
        return validate(r, true);
    }

    private static JsonAddFeedRequest validate(JsonAddFeedRequest r, boolean edit) throws FeedConfigException {
        if (r == null) {
            throw FeedConfigException.badRequest("No feed was sent");
        }

        r.setInstanceName(safeId("Instance name", r.getInstanceName(), MAX_SHORT));
        if (!edit && SftpReaderFeedEditor.RETIRED_INSTANCE.equalsIgnoreCase(r.getInstanceName())) {
            throw FeedConfigException.badRequest("'" + SftpReaderFeedEditor.RETIRED_INSTANCE
                    + "' is where retired feeds are kept; choose the instance that should run this feed");
        }
        r.setConfigurationId(safeId("Configuration id", r.getConfigurationId(), MAX_CONFIGURATION_ID));
        r.setFriendlyName(required("Friendly name", r.getFriendlyName(), MAX_SHORT));
        r.setLocalRootPath(required("Local root path", r.getLocalRootPath(), MAX_LONG));

        // VHP 2026-11-25: the content types vhp-sftpreader's ImplementationActivator accepts (anything else stops the
        // reader at start-up) come from the message format list (config row global/message_formats, sftpReader = true)
        String contentType = required("Software content type", r.getSoftwareContentType(), MAX_SHORT).toUpperCase(Locale.ROOT);
        List<String> allowed = MessageFormats.sftpReaderFormats();
        if (!allowed.contains(contentType)) {
            throw FeedConfigException.badRequest("Software content type must be one of " + String.join(", ", allowed));
        }
        r.setSoftwareContentType(contentType);
        r.setSoftwareVersion(required("Software version", r.getSoftwareVersion(), MAX_SHORT));

        if (r.getInterfaceTypeId() == null || r.getInterfaceTypeId() < 1) {
            throw FeedConfigException.badRequest("Interface type is required");
        }
        if (r.getPollFrequencySeconds() == null || r.getPollFrequencySeconds() < 1) {
            throw FeedConfigException.badRequest("Poll frequency (seconds) must be a whole number of 1 or more");
        }
        if (r.getCreateInstance() == null) {
            r.setCreateInstance(Boolean.FALSE);
        }

        // SFTP
        r.setHostname(required("SFTP hostname", r.getHostname(), MAX_SHORT));
        if (r.getPort() == null || r.getPort() < 1 || r.getPort() > 65535) {
            throw FeedConfigException.badRequest("SFTP port must be between 1 and 65535");
        }
        r.setRemotePath(required("SFTP remote path", r.getRemotePath(), MAX_LONG));
        r.setUsername(required("SFTP username", r.getUsername(), MAX_SHORT));
        // optional: vhp-sftpreader never reads it (it authenticates with the private key). The column is NOT NULL, so
        // a blank key is stored as ''.
        String clientPublicKey = optional("Client public key", r.getClientPublicKey(), MAX_KEY);
        r.setClientPublicKey(clientPublicKey == null ? "" : clientPublicKey);
        r.setClientPrivateKey(edit ? optional("Client private key", r.getClientPrivateKey(), MAX_KEY)
                : required("Client private key", r.getClientPrivateKey(), MAX_KEY));
        r.setClientPrivateKeyPassword(secret("Client private key password", r.getClientPrivateKeyPassword(), MAX_LONG, edit));
        r.setHostPublicKey(required("Host public key", r.getHostPublicKey(), MAX_KEY));

        // PGP
        r.setFileExtensionFilter(required("File extension filter", r.getFileExtensionFilter(), MAX_SHORT));
        r.setSenderPublicKey(required("Sender public key", r.getSenderPublicKey(), MAX_KEY));
        r.setRecipientPublicKey(required("Recipient public key", r.getRecipientPublicKey(), MAX_KEY));
        r.setRecipientPrivateKey(edit ? optional("Recipient private key", r.getRecipientPrivateKey(), MAX_KEY)
                : required("Recipient private key", r.getRecipientPrivateKey(), MAX_KEY));
        r.setRecipientPrivateKeyPassword(secret("Recipient private key password", r.getRecipientPrivateKeyPassword(), MAX_LONG, edit));

        // optional kvp
        String guid = trimToNull(r.getSharingAgreementGuid());
        if (guid != null) {
            maxLength("Sharing agreement GUID", guid, MAX_LONG);
        }
        r.setSharingAgreementGuid(guid);

        return r;
    }

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static void maxLength(String label, String value, int max) throws FeedConfigException {
        if (value.length() > max) {
            throw FeedConfigException.badRequest(label + " must be at most " + max + " characters");
        }
    }

    private static String required(String label, String value, int max) throws FeedConfigException {
        String t = trimToNull(value);
        if (t == null) {
            throw FeedConfigException.badRequest(label + " is required");
        }
        maxLength(label, t, max);
        return t;
    }

    /** trimmed, or null when blank (an edit's "keep the stored key") */
    private static String optional(String label, String value, int max) throws FeedConfigException {
        String t = trimToNull(value);
        if (t != null) {
            maxLength(label, t, max);
        }
        return t;
    }

    /** passwords are not trimmed: spaces may be part of them. Empty is an error, or "keep" (null) in an edit. */
    private static String secret(String label, String value, int max, boolean optional) throws FeedConfigException {
        if (value == null || value.isEmpty()) {
            if (optional) {
                return null;
            }
            throw FeedConfigException.badRequest(label + " is required");
        }
        maxLength(label, value, max);
        return value;
    }

    private static String safeId(String label, String value, int max) throws FeedConfigException {
        String t = required(label, value, max);
        if (!SAFE_ID.matcher(t).matches()) {
            throw FeedConfigException.badRequest(label + " may only contain letters, digits, '.', '_' and '-'");
        }
        return t;
    }
}
