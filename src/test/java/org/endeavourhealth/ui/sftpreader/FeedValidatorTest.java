package org.endeavourhealth.ui.sftpreader;

import org.endeavourhealth.ui.json.JsonAddFeedRequest;
import org.endeavourhealth.ui.json.JsonInterfaceType;
import org.endeavourhealth.ui.messageformats.MessageFormats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FeedValidatorTest {

    /** the message format list row (global/message_formats); null = no row, so the built-in defaults */
    private static String formatsRow;

    @BeforeEach
    void noFormatsRow() {
        formatsRow = null;
        MessageFormats.setStore(new MessageFormats.Store() {
            public String read() {
                return formatsRow;
            }

            public void write(String json) {
                formatsRow = json;
            }
        });
    }

    @AfterEach
    void restoreStore() {
        MessageFormats.setStore(null);
    }

    /** A feed shaped like the deployment guide's EMIS99 example. */
    static JsonAddFeedRequest validFeed() {
        JsonAddFeedRequest r = new JsonAddFeedRequest();
        r.setInstanceName("EMIS99");
        r.setCreateInstance(true);
        r.setConfigurationId("EMIS99");
        r.setInterfaceTypeId(1);
        r.setPollFrequencySeconds(300);
        r.setLocalRootPath("EMIS99");
        r.setFriendlyName("EMIS99 test practice");
        r.setSoftwareContentType("emiscsv");
        r.setSoftwareVersion("5.1");
        r.setHostname("sftp.example.test");
        r.setPort(22);
        r.setRemotePath("/outgoing");
        r.setUsername("sftp-user");
        r.setClientPublicKey("ssh-rsa AAAA client");
        r.setClientPrivateKey("-----BEGIN RSA PRIVATE KEY-----\nclient\n-----END RSA PRIVATE KEY-----");
        r.setClientPrivateKeyPassword(" pw with spaces ");
        r.setHostPublicKey("ssh-rsa AAAA host");
        r.setFileExtensionFilter(".gpg");
        r.setSenderPublicKey("-----BEGIN PGP PUBLIC KEY BLOCK-----\nsender");
        r.setRecipientPublicKey("-----BEGIN PGP PUBLIC KEY BLOCK-----\nus");
        r.setRecipientPrivateKey("-----BEGIN PGP PRIVATE KEY BLOCK-----\nus");
        r.setRecipientPrivateKeyPassword("secret");
        r.setSharingAgreementGuid(" 0b5bd6a5-0000-4000-8000-000000000000 ");
        return r;
    }

    private static void assertRefused(Consumer<JsonAddFeedRequest> change, String expectInMessage) {
        JsonAddFeedRequest r = validFeed();
        change.accept(r);
        FeedConfigException ex = assertThrows(FeedConfigException.class, () -> FeedValidator.validate(r));
        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains(expectInMessage), ex.getMessage());
    }

    @Test
    void validFeedIsNormalised() throws Exception {
        JsonAddFeedRequest r = FeedValidator.validate(validFeed());
        assertEquals("EMISCSV", r.getSoftwareContentType());
        assertEquals("0b5bd6a5-0000-4000-8000-000000000000", r.getSharingAgreementGuid());
        assertEquals(" pw with spaces ", r.getClientPrivateKeyPassword(), "passwords are not trimmed");
        assertTrue(r.getCreateInstance());
    }

    @Test
    void createInstanceDefaultsToFalseAndGuidIsOptional() throws Exception {
        JsonAddFeedRequest in = validFeed();
        in.setCreateInstance(null);
        in.setSharingAgreementGuid("   ");
        JsonAddFeedRequest r = FeedValidator.validate(in);
        assertFalse(r.getCreateInstance());
        assertNull(r.getSharingAgreementGuid());
    }

    @Test
    void clientPublicKeyIsOptionalAndStoredAsEmpty() throws Exception {
        for (String blank : new String[]{null, "", "   "}) {
            JsonAddFeedRequest in = validFeed();
            in.setClientPublicKey(blank);
            assertEquals("", FeedValidator.validate(in).getClientPublicKey(), "add, blank=" + blank);
            JsonAddFeedRequest edit = validFeed();
            edit.setClientPublicKey(blank);
            assertEquals("", FeedValidator.validateEdit(edit, "EMIS99").getClientPublicKey(), "edit, blank=" + blank);
        }
        JsonAddFeedRequest in = validFeed();
        in.setClientPublicKey(" ssh-rsa AAAA client ");
        assertEquals("ssh-rsa AAAA client", FeedValidator.validate(in).getClientPublicKey());
    }

    @Test
    void nothingSent() {
        assertThrows(FeedConfigException.class, () -> FeedValidator.validate(null));
    }

    @Test
    void requiredFields() {
        assertRefused(r -> r.setInstanceName(" "), "Instance name is required");
        assertRefused(r -> r.setConfigurationId(null), "Configuration id is required");
        assertRefused(r -> r.setFriendlyName(""), "Friendly name is required");
        assertRefused(r -> r.setLocalRootPath(null), "Local root path is required");
        assertRefused(r -> r.setSoftwareContentType(null), "Software content type is required");
        assertRefused(r -> r.setSoftwareVersion(" "), "Software version is required");
        assertRefused(r -> r.setInterfaceTypeId(null), "Interface type is required");
        assertRefused(r -> r.setPollFrequencySeconds(null), "Poll frequency");
        assertRefused(r -> r.setHostname(null), "SFTP hostname is required");
        assertRefused(r -> r.setRemotePath(null), "SFTP remote path is required");
        assertRefused(r -> r.setUsername(null), "SFTP username is required");
        assertRefused(r -> r.setClientPrivateKey(null), "Client private key is required");
        assertRefused(r -> r.setClientPrivateKeyPassword(""), "Client private key password is required");
        assertRefused(r -> r.setHostPublicKey(null), "Host public key is required");
        assertRefused(r -> r.setFileExtensionFilter(null), "File extension filter is required");
        assertRefused(r -> r.setSenderPublicKey(null), "Sender public key is required");
        assertRefused(r -> r.setRecipientPublicKey(null), "Recipient public key is required");
        assertRefused(r -> r.setRecipientPrivateKey(null), "Recipient private key is required");
        assertRefused(r -> r.setRecipientPrivateKeyPassword(null), "Recipient private key password is required");
    }

    @Test
    void numbers() {
        assertRefused(r -> r.setPort(0), "port");
        assertRefused(r -> r.setPort(65536), "port");
        assertRefused(r -> r.setPort(null), "port");
        assertRefused(r -> r.setPollFrequencySeconds(0), "Poll frequency");
        assertRefused(r -> r.setInterfaceTypeId(0), "Interface type");
    }

    @Test
    void portBoundsAreAccepted() throws Exception {
        for (int port : new int[]{1, 65535}) {
            JsonAddFeedRequest r = validFeed();
            r.setPort(port);
            FeedValidator.validate(r);
        }
    }

    @Test
    void contentTypeMustBeOneTheReaderKnows() throws Exception {
        assertRefused(r -> r.setSoftwareContentType("EMIS"), "must be one of");
        // transform-only formats are not SFTP Reader formats
        assertRefused(r -> r.setSoftwareContentType("HL7V2"), "must be one of");
        for (String ok : new String[]{"EMISCSV", "VISIONCSV", "BARTSCSV", "HOMERTONCSV", "TPPCSV", "ADASTRACSV", "BHRUTCSV",
                "IMPERIALCSV", "NEMSXML"}) {
            JsonAddFeedRequest r = validFeed();
            r.setSoftwareContentType(ok.toLowerCase());
            assertEquals(ok, FeedValidator.validate(r).getSoftwareContentType());
        }
    }

    @Test
    void contentTypesComeFromTheMessageFormatRow() throws Exception {
        formatsRow = "[{\"format\":\"NEWCSV\",\"sftpReader\":true,\"transform\":true},"
                + "{\"format\":\"EMISCSV\",\"sftpReader\":false,\"transform\":true}]";
        JsonAddFeedRequest r = validFeed();
        r.setSoftwareContentType("newcsv");
        assertEquals("NEWCSV", FeedValidator.validate(r).getSoftwareContentType());
        assertRefused(x -> x.setSoftwareContentType("EMISCSV"), "must be one of NEWCSV");
    }

    @Test
    void idsAreSafe() {
        assertRefused(r -> r.setInstanceName("EMIS 99"), "may only contain");
        assertRefused(r -> r.setInstanceName("../x"), "may only contain");
        assertRefused(r -> r.setConfigurationId("a/b"), "may only contain");
        assertRefused(r -> r.setConfigurationId("a#b"), "may only contain");
    }

    @Test
    void lengthLimitsFromTheSchema() throws Exception {
        String s100 = "a".repeat(100);
        String s1000 = "a".repeat(1000);
        JsonAddFeedRequest atLimit = validFeed();
        atLimit.setConfigurationId("c".repeat(53));
        atLimit.setInstanceName(s100);
        atLimit.setFriendlyName(s100);
        atLimit.setSoftwareVersion(s100);
        atLimit.setHostname(s100);
        atLimit.setUsername(s100);
        atLimit.setFileExtensionFilter(s100);
        atLimit.setLocalRootPath(s1000);
        atLimit.setRemotePath(s1000);
        atLimit.setClientPrivateKeyPassword(s1000);
        atLimit.setRecipientPrivateKeyPassword(s1000);
        atLimit.setSharingAgreementGuid(s1000);
        atLimit.setClientPrivateKey("k".repeat(FeedValidator.MAX_KEY));
        FeedValidator.validate(atLimit);

        String s101 = "a".repeat(101);
        String s1001 = "a".repeat(1001);
        assertRefused(r -> r.setConfigurationId("c".repeat(54)), "at most 53");
        assertRefused(r -> r.setInstanceName(s101), "at most 100");
        assertRefused(r -> r.setFriendlyName(s101), "at most 100");
        assertRefused(r -> r.setSoftwareVersion(s101), "at most 100");
        assertRefused(r -> r.setHostname(s101), "at most 100");
        assertRefused(r -> r.setUsername(s101), "at most 100");
        assertRefused(r -> r.setFileExtensionFilter(s101), "at most 100");
        assertRefused(r -> r.setLocalRootPath(s1001), "at most 1000");
        assertRefused(r -> r.setRemotePath(s1001), "at most 1000");
        assertRefused(r -> r.setClientPrivateKeyPassword(s1001), "at most 1000");
        assertRefused(r -> r.setRecipientPrivateKeyPassword(s1001), "at most 1000");
        assertRefused(r -> r.setSharingAgreementGuid(s1001), "at most 1000");
        assertRefused(r -> r.setHostPublicKey("k".repeat(FeedValidator.MAX_KEY + 1)), "at most " + FeedValidator.MAX_KEY);
    }

    @Test
    void configurationIdFitsTheReadersLockName() {
        // the reader locks 'SftpReader-' + id, and MySQL lock names are at most 64 characters
        assertEquals(64, "SftpReader-".length() + FeedValidator.MAX_CONFIGURATION_ID);
    }

    @Test
    void theRetiredInstanceCantBeChosenForANewFeed() {
        assertRefused(r -> r.setInstanceName("NOT_USED"), "retired");
        assertRefused(r -> r.setInstanceName("not_used"), "retired");
    }

    @Test
    void anEditMayLeaveSecretsOut() throws Exception {
        JsonAddFeedRequest r = validFeed();
        r.setConfigurationId("ignored-from-body");
        r.setClientPrivateKey("  ");
        r.setClientPrivateKeyPassword("");
        r.setRecipientPrivateKey(null);
        r.setRecipientPrivateKeyPassword(null);
        r.setCreateInstance(true);
        JsonAddFeedRequest v = FeedValidator.validateEdit(r, "EMIS99");
        assertEquals("EMIS99", v.getConfigurationId(), "the id comes from the URL");
        assertFalse(v.getCreateInstance(), "an edit never creates an instance");
        assertNull(v.getClientPrivateKey());
        assertNull(v.getClientPrivateKeyPassword());
        assertNull(v.getRecipientPrivateKey());
        assertNull(v.getRecipientPrivateKeyPassword());

        // the other checks still apply
        JsonAddFeedRequest bad = validFeed();
        bad.setPort(0);
        assertThrows(FeedConfigException.class, () -> FeedValidator.validateEdit(bad, "EMIS99"));
        JsonAddFeedRequest longPw = validFeed();
        longPw.setRecipientPrivateKeyPassword("x".repeat(1001));
        assertThrows(FeedConfigException.class, () -> FeedValidator.validateEdit(longPw, "EMIS99"));
        assertThrows(FeedConfigException.class, () -> FeedValidator.validateEdit(null, "EMIS99"));
    }

    @Test
    void messagesNeverContainSecrets() {
        JsonAddFeedRequest r = validFeed();
        r.setClientPrivateKeyPassword("x".repeat(1001));
        FeedConfigException ex = assertThrows(FeedConfigException.class, () -> FeedValidator.validate(r));
        assertFalse(ex.getMessage().contains("xxxx"), ex.getMessage());
    }

    // ------------------------------------------------------------------ interface types

    private static JsonInterfaceType type(String name, Integer days, Integer id, String... files) {
        return new JsonInterfaceType(id, name, days, Arrays.asList(files));
    }

    private static void assertTypeRefused(JsonInterfaceType t, String expect) {
        FeedConfigException ex = assertThrows(FeedConfigException.class, () -> InterfaceTypeValidator.validate(t));
        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains(expect), ex.getMessage());
    }

    @Test
    void validInterfaceTypeIsTrimmed() throws Exception {
        JsonInterfaceType t = InterfaceTypeValidator.validate(type(" EMIS-EXTRACT-SERVICE-5-1 ", 1, null,
                " Admin_Patient ", "", "  ", "Agreements_SharingOrganisation"));
        assertEquals("EMIS-EXTRACT-SERVICE-5-1", t.getInterfaceTypeName());
        assertNull(t.getInterfaceTypeId(), "left for the next free id");
        assertEquals(List.of("Admin_Patient", "Agreements_SharingOrganisation"), t.getFileTypes());
    }

    @Test
    void interfaceTypeChecks() {
        assertThrows(FeedConfigException.class, () -> InterfaceTypeValidator.validate(null));
        assertTypeRefused(type(" ", 1, null, "a"), "name is required");
        assertTypeRefused(type("x".repeat(1001), 1, null, "a"), "at most 1000");
        assertTypeRefused(type("t", null, null, "a"), "Data frequency");
        assertTypeRefused(type("t", 0, null, "a"), "Data frequency");
        assertTypeRefused(type("t", 1, 0, "a"), "id must be 1 or more");
        assertTypeRefused(type("t", 1, null), "At least one file type");
        assertTypeRefused(type("t", 1, null, " ", ""), "At least one file type");
        assertTypeRefused(type("t", 1, null, "Admin_Patient", "admin_patient"), "listed twice");
        assertTypeRefused(type("t", 1, null, "f".repeat(1001)), "at most 1000");
    }

    // ------------------------------------------------------------------ SQL error mapping

    @Test
    void onlyDuplicateKeysAreConflicts() {
        Exception dup = SftpReaderFeedWriter.mapDuplicate(
                new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062), "in use");
        assertInstanceOf(FeedConfigException.class, dup);
        assertEquals(409, ((FeedConfigException) dup).getStatus());

        // a foreign key failure has the same SQLState but is not a duplicate
        SQLException fk = new SQLIntegrityConstraintViolationException("Cannot add or update a child row", "23000", 1452);
        assertSame(fk, SftpReaderFeedWriter.mapDuplicate(fk, "in use"));

        FeedConfigException already = FeedConfigException.badRequest("x");
        assertSame(already, SftpReaderFeedWriter.mapDuplicate(already, "in use"));
    }

    @Test
    void onlyReferencedRowsAreInUseConflicts() {
        Exception inUse = SftpReaderFeedWriter.mapInUse(
                new SQLIntegrityConstraintViolationException("Cannot delete or update a parent row", "23000", 1451), "in use");
        assertInstanceOf(FeedConfigException.class, inUse);
        assertEquals(409, ((FeedConfigException) inUse).getStatus());

        SQLException other = new SQLException("Lock wait timeout", "HY000", 1205);
        assertSame(other, SftpReaderFeedWriter.mapInUse(other, "in use"));
    }
}
