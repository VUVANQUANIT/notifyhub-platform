package com.vuvanquan.notifyhub.notification.worker;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/operations")
@ConditionalOnProperty(name="notification.operations.enabled",havingValue="true")
public class OperationsController {
    private final OperatorRedrive redrive;
    public OperationsController(OperatorRedrive redrive){this.redrive=redrive;}
    @GetMapping("/dead-letters") List<Map<String,Object>> failures(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page){return redrive.failures(tenant(jwt),page);}
    @GetMapping("/redrives") List<Map<String,Object>> history(@AuthenticationPrincipal Jwt jwt){return redrive.history(tenant(jwt));}
    @PostMapping(value="/dead-letters/{id}/redrive",consumes="application/json") @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,UUID> redrive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Request body){return Map.of("notificationId",redrive.redrive(tenant(jwt),UUID.fromString(jwt.getSubject()),id,body.requestId(),body.reason()));}
    private UUID tenant(Jwt jwt){try{UUID.fromString(jwt.getSubject());return UUID.fromString(jwt.getClaimAsString("tenant_id"));}catch(IllegalArgumentException|NullPointerException invalid){throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);}}
    public record Request(UUID requestId,String reason){}
}
