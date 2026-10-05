package com.vuvanquan.notifyhub.reporting.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Component
public class TenantResolver {
    private final boolean local;
    public TenantResolver(Environment environment) {
        local = environment.acceptsProfiles(Profiles.of("local"));
        if (local && environment.acceptsProfiles(Profiles.of("prod", "authenticated-local"))) throw new IllegalStateException("local cannot be combined with a JWT profile");
    }
    public UUID resolve(HttpServletRequest request, Authentication auth) {
        try {
            if (local) {
                UUID.fromString(request.getHeader("X-User-Id"));
                return UUID.fromString(request.getHeader("X-Tenant-Id"));
            }
            if (auth instanceof JwtAuthenticationToken jwt) {
                UUID.fromString(jwt.getToken().getSubject());
                return UUID.fromString(jwt.getToken().getClaimAsString("tenant_id"));
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new ResponseStatusException(local ? HttpStatus.BAD_REQUEST : HttpStatus.FORBIDDEN, "Valid tenant and user UUIDs required");
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
}
