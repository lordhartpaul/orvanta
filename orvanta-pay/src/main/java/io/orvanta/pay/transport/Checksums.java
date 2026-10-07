package io.orvanta.pay.transport;

import io.orvanta.core.data.Rec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Checksum companion files for partners who do not use OpenPGP: next to every file goes a
 * {@code <file>.sha256} in the format of sha256sum ("&lt;hex&gt;  &lt;file name&gt;"), and a file that
 * arrives is taken only when its companion is there and agrees. Asked for with {@code checksum: sha256}
 * on a folder or sftp transport or destination.
 */
public final class Checksums {
    private Checksums() {
    }

    public static boolean wanted(Rec settings) {
        return settings != null && "sha256".equals(settings.str("checksum"));
    }

    public static boolean isCompanion(String fileName) {
        return fileName != null && fileName.endsWith(".sha256");
    }

    public static String companionName(String fileName) {
        return fileName + ".sha256";
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String companionText(String hex, String fileName) {
        return hex + "  " + fileName + "\n";
    }

    /** @return what is wrong with the file against its companion, or null when the checksum agrees */
    public static String verify(byte[] bytes, String companion) {
        String given = companion == null ? "" : companion.trim();
        int space = given.indexOf(' ');
        String hex = (space < 0 ? given : given.substring(0, space)).trim().toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{64}")) {
            return "the checksum file does not hold a SHA-256 checksum";
        }
        String actual = sha256Hex(bytes);
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), hex.getBytes(StandardCharsets.US_ASCII)) ? null
                : "the file's checksum " + actual + " is not the " + hex + " of its checksum file";
    }
}
