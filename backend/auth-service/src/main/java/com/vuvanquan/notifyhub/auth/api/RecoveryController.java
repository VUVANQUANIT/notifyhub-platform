package com.vuvanquan.notifyhub.auth.api;

import com.vuvanquan.notifyhub.auth.control.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password")
public class RecoveryController {
    private final PasswordRecovery recovery;
    private final ClientAddress addresses;
    public RecoveryController(PasswordRecovery recovery, ClientAddress addresses) { this.recovery = recovery; this.addresses = addresses; }
    @PostMapping(value="/forgot", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    PasswordRecovery.Requested forgot(@Valid @RequestBody Forgot request, HttpServletRequest http) {
        return recovery.request(request.tenantSlug(), request.email(), addresses.resolve(http));
    }
    @PostMapping(value="/reset", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody Reset request) { recovery.reset(request.challengeId(), request.code(), request.password()); }
    public record Forgot(@NotNull @Size(max=63) String tenantSlug, @NotNull @Size(max=254) String email) {}
    public record Reset(@NotNull UUID challengeId, @NotNull @Pattern(regexp="[0-9]{8}") String code, @NotNull @Size(max=128) String password) {}
}
