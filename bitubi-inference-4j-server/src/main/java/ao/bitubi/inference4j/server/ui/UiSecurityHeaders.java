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
package ao.bitubi.inference4j.server.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Strict browser security headers on everything the UI serves. The JSON API does not need a page policy, and the
 * Swagger UI at /docs ships inline assets of its own, so those paths are left alone (a comment, not an oversight).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class UiSecurityHeaders extends OncePerRequestFilter {
    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
        + "font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    private static final List<String> NOT_UI = List.of("/v1/", "/healthz", "/v3/", "/docs", "/swagger-ui", "/error");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (NOT_UI.stream().noneMatch(path::startsWith)) {
            response.setHeader("Content-Security-Policy", CSP);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
        }
        chain.doFilter(request, response);
    }
}
