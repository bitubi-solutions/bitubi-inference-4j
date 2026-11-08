/*
 * Copyright 2026 Bitubi Solutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package ao.bitubi.inference4j.server.security;

import ao.bitubi.inference4j.server.Bi4jServerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses requests whose Host is not a loopback name or listed in {@code bi4j.server.allowed-hosts}. A request
 * addressed to any other name that reaches a loopback-bound server is a DNS-rebinding attempt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostGuardFilter extends OncePerRequestFilter {
    private final Set<String> allowed = new HashSet<>(Set.of("localhost", "127.0.0.1", "[::1]"));

    public HostGuardFilter(Bi4jServerProperties properties) {
        properties.allowedHosts().forEach(h -> allowed.add(h.toLowerCase(Locale.ROOT)));
    }

    static String hostName(String header) {
        if (header == null) return "";
        String h = header.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end < 0 ? h : h.substring(0, end + 1);
        }
        int colon = h.indexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (allowed.contains(hostName(request.getHeader("Host")))) {
            chain.doFilter(request, response);
            return;
        }
        reject(response, 421, "host_not_allowed", "this Host is not allowed; add it to bi4j.server.allowed-hosts");
    }

    static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"" + code + "\"}}");
    }
}
