package org.voror.auth.casdoor;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TEST STAND-IN for the real Casdoor valve (same class name, so the webapp's real META-INF/context.xml
 * loads it in Tomcat11SmokeTest). Instead of validating a Casdoor token it takes the user from the
 * {@code X-Test-User} header and the roles from {@code X-Test-Roles}, then wraps the request the same way
 * the real valve does ({@code Request.setRequest} with a wrapper exposing the principal and roles).
 */
public class CasdoorAuthenticatorValve extends ValveBase {

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        // like the real valve: only /api/ and /machine-api/ are protected
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/") && !path.startsWith("/machine-api/")) {
            getNext().invoke(request, response);
            return;
        }
        String user = request.getHeader("X-Test-User");
        if (user == null) {
            response.sendError(401, "Missing or invalid Authorization header");
            return;
        }
        String rolesHeader = request.getHeader("X-Test-Roles");
        Set<String> roles = rolesHeader == null ? Set.of()
                : Arrays.stream(rolesHeader.split(",")).map(String::trim).collect(Collectors.toSet());
        Principal principal = () -> user;

        request.setRequest(new HttpServletRequestWrapper(request.getRequest()) {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public String getRemoteUser() {
                return user;
            }

            @Override
            public boolean isUserInRole(String role) {
                return roles.contains(role);
            }
        });
        getNext().invoke(request, response);
    }
}
