package com.vuvanquan.notifyhub.auth.control;

import org.springframework.http.HttpStatus;

public final class ControlFailure extends RuntimeException {
    private final HttpStatus status;
    private final long retryAfter;
    public ControlFailure(HttpStatus status, long retryAfter, String message) { super(message); this.status = status; this.retryAfter = retryAfter; }
    public HttpStatus status() { return status; }
    public long retryAfter() { return retryAfter; }
    public static ControlFailure unavailable() { return new ControlFailure(HttpStatus.SERVICE_UNAVAILABLE, 1, "Authentication controls unavailable"); }
}
