package com.vuvanquan.notifyhub.contracts;

import java.time.Instant;
import java.util.*;

/** Audit payload intentionally excludes credentials, email, destination and message content. */
public record IdentityAuditEvent(UUID eventId,int eventVersion,String action,UUID tenantId,UUID actorId,UUID subjectId,Instant occurredAt) {
    private static final Set<String> ACTIONS=Set.of("TENANT_REGISTERED","LOGIN_SUCCEEDED","REFRESH_ROTATED","LOGOUT","USER_CREATED","ROLE_CHANGED","ENABLED_CHANGED","PASSWORD_RECOVERED","INVITE_ISSUED","INVITE_REVOKED","INVITE_ACCEPTED","MFA_ENROLLMENT","MFA_ENABLED","MFA_DISABLED");
    public void validate(){if(eventId==null || eventVersion!=1 || !ACTIONS.contains(action==null?"":action) || tenantId==null || actorId==null || subjectId==null || occurredAt==null)throw new IllegalArgumentException("Invalid identity audit event");}
}
