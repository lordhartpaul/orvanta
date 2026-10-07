package io.orvanta.pay;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.HttpConnector;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A connector that presents a client certificate to a service that requires one (mutual TLS), with certificates made here. */
class MutualTlsConnectorTest {

    private static X509Certificate certificate(String subject, KeyPair pair, KeyPair signer, String issuer) throws Exception {
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(System.nanoTime()),
                new Date(System.currentTimeMillis() - 60_000), new Date(System.currentTimeMillis() + 3_600_000), new X500Name(subject), pair.getPublic());
        boolean isCa = subject.equals(issuer);
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, new org.bouncycastle.asn1.x509.BasicConstraints(isCa));
        if (subject.equals("CN=localhost")) {
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
                    new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost")));
        }
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(signer.getPrivate())));
    }

    private static Path store(Path dir, String name, String password, KeyPair pair, X509Certificate cert, X509Certificate trusted) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        if (pair != null) {
            store.setKeyEntry("key", pair.getPrivate(), password.toCharArray(), new java.security.cert.Certificate[] {cert});
        }
        if (trusted != null) {
            store.setCertificateEntry("trusted", trusted);
        }
        Path path = dir.resolve(name);
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            store.store(out, password.toCharArray());
        }
        return path;
    }

    @Test
    void aServiceThatRequiresAClientCertificateIsCalledWithOurs() throws Exception {
        Path dir = Files.createTempDirectory("orvanta-mtls");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair ca = generator.generateKeyPair();
        KeyPair server = generator.generateKeyPair();
        KeyPair client = generator.generateKeyPair();
        X509Certificate caCert = certificate("CN=Test CA", ca, ca, "CN=Test CA");
        X509Certificate serverCert = certificate("CN=localhost", server, ca, "CN=Test CA");
        X509Certificate clientCert = certificate("CN=orvanta", client, ca, "CN=Test CA");
        String password = "store-password-for-the-test";
        Path serverStore = store(dir, "server.p12", password, server, serverCert, caCert);
        Path clientStore = store(dir, "client.p12", password, client, clientCert, null);
        Path trustStore = store(dir, "ca.p12", password, null, null, caCert);

        // the service: TLS with the server certificate, and a client certificate signed by the CA required
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(serverStore)) {
            ks.load(in, password.toCharArray());
        }
        keys.init(ks, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(ks);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        HttpsServer service = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                javax.net.ssl.SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                params.setSSLParameters(ssl);
            }
        });
        service.createContext("/accounts", x -> {
            String who;
            try {
                who = ((com.sun.net.httpserver.HttpsExchange) x).getSSLSession().getPeerPrincipal().getName();
            } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
                who = null;
            }
            byte[] body = (who == null ? "{\"error\":\"no client certificate\"}" : "{\"status\":\"ACTIVE\",\"caller\":\"" + who + "\"}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(who == null ? 401 : 200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        service.start();
        try {
            String url = "https://localhost:" + service.getAddress().getPort() + "/accounts";
            HttpConnector with = new HttpConnector("connectors.Accounts", Rec.of("url", url, "method", "GET", "retries", 0,
                    "tls", Rec.of("keyStore", clientStore.toString(), "keyStorePassword", password, "trustStore", trustStore.toString(), "trustStorePassword", password)));
            Rec answer = with.call(Rec.of("account", "1"));
            assertEquals("ACTIVE", answer.str("status"));
            assertEquals("CN=orvanta", answer.str("caller"), "the service saw our certificate");

            // without our certificate the service refuses the handshake; without the trust store the service's certificate is not trusted
            HttpConnector withoutKey = new HttpConnector("connectors.Accounts", Rec.of("url", url, "method", "GET", "retries", 0,
                    "tls", Rec.of("trustStore", trustStore.toString(), "trustStorePassword", password)));
            assertThrows(IOException.class, () -> withoutKey.call(Rec.of("account", "1")));
            HttpConnector untrusted = new HttpConnector("connectors.Accounts", Rec.of("url", url, "method", "GET", "retries", 0,
                    "tls", Rec.of("keyStore", clientStore.toString(), "keyStorePassword", password)));
            assertThrows(IOException.class, () -> untrusted.call(Rec.of("account", "1")));
            // the model compiler's checks
            assertTrue(assertThrows(IllegalArgumentException.class, () -> new HttpConnector("c", Rec.of("url", url, "tls", Rec.of("keyStore", clientStore.toString())))).getMessage().contains("keyStorePassword"));
            assertThrows(IllegalArgumentException.class, () -> new HttpConnector("c", Rec.of("url", url, "tls", Rec.of("keyStore", dir.resolve("missing.p12").toString(), "keyStorePassword", "x"))));
        } finally {
            service.stop(0);
        }
    }
}
