package com.vuvanquan.notifyhub.auth.api;

import com.vuvanquan.notifyhub.auth.application.AuthFailure;
import com.vuvanquan.notifyhub.auth.application.AuthModels.*;
import com.vuvanquan.notifyhub.auth.application.IdentityService;
import com.vuvanquan.notifyhub.auth.configuration.SigningKeys;
import com.vuvanquan.notifyhub.auth.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final IdentityService identities;
    private final SigningKeys keys;
    public AuthController(IdentityService identities, SigningKeys keys) { this.identities = identities; this.keys = keys; }

    @GetMapping("/.well-known/jwks.json") Map<String, Object> jwks() { return keys.publicJwks(); }
    @PostMapping(value = "/api/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    Tokens register(@Valid @RequestBody Register request) {
        return identities.register(request.tenantSlug(), request.tenantName(), request.email(), request.displayName(), request.password());
    }
    @PostMapping(value = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    Tokens login(@Valid @RequestBody Login request) { return identities.login(request.tenantSlug(), request.email(), request.password()); }
    @PostMapping(value = "/api/auth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    Tokens refresh(@Valid @RequestBody Refresh request) { return identities.refresh(request.refreshToken()); }
    @PostMapping(value = "/api/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody Refresh request) { identities.logout(request.refreshToken()); }
    @GetMapping("/api/auth/me") UserView me(@AuthenticationPrincipal Jwt jwt) { return identities.me(actor(jwt)); }
    @GetMapping("/api/auth/tenant") TenantView tenant(@AuthenticationPrincipal Jwt jwt) { return identities.tenant(actor(jwt)); }
    @GetMapping("/api/auth/users")
    UserPage users(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return identities.users(actor(jwt), page, size);
    }
    @PostMapping(value = "/api/auth/users", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    UserView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateUser request) {
        return identities.createUser(actor(jwt), request.email(), request.displayName(), request.password(), request.role());
    }
    @PutMapping(value = "/api/auth/users/{id}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    UserView role(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ChangeRole request) {
        return identities.changeRole(actor(jwt), id, request.role());
    }
    @PutMapping(value = "/api/auth/users/{id}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
    UserView enabled(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ChangeEnabled request) {
        return identities.changeEnabled(actor(jwt), id, request.enabled());
    }

    private Actor actor(Jwt jwt) {
        try { return new Actor(UUID.fromString(jwt.getClaimAsString("tenant_id")), UUID.fromString(jwt.getSubject())); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw AuthFailure.unauthorized(); }
    }

    public record Register(@NotBlank @Size(max=63) String tenantSlug, @NotBlank @Size(max=100) String tenantName,
            @NotBlank @Size(max=254) String email, @NotBlank @Size(max=100) String displayName, @NotNull @Size(max=128) String password) {}
    public record Login(@NotNull @Size(max=63) String tenantSlug, @NotNull @Size(max=254) String email, @NotNull @Size(max=128) String password) {}
    public record Refresh(@NotNull @Size(max=128) String refreshToken) {}
    public record CreateUser(@NotBlank @Size(max=254) String email, @NotBlank @Size(max=100) String displayName,
            @NotNull @Size(max=128) String password, @NotNull Role role) {}
    public record ChangeRole(@NotNull Role role) {}
    public record ChangeEnabled(@NotNull Boolean enabled) {}
}
