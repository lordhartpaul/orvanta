package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Mutual TLS for a connector: the client certificate the other system requires, and the certificates we
 * trust it by. Settings on an http or soap connector:
 *
 * <pre>
 * tls:
 *   keyStore: config/bank-client.p12            # our certificate and private key (PKCS12 or JKS)
 *   keyStorePassword: ${env.BANK_CLIENT_KEY_PASSWORD:-}
 *   trustStore: config/bank-ca.p12              # optional: only these certificates are trusted for the other side
 *   trustStorePassword: ${env.BANK_CA_PASSWORD:-}
 * </pre>
 */
public final class ClientTls {
    private ClientTls() {
    }

    /** The TLS settings of a model, checked, or null when it has none. */
    public static Rec of(Rec def) {
        if (!(def.get("tls") instanceof Map<?, ?> m)) {
            return null;
        }
        Rec tls = Rec.from(m);
        if (tls.str("keyStore") == null && tls.str("trustStore") == null) {
            throw new IllegalArgumentException("tls needs 'keyStore' (our client certificate) or 'trustStore' (the certificates we trust), or both");
        }
        if (tls.str("keyStore") != null && tls.str("keyStorePassword") == null) {
            throw new IllegalArgumentException("tls.keyStore needs 'keyStorePassword' (from the environment: ${env.NAME:-})");
        }
        return tls;
    }

    /** An HTTP client that presents our certificate and trusts what the settings say. */
    public static HttpClient client(Rec tls, Duration connectTimeout) throws IOException {
        try {
            KeyManagerFactory keys = null;
            if (tls.str("keyStore") != null) {
                keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(load(tls.str("keyStore"), tls.str("keyStorePassword")), tls.str("keyStorePassword").toCharArray());
            }
            TrustManagerFactory trust = null;
            if (tls.str("trustStore") != null) {
                trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(load(tls.str("trustStore"), tls.str("trustStorePassword") == null ? "" : tls.str("trustStorePassword")));
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys == null ? null : keys.getKeyManagers(), trust == null ? null : trust.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(context).connectTimeout(connectTimeout).build();
        } catch (GeneralSecurityException e) {
            throw new IOException("the TLS settings cannot be used: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(String path, String password) throws IOException, GeneralSecurityException {
        // a model names a file of the installation, never one above the working directory
        if (path.contains("..")) {
            throw new IOException("a key store path may not contain '..': " + path);
        }
        java.nio.file.Path file = java.nio.file.Path.of(path).normalize();
        KeyStore store = KeyStore.getInstance(path.toLowerCase(java.util.Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12");
        try (InputStream in = new FileInputStream(file.toFile())) {
            store.load(in, password == null ? new char[0] : password.toCharArray());
        }
        return store;
    }
}
