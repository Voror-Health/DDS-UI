package org.endeavourhealth.ui.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the Vue SPA shell for its history-mode routes, so deep links and refreshes work (adapted from
 * vhp-datasteward's filter).
 *
 * <p>Backend calls ({@code /api/*}, {@code /machine-api/*}, {@code /public/*}) and real static files (any path
 * whose last segment contains a '.') pass straight through. Everything else, e.g. {@code /serviceEdit/edit/<id>},
 * gets index.html with a {@code <base href="<context path>/">} added, so the relative asset URLs Vite writes
 * ({@code ./assets/...}) resolve from the webapp root and not from the deep link's directory.</p>
 */
public class SpaForwardFilter implements Filter {

    private volatile String indexHtml;
    private FilterConfig config;

    @Override
    public void init(FilterConfig filterConfig) {
        this.config = filterConfig;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        String ctx = http.getContextPath();
        String uri = http.getRequestURI();
        String path = (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) ? uri.substring(ctx.length()) : uri;
        if (path.isEmpty()) {
            path = "/";
        }

        if (isBackendOrStaticAsset(path)) {
            chain.doFilter(req, res);
            return;
        }

        String html = loadIndexHtml();
        if (html == null) {
            //SPA not built into this WAR: let the container answer (404)
            chain.doFilter(req, res);
            return;
        }

        HttpServletResponse response = (HttpServletResponse) res;
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        String base = "<base href=\"" + escape((ctx == null ? "" : ctx) + "/") + "\">";
        int head = html.indexOf("<head>");
        String out = head < 0 ? base + html : html.substring(0, head + 6) + base + html.substring(head + 6);
        response.getOutputStream().write(out.getBytes(StandardCharsets.UTF_8));
    }

    private String loadIndexHtml() throws IOException {
        String html = indexHtml;
        if (html == null) {
            try (InputStream in = config.getServletContext().getResourceAsStream("/index.html")) {
                if (in == null) {
                    return null;
                }
                html = indexHtml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return html;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static boolean isBackendOrStaticAsset(String path) {
        if (path.startsWith("/api/") || path.startsWith("/machine-api/") || path.startsWith("/public/")) {
            return true;
        }
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return lastSegment.contains(".");
    }
}
