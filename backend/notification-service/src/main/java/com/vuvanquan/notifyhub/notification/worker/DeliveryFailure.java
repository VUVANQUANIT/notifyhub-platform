package com.vuvanquan.notifyhub.notification.worker;

/** Safe error code and retry classification; never persist provider text containing recipient data. */
public final class DeliveryFailure extends RuntimeException {
    private final boolean retryable;
    private final java.time.Duration retryAfter;
    public DeliveryFailure(String code, boolean retryable) {
        this(code,retryable,null);
    }
    public DeliveryFailure(String code, boolean retryable, java.time.Duration retryAfter) {
        super(code);
        if (!code.matches("[A-Za-z0-9_]{1,128}")) throw new IllegalArgumentException("Invalid failure code");
        this.retryable = retryable;
        if(retryAfter!=null && retryAfter.isNegative())throw new IllegalArgumentException("Retry delay cannot be negative");
        this.retryAfter=retryAfter;
    }
    public boolean retryable() { return retryable; }
    public java.time.Duration retryAfter() { return retryAfter; }
}
