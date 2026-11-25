package org.endeavourhealth.ui.sftpreader;

import org.endeavourhealth.ui.json.JsonAddFeedRequest;
import org.endeavourhealth.ui.json.JsonInterfaceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in: the SFTP Reader page's interface type and Add Feed writes against a real MySQL. It (re)creates a database
 * called {@value #DB} from vhp-sftpreader's {@code schema.sql} on every test, so <b>only point it at a throwaway
 * server</b>. Run with
 * {@code "-Ddds.test.sftpreader.url=jdbc:mysql://127.0.0.1:33084/?useSSL=false&allowPublicKeyRetrieval=true"
 * -Ddds.test.sftpreader.username=root -Ddds.test.sftpreader.password=}.
 *
 * <p>It calls {@link SftpReaderFeedWriter} on a plain connection (auto-commit off, as {@link SftpReaderDb} gives),
 * after the validators, which is what the endpoint does; the endpoint's HTTP side is covered by the smoke test.</p>
 */
@EnabledIfSystemProperty(named = "dds.test.sftpreader.url", matches = ".+")
class SftpReaderFeedMySqlIntegrationTest {

    static final String DB = "dds_ui_test_sftpreader";
    private static final Path SCHEMA = Path.of("../vhp-sftpreader/db/sftpreader-database/src/main/mysql/schema.sql");

    private Connection connection;

    private static Connection connect(String database) throws Exception {
        String url = System.getProperty("dds.test.sftpreader.url");
        if (database != null) {
            int q = url.indexOf('?');
            String base = q == -1 ? url : url.substring(0, q);
            String params = q == -1 ? "" : url.substring(q);
            url = (base.endsWith("/") ? base : base + "/") + database + params;
        }
        return DriverManager.getConnection(url, System.getProperty("dds.test.sftpreader.username", "root"),
                System.getProperty("dds.test.sftpreader.password", ""));
    }

    @BeforeEach
    void createSchema() throws Exception {
        try (Connection c = connect(null); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB);
            s.execute("CREATE DATABASE " + DB);
        }
        connection = connect(DB);
        StringBuilder sql = new StringBuilder();
        for (String line : Files.readAllLines(SCHEMA)) {
            if (!line.trim().startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        try (Statement s = connection.createStatement()) {
            for (String stmt : sql.toString().split(";")) {
                String t = stmt.trim();
                // the file creates and uses its own database name; this test uses DB instead
                if (t.isEmpty() || t.toUpperCase().startsWith("CREATE DATABASE") || t.toUpperCase().startsWith("USE ")) {
                    continue;
                }
                s.execute(t);
            }
        }
        connection.setAutoCommit(false);
    }

    @AfterEach
    void close() throws Exception {
        SftpReaderFeedWriter.afterInstanceConfigurationForTest = null;
        if (connection != null) {
            connection.close();
        }
    }

    private JsonInterfaceType addEmisType() throws Exception {
        return SftpReaderFeedWriter.addInterfaceType(connection, InterfaceTypeValidator.validate(
                new JsonInterfaceType(null, "EMIS-EXTRACT-SERVICE-5-1", 1, List.of("Admin_Patient", "Agreements_SharingOrganisation"))));
    }

    private int count(String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static FeedConfigException refused(int status, ThrowingRunnable r) {
        FeedConfigException ex = assertThrows(FeedConfigException.class, r::run);
        assertEquals(status, ex.getStatus(), ex.getMessage());
        return ex;
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void interfaceTypesAreAddedWithFileTypesAndReadBack() throws Exception {
        assertTrue(SftpReaderFeedWriter.listInterfaceTypes(connection).isEmpty());

        JsonInterfaceType first = addEmisType();
        assertEquals(1, first.getInterfaceTypeId(), "next free id in an empty table");

        JsonInterfaceType withId = SftpReaderFeedWriter.addInterfaceType(connection, InterfaceTypeValidator.validate(
                new JsonInterfaceType(10, "TPP-SRONE", 7, List.of("SRPatient"))));
        assertEquals(10, withId.getInterfaceTypeId());
        JsonInterfaceType next = SftpReaderFeedWriter.addInterfaceType(connection, InterfaceTypeValidator.validate(
                new JsonInterfaceType(null, "VISION", 1, List.of("patient"))));
        assertEquals(11, next.getInterfaceTypeId(), "max + 1");

        // a type with no file types (e.g. copied without its interface_file_type rows) is listed with none
        try (Statement s = connection.createStatement()) {
            s.execute("INSERT INTO interface_type VALUES (20, 'BARE', 1)");
        }
        connection.commit();

        List<JsonInterfaceType> types = SftpReaderFeedWriter.listInterfaceTypes(connection);
        assertEquals(List.of(1, 10, 11, 20), types.stream().map(JsonInterfaceType::getInterfaceTypeId).toList());
        assertEquals(List.of("Admin_Patient", "Agreements_SharingOrganisation"), types.get(0).getFileTypes());
        assertEquals(7, types.get(1).getDataFrequencyDays());
        assertTrue(types.get(3).getFileTypes().isEmpty());
    }

    @Test
    void duplicateInterfaceTypesAreConflictsAndWriteNothing() throws Exception {
        addEmisType();
        refused(409, () -> SftpReaderFeedWriter.addInterfaceType(connection, InterfaceTypeValidator.validate(
                new JsonInterfaceType(null, "emis-extract-service-5-1", 1, List.of("x")))));
        refused(409, () -> SftpReaderFeedWriter.addInterfaceType(connection, InterfaceTypeValidator.validate(
                new JsonInterfaceType(1, "OTHER", 1, List.of("x")))));
        assertEquals(1, count("SELECT COUNT(*) FROM interface_type"));
        assertEquals(2, count("SELECT COUNT(*) FROM interface_file_type"));
    }

    @Test
    void interfaceTypesAreDeletedOnlyWhenNoFeedUsesThem() throws Exception {
        int used = addEmisType().getInterfaceTypeId();
        int unused = SftpReaderFeedWriter.addInterfaceType(connection, InterfaceTypeValidator.validate(
                new JsonInterfaceType(null, "VISION", 1, List.of("patient", "journal")))).getInterfaceTypeId();
        SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(FeedValidatorTest.validFeed())); // uses type 1

        List<JsonInterfaceType> types = SftpReaderFeedWriter.listInterfaceTypes(connection);
        assertEquals(1, types.get(0).getConfigurationCount());
        assertEquals(0, types.get(1).getConfigurationCount());

        FeedConfigException inUse = refused(409, () -> SftpReaderFeedWriter.deleteInterfaceType(connection, used));
        assertTrue(inUse.getMessage().contains("EMIS99"), inUse.getMessage());
        assertEquals(2, count("SELECT COUNT(*) FROM interface_file_type WHERE interface_type_id = " + used),
                "nothing deleted while in use");

        assertEquals("VISION", SftpReaderFeedWriter.deleteInterfaceType(connection, unused));
        assertEquals(0, count("SELECT COUNT(*) FROM interface_type WHERE interface_type_id = " + unused));
        assertEquals(0, count("SELECT COUNT(*) FROM interface_file_type WHERE interface_type_id = " + unused));

        refused(404, () -> SftpReaderFeedWriter.deleteInterfaceType(connection, unused));
        assertEquals(List.of(used), SftpReaderFeedWriter.listInterfaceTypes(connection).stream()
                .map(JsonInterfaceType::getInterfaceTypeId).toList());
    }

    @Test
    void addFeedWritesTheSixRows() throws Exception {
        int typeId = addEmisType().getInterfaceTypeId();
        JsonAddFeedRequest r = FeedValidatorTest.validFeed();
        r.setInterfaceTypeId(typeId);
        SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(r));

        assertEquals(1, count("SELECT COUNT(*) FROM instance WHERE instance_name = 'EMIS99'"));
        assertEquals(1, count("SELECT COUNT(*) FROM instance_configuration WHERE instance_name = 'EMIS99' AND configuration_id = 'EMIS99'"));
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT c.interface_type_id, c.poll_frequency_seconds, c.software_content_type, c.software_version, s.port,"
                        + " s.client_private_key_password, p.file_extension_filter, k.`value`"
                        + " FROM configuration c"
                        + " JOIN configuration_sftp s ON s.configuration_id = c.configuration_id"
                        + " JOIN configuration_pgp p ON p.configuration_id = c.configuration_id"
                        + " JOIN configuration_kvp k ON k.configuration_id = c.configuration_id AND k.`key` = 'SharingAgreementGuid'"
                        + " WHERE c.configuration_id = 'EMIS99'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(typeId, rs.getInt(1));
            assertEquals(300, rs.getInt(2));
            assertEquals("EMISCSV", rs.getString(3));
            assertEquals("5.1", rs.getString(4));
            assertEquals(22, rs.getInt(5));
            assertEquals(" pw with spaces ", rs.getString(6));
            assertEquals(".gpg", rs.getString(7));
            assertEquals("0b5bd6a5-0000-4000-8000-000000000000", rs.getString(8));
        }

        // a second feed on the existing instance, with no kvp
        JsonAddFeedRequest second = FeedValidatorTest.validFeed();
        second.setInterfaceTypeId(typeId);
        second.setCreateInstance(false);
        second.setConfigurationId("EMIS98");
        second.setFriendlyName("EMIS98 test practice");
        second.setSharingAgreementGuid(null);
        SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(second));
        assertEquals(2, count("SELECT COUNT(*) FROM instance_configuration WHERE instance_name = 'EMIS99'"));
        assertEquals(1, count("SELECT COUNT(*) FROM configuration_kvp"));
        assertEquals(List.of("EMIS99"), SftpReaderFeedWriter.listInstanceNames(connection));
    }

    @Test
    void addFeedRefusals() throws Exception {
        int typeId = addEmisType().getInterfaceTypeId();
        JsonAddFeedRequest first = FeedValidatorTest.validFeed();
        first.setInterfaceTypeId(typeId);
        SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(first));

        JsonAddFeedRequest dupId = FeedValidatorTest.validFeed();
        dupId.setInterfaceTypeId(typeId);
        dupId.setCreateInstance(false);
        dupId.setFriendlyName("other name");
        assertTrue(refused(409, () -> SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(dupId)))
                .getMessage().contains("Configuration id"));

        JsonAddFeedRequest dupName = FeedValidatorTest.validFeed();
        dupName.setInterfaceTypeId(typeId);
        dupName.setCreateInstance(false);
        dupName.setConfigurationId("OTHER");
        dupName.setFriendlyName("emis99 TEST practice"); // the collation is case-insensitive
        refused(409, () -> SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(dupName)));

        JsonAddFeedRequest newInstanceExists = FeedValidatorTest.validFeed();
        newInstanceExists.setInterfaceTypeId(typeId);
        newInstanceExists.setConfigurationId("OTHER");
        newInstanceExists.setFriendlyName("other");
        refused(409, () -> SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(newInstanceExists)));

        JsonAddFeedRequest noInstance = FeedValidatorTest.validFeed();
        noInstance.setInterfaceTypeId(typeId);
        noInstance.setCreateInstance(false);
        noInstance.setInstanceName("NOPE");
        noInstance.setConfigurationId("OTHER");
        noInstance.setFriendlyName("other");
        refused(400, () -> SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(noInstance)));

        JsonAddFeedRequest noType = FeedValidatorTest.validFeed();
        noType.setInterfaceTypeId(999);
        noType.setCreateInstance(false);
        noType.setConfigurationId("OTHER");
        noType.setFriendlyName("other");
        assertTrue(refused(400, () -> SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(noType)))
                .getMessage().contains("does not exist"));

        try (Statement s = connection.createStatement()) {
            s.execute("INSERT INTO interface_type VALUES (20, 'BARE', 1)");
        }
        connection.commit();
        JsonAddFeedRequest bareType = FeedValidatorTest.validFeed();
        bareType.setInterfaceTypeId(20);
        bareType.setCreateInstance(false);
        bareType.setConfigurationId("OTHER");
        bareType.setFriendlyName("other");
        assertTrue(refused(400, () -> SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(bareType)))
                .getMessage().contains("no file types"));

        assertEquals(1, count("SELECT COUNT(*) FROM configuration"), "nothing written by the refused requests");
    }

    // ------------------------------------------------------------------ edit / retire / reinstate / delete

    private String scalar(String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** EMIS99 on instance EMIS99 (interface type 1), a second type 2, and an empty instance TPP01. */
    private void twoTypesOneFeed() throws Exception {
        addEmisType();
        SftpReaderFeedWriter.addInterfaceType(connection, InterfaceTypeValidator.validate(
                new JsonInterfaceType(null, "EMIS-OTHER", 1, List.of("Admin_Patient"))));
        SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(FeedValidatorTest.validFeed()));
        try (Statement s = connection.createStatement()) {
            s.execute("INSERT INTO instance (instance_name) VALUES ('TPP01')");
        }
        connection.commit();
    }

    private JsonAddFeedRequest editOf(com.fasterxml.jackson.databind.JsonNode stored) throws Exception {
        JsonAddFeedRequest r = new com.fasterxml.jackson.databind.ObjectMapper()
                .readerFor(JsonAddFeedRequest.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(stored);
        return r; // secrets are absent from the GET, so they stay null = keep
    }

    private void insertBatch(int interfaceTypeId) throws Exception {
        try (Statement s = connection.createStatement()) {
            s.execute("INSERT INTO batch (configuration_id, interface_type_id, batch_identifier, local_relative_path, is_complete)"
                    + " VALUES ('EMIS99', " + interfaceTypeId + ", 'b1', 'EMIS99/b1', false)");
        }
        connection.commit();
    }

    @Test
    void editKeepsSecretsUnlessSentAndChecksTheVersion() throws Exception {
        twoTypesOneFeed();
        var stored = SftpReaderFeedEditor.getFeed(connection, "EMIS99");
        assertFalse(stored.toString().contains(" pw with spaces "), "no secrets in GET");
        assertEquals("0b5bd6a5-0000-4000-8000-000000000000", stored.get("sharingAgreementGuid").asText());

        JsonAddFeedRequest edit = editOf(stored);
        edit.setFriendlyName("Renamed practice");
        edit.setPort(2222);
        edit.setClientPublicKey("ssh-rsa NEW");
        edit.setSharingAgreementGuid(null);
        var result = SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(edit, "EMIS99"),
                stored.get("version").asText());
        assertEquals(List.of("friendlyName", "port", "clientPublicKey", "sharingAgreementGuid"), result.changedFields());
        assertEquals(List.of("EMIS99"), result.restartInstances());
        assertEquals("Renamed practice", scalar("SELECT configuration_friendly_name FROM configuration WHERE configuration_id = 'EMIS99'"));
        assertEquals("2222", scalar("SELECT port FROM configuration_sftp WHERE configuration_id = 'EMIS99'"));
        assertEquals(" pw with spaces ", scalar("SELECT client_private_key_password FROM configuration_sftp WHERE configuration_id = 'EMIS99'"),
                "an omitted password keeps the stored one");
        assertEquals("secret", scalar("SELECT recipient_private_key_password FROM configuration_pgp WHERE configuration_id = 'EMIS99'"));
        assertEquals(0, count("SELECT COUNT(*) FROM configuration_kvp"), "a cleared GUID removes the kvp row");

        // the old version is now stale
        JsonAddFeedRequest stale = editOf(stored);
        stale.setPollFrequencySeconds(60);
        refused(409, () -> SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(stale, "EMIS99"),
                stored.get("version").asText()));

        // a sent password replaces the stored one, and the GUID can be added back
        var again = SftpReaderFeedEditor.getFeed(connection, "EMIS99");
        JsonAddFeedRequest pw = editOf(again);
        pw.setRecipientPrivateKeyPassword("new-secret");
        pw.setSharingAgreementGuid("guid-2");
        var r2 = SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(pw, "EMIS99"), again.get("version").asText());
        assertEquals(List.of("recipientPrivateKeyPassword", "sharingAgreementGuid"), r2.changedFields());
        assertEquals("new-secret", scalar("SELECT recipient_private_key_password FROM configuration_pgp WHERE configuration_id = 'EMIS99'"));
        assertEquals("guid-2", scalar("SELECT `value` FROM configuration_kvp WHERE configuration_id = 'EMIS99'"));

        // no change at all is fine and restarts nothing
        var same = SftpReaderFeedEditor.getFeed(connection, "EMIS99");
        var r3 = SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(editOf(same), "EMIS99"), same.get("version").asText());
        assertTrue(r3.changedFields().isEmpty());
        assertTrue(r3.restartInstances().isEmpty());

        refused(404, () -> SftpReaderFeedEditor.getFeed(connection, "NOPE"));
    }

    @Test
    void interfaceTypeAndRootPathAreLockedOnceBatchesExist() throws Exception {
        twoTypesOneFeed();
        var stored = SftpReaderFeedEditor.getFeed(connection, "EMIS99");
        JsonAddFeedRequest edit = editOf(stored);
        edit.setInterfaceTypeId(2);
        SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(edit, "EMIS99"), stored.get("version").asText());
        assertEquals("2", scalar("SELECT interface_type_id FROM configuration WHERE configuration_id = 'EMIS99'"));

        insertBatch(2);
        var withBatch = SftpReaderFeedEditor.getFeed(connection, "EMIS99");
        assertTrue(withBatch.get("hasBatches").asBoolean());
        JsonAddFeedRequest type = editOf(withBatch);
        type.setInterfaceTypeId(1);
        assertTrue(refused(409, () -> SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(type, "EMIS99"),
                withBatch.get("version").asText())).getMessage().contains("interface type"));
        JsonAddFeedRequest root = editOf(withBatch);
        root.setLocalRootPath("elsewhere");
        assertTrue(refused(409, () -> SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(root, "EMIS99"),
                withBatch.get("version").asText())).getMessage().contains("root path"));
        // other fields can still change
        JsonAddFeedRequest poll = editOf(withBatch);
        poll.setPollFrequencySeconds(600);
        SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(poll, "EMIS99"), withBatch.get("version").asText());
        assertEquals("600", scalar("SELECT poll_frequency_seconds FROM configuration WHERE configuration_id = 'EMIS99'"));
    }

    @Test
    void movingToAnotherInstance() throws Exception {
        twoTypesOneFeed();
        var stored = SftpReaderFeedEditor.getFeed(connection, "EMIS99");
        JsonAddFeedRequest bad = editOf(stored);
        bad.setInstanceName("NOPE");
        refused(400, () -> SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(bad, "EMIS99"), stored.get("version").asText()));
        JsonAddFeedRequest viaEdit = editOf(stored);
        viaEdit.setInstanceName("NOT_USED");
        refused(400, () -> SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(viaEdit, "EMIS99"), stored.get("version").asText()));

        JsonAddFeedRequest move = editOf(stored);
        move.setInstanceName("TPP01");
        var r = SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(move, "EMIS99"), stored.get("version").asText());
        assertEquals(List.of("EMIS99", "TPP01"), r.restartInstances());
        assertEquals("TPP01", scalar("SELECT instance_name FROM instance_configuration WHERE configuration_id = 'EMIS99'"));
    }

    @Test
    void writesWaitForNoPollingRun() throws Exception {
        twoTypesOneFeed();
        // a second session holds the reader's lock, as a polling run would
        try (Connection reader = connect(DB); Statement s = reader.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT GET_LOCK('SftpReader-EMIS99', 0)")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
            assertTrue(refused(409, () -> SftpReaderFeedEditor.retireFeed(connection, "EMIS99")).getMessage().contains("polling"));
            refused(409, () -> SftpReaderFeedEditor.deleteFeed(connection, "EMIS99"));
            s.executeQuery("SELECT RELEASE_LOCK('SftpReader-EMIS99')").close();
        }
        assertEquals("EMIS99", SftpReaderFeedEditor.retireFeed(connection, "EMIS99"));
        // and the editor released its own lock, so a "reader" can take it again
        try (Connection reader = connect(DB); Statement s = reader.createStatement();
             ResultSet rs = s.executeQuery("SELECT GET_LOCK('SftpReader-EMIS99', 0)")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void retireThenReinstate() throws Exception {
        twoTypesOneFeed();
        insertBatch(1);
        assertEquals("EMIS99", SftpReaderFeedEditor.retireFeed(connection, "EMIS99"));
        assertEquals("NOT_USED", scalar("SELECT instance_name FROM instance_configuration WHERE configuration_id = 'EMIS99'"));
        assertEquals(1, count("SELECT COUNT(*) FROM instance WHERE instance_name = 'NOT_USED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM configuration_paused_notifying WHERE configuration_id = 'EMIS99'"));
        assertEquals(1, count("SELECT COUNT(*) FROM batch"), "history is kept");
        refused(409, () -> SftpReaderFeedEditor.retireFeed(connection, "EMIS99"));

        var retired = SftpReaderFeedEditor.getFeed(connection, "EMIS99");
        assertTrue(retired.get("retired").asBoolean());
        JsonAddFeedRequest move = editOf(retired);
        move.setInstanceName("TPP01");
        refused(400, () -> SftpReaderFeedEditor.updateFeed(connection, FeedValidator.validateEdit(move, "EMIS99"), retired.get("version").asText()));

        // the feed list hides it unless asked (the endpoint filters NOT_USED in code; the SQL returns it with hasBatches)
        try (PreparedStatement ps = connection.prepareStatement(org.endeavourhealth.ui.endpoints.SftpReaderEndpoint.INSTANCES_SQL);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("NOT_USED", rs.getString(3));
            assertTrue(rs.getBoolean(5), "hasBatches");
        }

        refused(400, () -> SftpReaderFeedEditor.reinstateFeed(connection, "EMIS99", "NOPE"));
        refused(400, () -> SftpReaderFeedEditor.reinstateFeed(connection, "EMIS99", "NOT_USED"));
        SftpReaderFeedEditor.reinstateFeed(connection, "EMIS99", "TPP01");
        assertEquals("TPP01", scalar("SELECT instance_name FROM instance_configuration WHERE configuration_id = 'EMIS99'"));
        assertEquals(1, count("SELECT COUNT(*) FROM configuration_paused_notifying WHERE configuration_id = 'EMIS99'"),
                "reinstating leaves it paused");
        refused(409, () -> SftpReaderFeedEditor.reinstateFeed(connection, "EMIS99", "TPP01"));
    }

    @Test
    void deleteOnlyWithoutHistory() throws Exception {
        twoTypesOneFeed();
        try (Statement s = connection.createStatement()) {
            s.execute("INSERT INTO configuration_polling_attempt (configuration_id, attempt_started, attempt_finished)"
                    + " VALUES ('EMIS99', NOW(), NOW())");
            s.execute("INSERT INTO unknown_file (configuration_id, filename, remote_size_bytes) VALUES ('EMIS99', 'x.txt', 1)");
            s.execute("INSERT INTO configuration_paused_notifying (configuration_id) VALUES ('EMIS99')");
        }
        connection.commit();

        var deleted = SftpReaderFeedEditor.deleteFeed(connection, "EMIS99");
        assertEquals(1, deleted.get("configuration"));
        assertEquals(1, deleted.get("configuration_polling_attempt"));
        assertEquals(1, deleted.get("unknown_file"));
        for (String table : SftpReaderFeedEditor.DELETE_ORDER) {
            assertEquals(0, count("SELECT COUNT(*) FROM " + table + " WHERE configuration_id = 'EMIS99'"), table);
        }
        assertEquals(1, count("SELECT COUNT(*) FROM instance WHERE instance_name = 'EMIS99'"), "the instance row is kept");
        refused(404, () -> SftpReaderFeedEditor.deleteFeed(connection, "EMIS99"));

        // a feed with a batch is refused and left whole
        JsonAddFeedRequest again = FeedValidatorTest.validFeed();
        again.setCreateInstance(false); // the instance row was kept
        SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(again));
        insertBatch(1);
        assertTrue(refused(409, () -> SftpReaderFeedEditor.deleteFeed(connection, "EMIS99")).getMessage().contains("retire"));
        assertEquals(1, count("SELECT COUNT(*) FROM configuration WHERE configuration_id = 'EMIS99'"));
        assertEquals(1, count("SELECT COUNT(*) FROM configuration_sftp WHERE configuration_id = 'EMIS99'"));
    }

    @Test
    void aLaterFailureRollsBackTheWholeFeed() throws Exception {
        int typeId = addEmisType().getInterfaceTypeId();
        JsonAddFeedRequest r = FeedValidatorTest.validFeed();
        r.setInterfaceTypeId(typeId);
        SftpReaderFeedWriter.afterInstanceConfigurationForTest = () -> {
            throw new IllegalStateException("forced failure after instance_configuration");
        };
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(r)));
        assertTrue(ex.getMessage().contains("forced"));

        assertEquals(0, count("SELECT COUNT(*) FROM instance"));
        assertEquals(0, count("SELECT COUNT(*) FROM configuration"));
        assertEquals(0, count("SELECT COUNT(*) FROM instance_configuration"));
        assertEquals(0, count("SELECT COUNT(*) FROM configuration_sftp"));

        // and the same feed goes in once the failure is gone
        SftpReaderFeedWriter.afterInstanceConfigurationForTest = null;
        SftpReaderFeedWriter.addFeed(connection, FeedValidator.validate(FeedValidatorTest.validFeed()));
        assertEquals(1, count("SELECT COUNT(*) FROM configuration_pgp"));
    }
}
