package com.vuvanquan.notifyhub.auth.api;

import com.vuvanquan.notifyhub.auth.application.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController @RequestMapping("/api/auth/mfa")
public class MfaController {
    private final MfaService mfa;
    public MfaController(MfaService mfa){this.mfa=mfa;}
    @GetMapping Map<String,Boolean> status(@AuthenticationPrincipal Jwt jwt){return Map.of("enabled",mfa.enabled(actor(jwt)));}
    @PostMapping(value="/enrollment",consumes="application/json")
    MfaService.Enrollment begin(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Password body){return mfa.begin(actor(jwt),body.password());}
    @PostMapping(value="/confirm",consumes="application/json")
    Map<String,List<String>> confirm(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Code body){return Map.of("recoveryCodes",mfa.confirm(actor(jwt),body.code()));}
    @PostMapping(value="/disable",consumes="application/json") @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Disable body){mfa.disable(actor(jwt),body.password(),body.code());}
    private AuthModels.Actor actor(Jwt jwt){return new AuthModels.Actor(UUID.fromString(jwt.getClaimAsString("tenant_id")),UUID.fromString(jwt.getSubject()));}
    public record Password(@NotNull @Size(max=128) String password){}
    public record Code(@NotNull @Size(max=43) String code){}
    public record Disable(@NotNull @Size(max=128) String password,@NotNull @Size(max=43) String code){}
}
