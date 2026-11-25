package org.endeavourhealth.ui.sftpreader;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The parts of {@link SftpReaderFeedEditor} that need no database; the rest is in the opt-in MySQL test. */
class SftpReaderFeedEditorTest {

    static SftpReaderFeedEditor.StoredFeed feed(String clientPassword, String guid, boolean paused, boolean hasBatches) {
        return new SftpReaderFeedEditor.StoredFeed("EMIS99", 1, 300, "EMIS99", "EMIS99 test", "EMISCSV", "5.1", "EMIS99",
                "sftp.example.test", 22, "/out", "user", "ssh-rsa client", "CLIENT-PRIVATE-KEY", clientPassword,
                "ssh-rsa host", ".gpg", "sender", "us", "RECIPIENT-PRIVATE-KEY", "recipient-secret", guid, paused, hasBatches);
    }

    @Test
    void versionIsStableAndSeesEveryEditableValue() {
        String v = feed("pw", "guid", false, false).version();
        assertEquals(64, v.length());
        assertEquals(v, feed("pw", "guid", false, false).version());
        assertNotEquals(v, feed("pw2", "guid", false, false).version(), "a password change is seen");
        assertNotEquals(v, feed("pw", null, false, false).version(), "removing the kvp is seen");
        assertNotEquals(v, feed("pw", "", false, false).version(), "null and empty differ");
        // pausing and receiving batches happen without an edit, so they don't invalidate an open form
        assertEquals(v, feed("pw", "guid", true, true).version());
    }

    @Test
    void versionIsNotTheSecret() {
        String v = feed("pw", "guid", false, false).version();
        assertFalse(v.contains("pw") || v.contains("PRIVATE"), v);
    }

    @Test
    void describeNeverReturnsPrivateKeysOrPasswords() {
        ObjectNode o = SftpReaderFeedEditor.describe(feed("client-secret", "guid", true, true));
        String json = o.toString();
        for (String secret : List.of("CLIENT-PRIVATE-KEY", "client-secret", "RECIPIENT-PRIVATE-KEY", "recipient-secret")) {
            assertFalse(json.contains(secret), "leaked " + secret + ": " + json);
        }
        assertTrue(o.get("clientPrivateKeySet").asBoolean());
        assertTrue(o.get("clientPrivateKeyPasswordSet").asBoolean());
        assertTrue(o.get("recipientPrivateKeySet").asBoolean());
        assertTrue(o.get("recipientPrivateKeyPasswordSet").asBoolean());
        assertEquals("ssh-rsa client", o.get("clientPublicKey").asText(), "public keys are returned");
        assertTrue(o.get("paused").asBoolean());
        assertTrue(o.get("hasBatches").asBoolean());
        assertFalse(o.get("retired").asBoolean());
        assertEquals(feed("client-secret", "guid", true, true).version(), o.get("version").asText());

        ObjectNode missing = SftpReaderFeedEditor.describe(feed(null, null, false, false));
        assertFalse(missing.get("clientPrivateKeyPasswordSet").asBoolean());
    }

    @Test
    void lockResults() throws Exception {
        SftpReaderFeedEditor.checkLockResult(1, "EMIS99");
        FeedConfigException held = assertThrows(FeedConfigException.class, () -> SftpReaderFeedEditor.checkLockResult(0, "EMIS99"));
        assertEquals(409, held.getStatus());
        assertTrue(held.getMessage().contains("polling"), held.getMessage());
        assertEquals(409, assertThrows(FeedConfigException.class,
                () -> SftpReaderFeedEditor.checkLockResult(null, "EMIS99")).getStatus());
    }

    @Test
    void tooLongIdsAreNotFoundWithoutTouchingTheDatabase() {
        // lock() checks the id before using the connection, so null is fine here
        FeedConfigException ex = assertThrows(FeedConfigException.class,
                () -> SftpReaderFeedEditor.lock(null, "x".repeat(FeedValidator.MAX_CONFIGURATION_ID + 1)));
        assertEquals(404, ex.getStatus());
    }

    @Test
    void deleteOrderIsChildrenFirstAndNeverTouchesHistory() {
        List<String> order = SftpReaderFeedEditor.DELETE_ORDER;
        assertEquals("configuration", order.get(order.size() - 1));
        assertTrue(order.indexOf("instance_configuration") < order.indexOf("configuration"));
        for (String history : List.of("batch", "batch_file", "batch_split", "notification_message")) {
            assertFalse(order.contains(history), history);
        }
    }
}
