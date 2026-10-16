package com.vuvanquan.notifyhub.auth.api;

import com.vuvanquan.notifyhub.auth.application.*;
import com.vuvanquan.notifyhub.auth.application.AuthModels.Actor;
import com.vuvanquan.notifyhub.auth.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
public class InvitationController {
    private final MemberInvitations invitations;
    public InvitationController(MemberInvitations invitations) { this.invitations=invitations; }
    @GetMapping("/api/auth/users/invitations")
    List<MemberInvitations.Invitation> list(@AuthenticationPrincipal Jwt jwt) { return invitations.list(actor(jwt)); }
    @PostMapping(value="/api/auth/users/invitations",consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,UUID> invite(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Invite body) {
        return Map.of("id",invitations.invite(actor(jwt),body.email(),body.displayName(),body.role()));
    }
    @DeleteMapping("/api/auth/users/invitations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { invitations.revoke(actor(jwt),id); }
    @PostMapping(value="/api/auth/invitations/accept",consumes="application/json") @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(@Valid @RequestBody Accept body) { invitations.accept(body.token(),body.password()); }
    private Actor actor(Jwt jwt) { return new Actor(UUID.fromString(jwt.getClaimAsString("tenant_id")),UUID.fromString(jwt.getSubject())); }
    public record Invite(@NotBlank @Size(max=254) String email,@NotBlank @Size(max=100) String displayName,@NotNull Role role) {}
    public record Accept(@NotNull @Size(max=128) String token,@NotNull @Size(max=128) String password) {}
}
