package com.vuvanquan.notifyhub.auth.configuration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** The private key stays on disk; only the public JWK leaves this process. */
public final class SigningKeys {
    private final RSAKey key;

    public SigningKeys(Path path, boolean generateLocalKey) throws Exception {
        if (!Files.exists(path) && generateLocalKey) {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var pair = generator.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            // CREATE_NEW never overwrites a provisioned key. Provision production keys outside the application.
            try {
                if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.writeString(path, pem, StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
                } else {
                    Files.writeString(path, pem, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
                }
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                throw new IllegalStateException("Signing key was concurrently provisioned; restart after provisioning completes", raced);
            }
        }
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        byte[] bytes = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
        requireSecureModulus(privateKey.getModulus());
        var publicKey = (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        String kid = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
        key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(kid)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).build();
    }

    public RSAKey signingKey() { return key; }
    static void requireSecureModulus(java.math.BigInteger modulus) {
        if (modulus.bitLength() < 2048) throw new IllegalArgumentException("RSA key must be at least 2048 bits");
    }
    public RSAPublicKey publicKey() throws com.nimbusds.jose.JOSEException { return key.toRSAPublicKey(); }
    public java.util.Map<String, Object> publicJwks() { return new JWKSet(key.toPublicJWK()).toJSONObject(); }
}
