package org.endeavourhealth.ui.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.endeavourhealth.ui.security.Roles;

import java.io.IOException;
import java.util.Set;

/**
 * Service accounts ({@code vhp_service}, e.g. the queue reader) are let in by the Casdoor valve so that the
 * queue reader's EMIS missing-code fix can call {@code POST /machine-api/exchangeAudit/postToExchange}, as it
 * called eds-ui. This filter keeps such tokens to that machine call: a caller with {@code vhp_service} but
 * neither UI role gets 403 everywhere else.
 */
public class ServiceAccountScopeFilter implements Filter {

    /** "METHOD path" (path within the webapp) open to service accounts */
    static final Set<String> SERVICE_CALLS = Set.of("POST /machine-api/exchangeAudit/postToExchange");

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        if (http.getUserPrincipal() != null
                && !http.isUserInRole(Roles.USER)
                && !http.isUserInRole(Roles.ADMIN)
                && http.isUserInRole(Roles.SERVICE)) {
            String path = http.getRequestURI().substring(http.getContextPath().length());
            if (!SERVICE_CALLS.contains(http.getMethod() + " " + path)) {
                ((HttpServletResponse) res).sendError(HttpServletResponse.SC_FORBIDDEN, "Service accounts may only use the machine API");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
