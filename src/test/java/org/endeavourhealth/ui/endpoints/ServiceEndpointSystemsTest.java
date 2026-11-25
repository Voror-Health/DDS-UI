package org.endeavourhealth.ui.endpoints;

import org.endeavourhealth.core.fhirStorage.ServiceInterfaceEndpoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A publisher saved with an interface that had no System (what "Add" in ServiceEdit.vue creates until one is picked)
 * made GET /api/service answer 500 for everyone (NPE in LibraryRepositoryHelper).
 */
class ServiceEndpointSystemsTest {

    private static ServiceInterfaceEndpoint endpoint(UUID systemUuid) {
        ServiceInterfaceEndpoint e = new ServiceInterfaceEndpoint();
        e.setSystemUuid(systemUuid);
        e.setEndpoint(ServiceInterfaceEndpoint.STATUS_BULK_PROCESSING);
        return e;
    }

    @Test
    void interfaceWithNoSystemIsShownNotThrown() throws Exception {
        // no DB access needed for a null system
        assertEquals(ServiceEndpoint.NO_SYSTEM_SET, ServiceEndpoint.findSoftwareDescForSystem(null));
    }

    @Test
    void saveRejectsInterfaceWithNoSystem() {
        assertNotNull(ServiceEndpoint.validateEndpointSystems(Arrays.asList(endpoint(UUID.randomUUID()), endpoint(null))));
    }

    @Test
    void saveAcceptsInterfacesWithSystemsOrNone() {
        assertNull(ServiceEndpoint.validateEndpointSystems(null));
        assertNull(ServiceEndpoint.validateEndpointSystems(new ArrayList<>()));
        List<ServiceInterfaceEndpoint> l = Arrays.asList(endpoint(UUID.randomUUID()), endpoint(UUID.randomUUID()));
        assertNull(ServiceEndpoint.validateEndpointSystems(l));
    }
}
