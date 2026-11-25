package org.endeavourhealth.core.queueing;

import org.endeavourhealth.core.configuration.PostMessageToExchangeConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DDS-UI's "Post to exchange" (ExchangeAuditEndpoint -> QueueHelper.postToExchange) looks up the
 * PostMessageToExchange config for the chosen exchange. The library only accepted the Endeavour names (EdsInbound),
 * so with the VHP config (VhpInbound) it failed with "Failed to find PostMessageToExchange config details for
 * exchange INBOUND". Check that the webapp's copy is the loaded one and that it accepts both names.
 */
class QueueHelperOverrideTest {

    @Test
    void overrideIsTheLoadedClass() {
        URL location = QueueHelper.class.getProtectionDomain().getCodeSource().getLocation();
        assertFalse(location.toString().endsWith(".jar"), "QueueHelper loaded from " + location);
    }

    @Test
    void fromNameAcceptsEndeavourAndVhpNames() {
        //the front end sends "edsInbound" / "edsProtocol"; the queue reader's EMIS fix sends "EdsInbound"
        assertEquals(QueueHelper.ExchangeName.INBOUND, QueueHelper.ExchangeName.fromName("edsInbound"));
        assertEquals(QueueHelper.ExchangeName.INBOUND, QueueHelper.ExchangeName.fromName("EdsInbound"));
        assertEquals(QueueHelper.ExchangeName.INBOUND, QueueHelper.ExchangeName.fromName("VhpInbound"));
        assertEquals(QueueHelper.ExchangeName.PROTOCOL, QueueHelper.ExchangeName.fromName("vhpprotocol"));
        assertEquals(QueueHelper.ExchangeName.SUBSCRIBER, QueueHelper.ExchangeName.fromName("VhpSubscriber"));
        assertThrows(IllegalArgumentException.class, () -> QueueHelper.ExchangeName.fromName("Inbound"));
        assertThrows(IllegalArgumentException.class, () -> QueueHelper.ExchangeName.fromName("XyzInbound"));
        assertThrows(IllegalArgumentException.class, () -> QueueHelper.ExchangeName.fromName(null));
    }

    @Test
    void getNameIsStillTheEndeavourName() {
        //exchange events ("Manually pushed into EdsInbound exchange") are matched on by OpenEnvelope and routines
        assertEquals("EdsInbound", QueueHelper.ExchangeName.INBOUND.getName());
        assertEquals("EdsProtocol", QueueHelper.ExchangeName.PROTOCOL.getName());
    }

    @Test
    void findsVhpInboundInTheVhpMessagingApiConfig() throws Exception {
        //the api-configuration row from demo3 (app_id messaging-api), which posts to VhpInbound
        String xml = resource("/queueing/vhp-api-configuration.xml");

        PostMessageToExchangeConfig config = findExchangeConfig(xml, QueueHelper.ExchangeName.INBOUND);
        assertNotNull(config, "VhpInbound not matched for INBOUND");
        assertEquals("VhpInbound", config.getExchange(), "posts to the exchange the config names");

        assertNull(findExchangeConfig(xml, QueueHelper.ExchangeName.PROTOCOL), "no protocol exchange in that config");
    }

    @Test
    void stillFindsEdsInbound() throws Exception {
        String xml = resource("/queueing/vhp-api-configuration.xml").replace("VhpInbound", "EdsInbound");
        PostMessageToExchangeConfig config = findExchangeConfig(xml, QueueHelper.ExchangeName.INBOUND);
        assertNotNull(config);
        assertEquals("EdsInbound", config.getExchange());
    }

    private static PostMessageToExchangeConfig findExchangeConfig(String xml, QueueHelper.ExchangeName name) throws Exception {
        Method m = QueueHelper.class.getDeclaredMethod("findExchangeConfig", String.class, QueueHelper.ExchangeName.class);
        m.setAccessible(true);
        return (PostMessageToExchangeConfig) m.invoke(null, xml, name);
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = QueueHelperOverrideTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
