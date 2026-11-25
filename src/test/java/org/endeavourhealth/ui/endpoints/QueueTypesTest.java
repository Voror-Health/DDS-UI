package org.endeavourhealth.ui.endpoints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueueTypesTest {

    @Test
    void endeavourVhpAndUnprefixedNames() {
        assertEquals("inbound", QueueTypes.of("EdsInbound-A"));
        assertEquals("inbound", QueueTypes.of("VhpInbound-A"));
        assertEquals("inbound", QueueTypes.of("inbound-a"));
        assertEquals("protocol", QueueTypes.of("EdsProtocol-A"));
        assertEquals("protocol", QueueTypes.of("VhpProtocol"));
        assertEquals("transform", QueueTypes.of("vhptransform-b"));
        assertEquals("subscriber", QueueTypes.of("Subscriber-x"));
    }

    @Test
    void unknownNames() {
        assertNull(QueueTypes.of(null));
        assertNull(QueueTypes.of(""));
        assertNull(QueueTypes.of("EdsResponse"));
        assertNull(QueueTypes.of("MyInbound"));
    }
}
