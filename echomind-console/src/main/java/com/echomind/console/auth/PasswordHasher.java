package com.echomind.console.auth;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/** JDK PBKDF2 密码哈希工具，避免在第一阶段引入完整安全框架。 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        return "pbkdf2$" + ITERATIONS + "$"
            + Base64.getEncoder().encodeToString(salt) + "$"
            + Base64.getEncoder().encodeToString(hash);
    }

    public boolean matches(String rawPassword, String encoded) {
        if (rawPassword == null || encoded == null || encoded.isBlank()) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    private boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int diff = expected.length ^ actual.length;
        int max = Math.max(expected.length, actual.length);
        for (int i = 0; i < max; i++) {
            byte a = i < expected.length ? expected[i] : 0;
            byte b = i < actual.length ? actual[i] : 0;
            diff |= a ^ b;
        }
        return diff == 0;
    }
}
