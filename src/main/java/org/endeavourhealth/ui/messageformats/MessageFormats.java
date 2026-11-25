package org.endeavourhealth.ui.messageformats;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.endeavourhealth.common.config.ConfigManager;
import org.endeavourhealth.ui.json.JsonMessageFormat;
import org.endeavourhealth.ui.sftpreader.FeedConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * VHP: the message formats the platform can actually process, kept in the config DB row
 * {@code app_id = global, config_id = message_formats} (a JSON array of {@link JsonMessageFormat}).
 *
 * <p>The System Config page offers the {@code transform} formats for a System's message format, and Add / Edit Feed
 * offers (and {@code FeedValidator} enforces) the {@code sftpReader} formats for a feed's software content type.
 * Admins add formats through {@code POST /api/messageFormats}; edits and removals are done on the Config Manager page.</p>
 *
 * <p>If the row is missing, unreadable or not valid JSON, {@link #DEFAULTS} is used (and the response says so), so the
 * UI keeps working before {@code db/migrations/2026-11-25_message_formats.sql} has been run. Adding a format then writes
 * the defaults plus the new one, except when the row exists but is invalid: that is refused, so a broken hand edit is
 * not overwritten.</p>
 *
 * <p>The row is read straight from the database on every call (not through ConfigManager's cache), so an addition or a
 * Config Manager edit shows at once.</p>
 */
public final class MessageFormats {
    private static final Logger LOG = LoggerFactory.getLogger(MessageFormats.class);

    public static final String APP_ID = "global";
    public static final String CONFIG_ID = "message_formats";

    /** upper case, like the MessageFormat constants; OpenEnvelope compares EMISCSV case-sensitively */
    static final Pattern FORMAT_PATTERN = Pattern.compile("[A-Z0-9_]+");
    /** {@code configuration.software_content_type} is varchar(100) in vhp-sftpreader's schema */
    static final int MAX_FORMAT = 100;
    static final int MAX_DESCRIPTION = 200;

    /**
     * What the code supports as of 2026-11-25: {@code sftpReader} = vhp-sftpreader's {@code ImplementationActivator},
     * {@code transform} = eds-messaging-core's {@code MessageTransformInbound}. The internal formats (BULK_DELETE_DATA,
     * BULK_TRANSFORM_TO_SUBSCRIBER) and the MessageFormat constants with no inbound transform are left out.
     * Keep in step with {@code db/migrations/2026-11-25_message_formats.sql} (MessageFormatsTest checks).
     */
    public static final List<JsonMessageFormat> DEFAULTS = List.of(
            new JsonMessageFormat("EMISCSV", "EMIS Web CSV extract (version CUSTOM = EMIS custom extracts)", true, true),
            new JsonMessageFormat("TPPCSV", "TPP SystmOne CSV extract", true, true),
            new JsonMessageFormat("VISIONCSV", "Vision CSV extract", true, true),
            new JsonMessageFormat("BARTSCSV", "Barts (Cerner Millennium) CSV extract", true, true),
            new JsonMessageFormat("HOMERTONCSV", "Homerton HI CSV extract", true, true),
            new JsonMessageFormat("ADASTRACSV", "Adastra CSV extract", true, true),
            new JsonMessageFormat("BHRUTCSV", "BHRUT CSV extract", true, true),
            new JsonMessageFormat("IMPERIALCSV", "Imperial / NWL extracts CSV", true, true),
            new JsonMessageFormat("NEMSXML", "NEMS XML event messages", true, true),
            new JsonMessageFormat("EMISOPEN", "EMIS Open XML (not via SFTP Reader)", false, true),
            new JsonMessageFormat("OPENHR", "EMIS Open HR XML (not via SFTP Reader)", false, true),
            new JsonMessageFormat("TPPXML", "TPP XML (not via SFTP Reader)", false, true),
            new JsonMessageFormat("HL7V2", "HL7 v2 via the HL7 Receiver (not via SFTP Reader)", false, true),
            new JsonMessageFormat("ACUTEHL7V2", "Acute HL7 v2 (not via SFTP Reader)", false, true),
            new JsonMessageFormat("ADASTRA_XML", "Adastra XML (not via SFTP Reader)", false, true));

    /** Where the row lives; replaced in tests. */
    public interface Store {
        /** the row's config_data, or null if there is no row */
        String read() throws Exception;

        void write(String json) throws Exception;
    }

    /** The real store: the config DB through ConfigManager's data access layer (uncached). */
    static final class ConfigDbStore implements Store {
        @Override
        public String read() {
            return ConfigManager.getConfigurations(APP_ID).get(CONFIG_ID);
        }

        @Override
        public void write(String json) throws Exception {
            // the 3-argument form inserts the row if it is not there
            if (!ConfigManager.setConfiguration(CONFIG_ID, APP_ID, json)) {
                throw new Exception("Failed to save config row " + APP_ID + "/" + CONFIG_ID + " (see the log)");
            }
        }
    }

    /** The list and where it came from. */
    public static final class Snapshot {
        /** {@code config} = the row; {@code default} = {@link #DEFAULTS} (no row, or it could not be used) */
        public final String source;
        /** why the defaults are in use, or null */
        public final String warning;
        public final List<JsonMessageFormat> formats;
        /** the row exists but is not a valid list, so it must not be overwritten */
        final boolean rowInvalid;

        Snapshot(String source, String warning, List<JsonMessageFormat> formats, boolean rowInvalid) {
            this.source = source;
            this.warning = warning;
            this.formats = Collections.unmodifiableList(formats);
            this.rowInvalid = rowInvalid;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile Store store = new ConfigDbStore();

    private MessageFormats() {
    }

    /** Tests only: swap the store (null restores the config DB one). */
    public static void setStore(Store s) {
        store = s != null ? s : new ConfigDbStore();
    }

    public static Snapshot load() {
        String json;
        try {
            json = store.read();
        } catch (Throwable t) {
            // e.g. the config DB is unreachable; the built-in list is the known-good one
            LOG.warn("Could not read config row {}/{}; using the built-in message formats", APP_ID, CONFIG_ID, t);
            return new Snapshot("default", "The config DB could not be read, so the built-in list is shown", DEFAULTS, false);
        }
        if (json == null || json.isBlank()) {
            return new Snapshot("default", "There is no " + APP_ID + "/" + CONFIG_ID
                    + " config row yet, so the built-in list is shown", DEFAULTS, false);
        }
        try {
            List<JsonMessageFormat> list = MAPPER.readValue(json, new TypeReference<List<JsonMessageFormat>>() {
            });
            List<String> problems = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (JsonMessageFormat f : list) {
                String problem = f == null ? "an empty entry" : problem(f.getFormat(), f.getDescription());
                if (problem == null && seen.contains(f.getFormat())) {
                    problem = f.getFormat() + " is listed twice";
                }
                if (problem != null) {
                    problems.add(problem);
                } else {
                    seen.add(f.getFormat());
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", problems));
            }
            return new Snapshot("config", null, new ArrayList<>(list), false);
        } catch (Exception ex) {
            LOG.error("Config row {}/{} is not a valid message format list; using the built-in list", APP_ID, CONFIG_ID, ex);
            return new Snapshot("default", "The " + APP_ID + "/" + CONFIG_ID + " config row is not valid ("
                    + ex.getMessage() + "), so the built-in list is shown. Fix it on the Config Manager page.", DEFAULTS, true);
        }
    }

    /** The formats an SFTP Reader feed may use. */
    public static List<String> sftpReaderFormats() {
        return load().formats.stream().filter(JsonMessageFormat::isSftpReader).map(JsonMessageFormat::getFormat).toList();
    }

    /**
     * Adds a format (upper-cased and trimmed) and returns the new list. 400 if it is not valid, 409 if it is already
     * listed or the stored row is invalid.
     */
    public static synchronized Snapshot add(JsonMessageFormat in) throws Exception {
        if (in == null) {
            throw FeedConfigException.badRequest("No message format was sent");
        }
        String format = in.getFormat() == null ? null : in.getFormat().trim().toUpperCase(Locale.ROOT);
        String description = in.getDescription() == null ? "" : in.getDescription().trim();
        String problem = problem(format, description);
        if (problem != null) {
            throw FeedConfigException.badRequest(problem);
        }
        if (!in.isSftpReader() && !in.isTransform()) {
            throw FeedConfigException.badRequest("A format must be readable by the SFTP Reader, transformable, or both");
        }

        Snapshot current = load();
        if (current.rowInvalid) {
            throw FeedConfigException.conflict(current.warning);
        }
        if (current.formats.stream().anyMatch(f -> f.getFormat().equalsIgnoreCase(format))) {
            throw FeedConfigException.conflict("Message format " + format + " is already listed");
        }

        List<JsonMessageFormat> next = new ArrayList<>(current.formats);
        next.add(new JsonMessageFormat(format, description, in.isSftpReader(), in.isTransform()));
        store.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(next));
        LOG.info("Added message format {} (sftpReader={}, transform={})", format, in.isSftpReader(), in.isTransform());
        return new Snapshot("config", null, next, false);
    }

    private static String problem(String format, String description) {
        if (format == null || format.isEmpty()) {
            return "Format is required";
        }
        if (format.length() > MAX_FORMAT) {
            return "Format " + format + " is longer than " + MAX_FORMAT + " characters";
        }
        if (!FORMAT_PATTERN.matcher(format).matches()) {
            return "Format " + format + " may only contain upper-case letters, digits and _";
        }
        if (description != null && description.length() > MAX_DESCRIPTION) {
            return "The description of " + format + " is longer than " + MAX_DESCRIPTION + " characters";
        }
        return null;
    }
}
