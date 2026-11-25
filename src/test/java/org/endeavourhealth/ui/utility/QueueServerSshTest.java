package org.endeavourhealth.ui.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueueServerSshTest {

    @Test
    void acceptsNormalValues() {
        assertEquals("EdsInbound-A1", QueueServerSsh.checkQueueName("EdsInbound-A1"));
        assertEquals("2g", QueueServerSsh.checkMemory("2g"));
        assertEquals("650m", QueueServerSsh.checkMemory("650m"));
        assertEquals("queueReader.jar", QueueServerSsh.checkJarFileName("queueReader.jar"));
        assertEquals("i-0123456789abcdef0", QueueServerSsh.checkAwsInstanceId("i-0123456789abcdef0"));
        assertEquals("10.0.0.12", QueueServerSsh.checkHost("10.0.0.12"));
    }

    @Test
    void rejectsShellMetacharacters() {
        for (String bad : new String[]{"EdsInbound; rm -rf /", "a\"b", "$(id)", "`id`", "a b", "a|b", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.checkQueueName(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.checkMemory("2g && reboot"));
        assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.checkJarFileName("../x.jar"));
        assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.checkJarFileName("a.jar; reboot"));
        assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.checkAwsInstanceId("i-123 --dry-run"));
    }

    @Test
    void parsesUserAndHostWithoutDroppingCharacters() {
        // the dds-ui port used substring(0, ix - 1), turning "ubuntu" into "ubunt"
        QueueServerSsh.Target t = QueueServerSsh.Target.parse("ubuntu@10.1.2.3");
        assertEquals("ubuntu", t.user());
        assertEquals("10.1.2.3", t.host());

        assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.Target.parse("ubuntu"));
        assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.Target.parse("ub;untu@host"));
        assertThrows(IllegalArgumentException.class, () -> QueueServerSsh.Target.parse("ubuntu@host; reboot"));
    }
}
