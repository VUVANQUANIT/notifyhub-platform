package com.vuvanquan.notifyhub.notification.worker;

import java.security.*;
import java.util.HexFormat;

final class PayloadIdentity {
    private PayloadIdentity() {}
    static String hash(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
