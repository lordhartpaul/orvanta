package io.orvanta.pay.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;

/** Rules that do not depend on state: what a password must look like, and where the server may call. */
public final class Policies {

    private static final Set<String> COMMON = Set.of("password1234", "123456789012", "qwertyuiop12", "administrator", "welcome12345",
            "letmein12345", "changeme1234", "passwordpassword", "orvantaorvanta", "000000000000", "111111111111", "abcdefghijkl");

    private Policies() {
    }

    /**
     * Length over composition: at least 12 characters, not built from the user name, not one of the
     * passwords everyone tries first, and not a single repeated character.
     * @return what is wrong, or null when the password is acceptable
     */
    public static String passwordProblem(String username, String password) {
        if (password == null || password.length() < 12) {
            return "password must be at least 12 characters";
        }
        if (password.length() > 200) {
            return "password must be at most 200 characters";
        }
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(java.util.Locale.ROOT))) {
            return "password must not contain the user name";
        }
        if (COMMON.contains(lower) || lower.chars().distinct().count() < 5) {
            return "password is too easy to guess";
        }
        return null;
    }

    /**
     * Where a Connector or an HTTP destination may point. Always refused: anything but http and https,
     * and the link-local range that cloud platforms use for their metadata service. When an allow-list
     * is configured (security.connectorHosts), the host must also be on it; an entry "*.example.com"
     * covers every host below example.com.
     * @return what is wrong, or null when the URL may be called
     */
    public static String urlProblem(String url, List<String> allowedHosts) {
        URI uri;
        try {
            uri = URI.create(url.replaceAll("\\{[^}]*}", "x"));
        } catch (RuntimeException e) {
            return "'" + url + "' is not a URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "only http and https may be called, not '" + scheme + "'";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "'" + url + "' has no host";
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        if (host.equals("metadata.google.internal") || host.startsWith("169.254.") || host.equals("[fd00:ec2::254]")) {
            return "the host " + host + " is a cloud metadata address and may not be called";
        }
        // an address written as a number can hide the same range
        if (host.matches("[0-9.]+") || host.startsWith("[")) {
            try {
                InetAddress address = InetAddress.getByName(host.replace("[", "").replace("]", ""));
                if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                    return "the address " + host + " may not be called";
                }
            } catch (java.net.UnknownHostException e) {
                return "'" + host + "' is not a valid address";
            }
        }
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return null;
        }
        for (String allowed : allowedHosts) {
            String a = allowed.trim().toLowerCase(java.util.Locale.ROOT);
            if (a.isEmpty()) {
                continue;
            }
            if (a.equals(host) || (a.startsWith("*.") && host.endsWith(a.substring(1)))) {
                return null;
            }
        }
        return "the host " + host + " is not on the list of hosts the server may call (security.connectorHosts)";
    }
}
