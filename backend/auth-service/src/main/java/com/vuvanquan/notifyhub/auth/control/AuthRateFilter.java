package com.vuvanquan.notifyhub.auth.control;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthRateFilter extends OncePerRequestFilter {
    private final RedisControls controls;
    private final ControlProperties config;
    private final ClientAddress addresses;
    private final ObjectMapper json;
    public AuthRateFilter(RedisControls controls, ControlProperties config, ClientAddress addresses, ObjectMapper json) {
        this.controls = controls; this.config = config; this.addresses = addresses; this.json = json;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/auth/") && SetMethods.MUTATING.contains(request.getMethod()));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        try { controls.rate("request-ip", addresses.resolve(request), config.requests()); }
        catch (ControlFailure error) {
            response.setStatus(error.status().value());
            response.setContentType("application/problem+json");
            response.setHeader("Retry-After", Long.toString(error.retryAfter()));
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(error.status(), error.getMessage()));
            return;
        }
        chain.doFilter(request, response);
    }
    private static final class SetMethods {
        private static final java.util.Set<String> MUTATING = java.util.Set.of("POST", "PUT", "PATCH", "DELETE");
    }
}
