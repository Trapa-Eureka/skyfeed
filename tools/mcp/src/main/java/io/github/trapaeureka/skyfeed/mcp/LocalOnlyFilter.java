package io.github.trapaeureka.skyfeed.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses requests that a web page on another site could make a browser send here. The server has no
 * authentication and listens on the local machine, which a browser can reach too, so two things are checked:
 * the Host the request is addressed to must be one of the allowed names (this stops DNS rebinding, where a
 * foreign name is pointed at 127.0.0.1), and if the request carries an Origin, that origin must be an allowed
 * name as well. MCP clients that are not browsers send no Origin and are not affected.
 */
@Component
class LocalOnlyFilter extends OncePerRequestFilter {

    private final Set<String> allowedHosts;

    LocalOnlyFilter(@Value("${skyfeed.mcp.allowed-hosts}") Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowed(hostOf(request.getHeader("Host")))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "this server answers only to its local address");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !allowed(hostOfOrigin(origin))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "requests from this origin are not accepted");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String host) {
        return host != null && allowedHosts.contains(host.toLowerCase());
    }

    /** "localhost:8083" -> "localhost"; "[::1]:8083" -> "[::1]". */
    private static String hostOf(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? null : value.substring(0, end + 1);
        }
        int colon = value.lastIndexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private static String hostOfOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host == null ? null : (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
