package org.endeavourhealth.ui.framework;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import org.endeavourhealth.ui.security.SecurityUtils;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Jakarta port of {@code org.endeavourhealth.coreui.endpoints.AbstractEndpoint} (common/api), limited to
 * the members the UI endpoints use.
 */
public abstract class AbstractEndpoint {

    private static final String MDC_MARKER_UUID = "UserUuid";

    @Context
    protected SecurityContext securityContext;

    /**
     * used to set LogBack to include the user UUID in all logging
     */
    protected void setLogbackMarkers(SecurityContext sc) {
        UUID userUuid = SecurityUtils.getCurrentUserId(sc);
        if (userUuid != null) {
            MDC.put(MDC_MARKER_UUID, userUuid.toString());
        }
    }

    public static void clearLogbackMarkers() {
        MDC.remove(MDC_MARKER_UUID);
    }

    /**
     * Unchanged from the original: the Keycloak version never read an organisation from the token and
     * always returned this fixed UUID (see the TODO in common/api). Kept so audit rows stay identical.
     */
    protected UUID getOrganisationUuidFromToken(SecurityContext sc) throws Exception {
        return UUID.fromString("b6ff900d-8fcd-43d8-af37-5db3a87a6ef6");
    }
}
