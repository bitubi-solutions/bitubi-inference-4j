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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF protection without sessions: a state-changing request that a browser marks cross-site, or whose Origin does
 * not match its Host, is refused. Requests without those headers (curl, SDKs) pass.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class OriginGuardFilter extends OncePerRequestFilter {
    private static final Set<String> CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (CHANGING.contains(request.getMethod()) && !sameOrigin(request)) {
            HostGuardFilter.reject(response, 403, "cross_origin_request", "cross-origin requests are not allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean sameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equals(site) || "same-site".equals(site)) return false;
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            String authority = uri.getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(request.getHeader("Host"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
