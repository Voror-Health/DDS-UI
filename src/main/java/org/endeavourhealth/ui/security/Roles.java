package org.endeavourhealth.ui.security;

/**
 * Casdoor role names used by the DDS UI.
 *
 * <p>Every {@code /api/*} call needs one of the webapp's {@code required_roles} (web.xml, checked by the
 * CasdoorAuthenticatorValve). Actions that the original marked {@code @RequiresAdmin} (and the new queue
 * server admin actions) additionally need {@link #ADMIN}.</p>
 *
 * <p>Note: in the original, {@code @RequiresAdmin} never took effect (Endeavour's CustomRolesAllowedFeature
 * only looked for annotations in a package that RequiresAdmin was not in). Here it is enforced by Jersey's
 * RolesAllowedDynamicFeature.</p>
 */
public final class Roles {
    /** may change config, re-run/re-queue exchanges, control queue readers and servers */
    public static final String ADMIN = "vhp_ui_admin";
    /** read-only and day-to-day use of the UI */
    public static final String USER = "vhp_ui_user";
    /** service accounts; only for POST /machine-api/exchangeAudit/postToExchange (see ServiceAccountScopeFilter) */
    public static final String SERVICE = "vhp_service";

    private Roles() {
    }
}
