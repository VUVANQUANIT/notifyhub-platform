package com.vuvanquan.notifyhub.auth.domain;

import java.util.List;

public enum Role {
    ADMIN(List.of("campaigns:read", "campaigns:write", "reports:read", "users:read", "users:write")),
    OPERATOR(List.of("campaigns:read", "campaigns:write", "reports:read")),
    VIEWER(List.of("campaigns:read", "reports:read"));
    private final List<String> scopes;
    Role(List<String> scopes) { this.scopes = scopes; }
    public List<String> scopes() { return scopes; }
}
