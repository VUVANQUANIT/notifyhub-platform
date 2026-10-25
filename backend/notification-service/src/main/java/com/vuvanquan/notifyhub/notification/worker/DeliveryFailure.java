package com.vuvanquan.notifyhub.notification.worker;

/** Safe error code and retry classification; never persist provider text containing recipient data. */
public final class DeliveryFailure extends RuntimeException {
    private final boolean retryable;
    public DeliveryFailure(String code, boolean retryable) {
        super(code);
        if (!code.matches("[A-Za-z0-9_]{1,128}")) throw new IllegalArgumentException("Invalid failure code");
        this.retryable = retryable;
    }
    public boolean retryable() { return retryable; }
}
