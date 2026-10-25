package com.vuvanquan.notifyhub.auth.application;

import com.vuvanquan.notifyhub.auth.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthModels {
    private AuthModels() {}
    public record Actor(UUID tenantId, UUID userId) {}
    public record UserView(UUID id, UUID tenantId, String email, String displayName, Role role, boolean enabled) {}
    public record TenantView(UUID id, String slug, String name) {}
    public record Tokens(String tokenType, String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt) {}
    public record UserPage(List<UserView> items, long total, int page, int size) {}
    record User(UUID id, UUID tenantId, String email, String displayName, String passwordHash, Role role, boolean enabled) {
        UserView view() { return new UserView(id, tenantId, email, displayName, role, enabled); }
    }
}
