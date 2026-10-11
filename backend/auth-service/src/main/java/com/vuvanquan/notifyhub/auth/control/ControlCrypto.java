package com.vuvanquan.notifyhub.auth.control;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Separate, provisioned secret for hashed control keys and encrypted recovery email payloads. */
public final class ControlCrypto {
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();
    public ControlCrypto(Path path, boolean local) throws Exception {
        if (!Files.exists(path) && local) {
            byte[] generated = new byte[32]; random.nextBytes(generated);
            String encoded = Base64.getEncoder().encodeToString(generated);
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(path, encoded, StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
            } else {
                Files.writeString(path, encoded, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            }
        }
        secret = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).strip());
        if (secret.length != 32) throw new IllegalArgumentException("Auth control secret requires 32 random bytes encoded as Base64");
    }
    public String digest(String domain, String value) { return HexFormat.of().formatHex(mac(domain, value)); }
    private byte[] mac(String domain, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal((domain + "\0" + value).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("Control digest unavailable", error); }
    }
    public String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(mac("email-key", "v1"), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("Email payload encryption failed", error); }
    }
    public String decrypt(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 29) throw new IllegalArgumentException("Invalid encrypted payload");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(mac("email-key", "v1"), "AES"), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            return new String(cipher.doFinal(bytes, 12, bytes.length-12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException error) { throw new IllegalStateException("Email payload authentication failed", error); }
    }
}
