package com.vuvanquan.notifyhub.campaign.api;

import com.vuvanquan.notifyhub.campaign.application.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Component
public class ActorResolver {
    private final boolean local;
    public ActorResolver(Environment environment) {
        local = environment.acceptsProfiles(Profiles.of("local"));
        if (local && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Profiles local and prod must not be combined");
        }
    }

    public Actor resolve(HttpServletRequest request, Authentication authentication) {
        String tenant;
        String user;
        if (local) {
            tenant = request.getHeader("X-Tenant-Id");
            user = request.getHeader("X-User-Id");
        } else if (authentication instanceof JwtAuthenticationToken jwt) {
            tenant = jwt.getToken().getClaimAsString("tenant_id");
            user = jwt.getToken().getSubject();
        } else {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        try {
            return new Actor(UUID.fromString(tenant), UUID.fromString(user));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(local ? HttpStatus.BAD_REQUEST : HttpStatus.FORBIDDEN,
                    "A valid tenant UUID and user UUID are required");
        }
    }
}
