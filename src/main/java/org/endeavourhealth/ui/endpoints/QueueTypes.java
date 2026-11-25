package org.endeavourhealth.ui.endpoints;

import java.util.Locale;

/**
 * VHP: works out a queue's type (inbound, protocol, transform, subscriber) from its RabbitMQ queue name.
 * The original screens only recognised the Endeavour names (EdsInbound-A, EdsProtocol-...), but the VHP queues
 * are named after the Vhp* exchanges (VhpInbound-A) or without a prefix (inbound-a). All three are accepted,
 * ignoring case.
 */
final class QueueTypes {

    static final String INBOUND = "inbound";
    static final String PROTOCOL = "protocol";
    static final String TRANSFORM = "transform";
    static final String SUBSCRIBER = "subscriber";

    private static final String[] TYPES = {INBOUND, PROTOCOL, TRANSFORM, SUBSCRIBER};
    private static final String[] PREFIXES = {"eds", "vhp", ""};

    private QueueTypes() {
    }

    /**
     * @return the queue type, or null if the name doesn't start with a known one
     */
    static String of(String queueName) {
        if (queueName == null) {
            return null;
        }
        String name = queueName.toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            for (String type : TYPES) {
                if (name.startsWith(prefix + type)) {
                    return type;
                }
            }
        }
        return null;
    }
}
