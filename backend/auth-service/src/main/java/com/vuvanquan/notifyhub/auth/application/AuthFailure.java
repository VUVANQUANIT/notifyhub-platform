package com.vuvanquan.notifyhub.auth.application;

import org.springframework.http.HttpStatus;

public final class AuthFailure extends RuntimeException {
    private final HttpStatus status;
    public AuthFailure(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static AuthFailure unauthorized() { return new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid credentials or session"); }
    public static AuthFailure forbidden() { return new AuthFailure(HttpStatus.FORBIDDEN, "Access denied"); }
}
