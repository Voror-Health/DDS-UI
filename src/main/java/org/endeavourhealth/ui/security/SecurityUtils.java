package org.endeavourhealth.ui.security;

import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;

/**
 * Jakarta / Casdoor replacement for {@code org.endeavourhealth.common.security.SecurityUtils}
 * (which is javax and Keycloak specific).
 *
 * <p>Authentication is performed by the {@code CasdoorAuthenticatorValve} installed in Tomcat. The valve
 * wraps the request so that {@link SecurityContext#getUserPrincipal()} returns the Casdoor principal and
 * {@link SecurityContext#isUserInRole(String)} checks the Casdoor roles from the token. This class only
 * depends on those standard APIs, so it does not need the valve jar at compile time.</p>
 */
public final class SecurityUtils {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityUtils.class);

    private SecurityUtils() {
    }

    /**
     * Returns the current user's ID as a UUID, as the Keycloak version did.
     *
     * <p>Keycloak principals were always the user's UUID. The Casdoor valve uses the Casdoor user name as
     * the principal name, which is usually not a UUID. In that case a stable name-based (type 3) UUID is
     * derived from it, so audit tables that expect a UUID still get a consistent value per user.</p>
     */
    public static UUID getCurrentUserId(SecurityContext sc) {
        String name = getCurrentUserName(sc);
        if (name == null) {
            LOG.warn("Something appears to be wrong with the security configuration, no user principal in SecurityContext.");
            return null;
        }
        return toUuid(name);
    }

    /** The raw principal name supplied by Casdoor (the Casdoor user name). */
    public static String getCurrentUserName(SecurityContext sc) {
        if (sc == null) {
            return null;
        }
        Principal principal = sc.getUserPrincipal();
        if (principal == null) {
            return null;
        }
        return principal.getName();
    }

    public static boolean hasRole(SecurityContext sc, String role) {
        return sc != null && sc.isUserInRole(role);
    }

    public static UUID toUuid(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        }
    }
}
