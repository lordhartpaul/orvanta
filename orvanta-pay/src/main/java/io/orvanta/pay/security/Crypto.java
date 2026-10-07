package io.orvanta.pay.security;

import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Password hashing (PBKDF2-HMAC-SHA256) and signed session tokens (JWT, HS256). */
public final class Crypto {

    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Crypto() {
    }

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS));
    }

    public static boolean verifyPassword(String password, String stored) {
        if (stored == null || password == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** A new secret for one-time codes: 160 random bits, in the Base32 form authenticator apps take. */
    public static String totpSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return out.toString();
    }

    /** The six-digit code of a secret for one 30-second step (RFC 6238 with HMAC-SHA1, what authenticator apps compute). */
    public static String totp(String secret, long step) {
        java.io.ByteArrayOutputStream key = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : secret.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                key.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA1"));
            byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether a code is the one of this moment, or of the step before or after (clocks differ a little).
     * @return the step the code belongs to, or -1 when it is none of the three
     */
    public static long totpStep(String secret, String code, long epochSeconds) {
        if (secret == null || code == null || !code.matches("[0-9]{6}")) {
            return -1;
        }
        long now = epochSeconds / 30;
        long found = -1;
        for (long step = now - 1; step <= now + 1; step++) {
            // every candidate is compared, in constant time, so the answer does not say which one matched
            if (java.security.MessageDigest.isEqual(totp(secret, step).getBytes(java.nio.charset.StandardCharsets.UTF_8), code.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                found = step;
            }
        }
        return found;
    }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    public static String signToken(Rec claims, String secret) {
        String head = URL.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = URL.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        return head + "." + body + "." + URL.encodeToString(hmac(head + "." + body, secret));
    }

    /** @return the claims, or null when the signature is wrong, the token is malformed or it has expired */
    public static Rec verifyToken(String token, String secret) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] given = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(given, hmac(parts[0] + "." + parts[1], secret))) {
                return null;
            }
            Rec claims = Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            Object exp = claims.get("exp");
            if (!(exp instanceof Number n) || n.longValue() < System.currentTimeMillis() / 1000) {
                return null;
            }
            return claims;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
