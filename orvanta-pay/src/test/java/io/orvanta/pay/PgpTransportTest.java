package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.transport.Pgp;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Files to and from a partner are encrypted and signed with OpenPGP; what does not fit the keys is refused. */
class PgpTransportTest {

    @TempDir
    Path temp;

    /** A key pair as a partner or we would make it: a secret key ring file and a public key ring file, ASCII armoured. */
    private Path[] keys(String name, String passphrase) throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BC");
        generator.initialize(2048);
        PGPKeyPair pair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, generator.generateKeyPair(), new Date());
        PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1);
        PGPKeyRingGenerator rings = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, pair, name + " <" + name + "@example.test>", sha1, null, null,
                new JcaPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256),
                new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha1).setProvider("BC").build(passphrase.toCharArray()));
        Path secret = temp.resolve(name + "-private.asc");
        Path pub = temp.resolve(name + "-public.asc");
        try (OutputStream out = new ArmoredOutputStream(Files.newOutputStream(secret))) {
            rings.generateSecretKeyRing().encode(out);
        }
        try (OutputStream out = new ArmoredOutputStream(Files.newOutputStream(pub))) {
            rings.generatePublicKeyRing().encode(out);
        }
        return new Path[] {secret, pub};
    }

    @Test
    void filesAreEncryptedAndSignedOnTheWayOutAndCheckedOnTheWayIn() throws Exception {
        Path[] ours = keys("orvanta", "our-pass-phrase");
        Path[] partner = keys("partner", "their-pass-phrase");
        Path[] stranger = keys("stranger", "x");

        // what we send: encrypted to the partner, signed by us; the partner opens it with their key and checks our signature
        Rec outbound = Rec.of("pgp", Rec.of("encryptKeyFile", partner[1].toString(), "signKeyFile", ours[0].toString(), "passphrase", "our-pass-phrase", "armor", true));
        byte[] sent = Pgp.protect(outbound, "<Document>hello</Document>".getBytes(StandardCharsets.UTF_8), "ORVOUT1.xml");
        String armoured = new String(sent, StandardCharsets.UTF_8);
        assertTrue(armoured.startsWith("-----BEGIN PGP MESSAGE-----") && !armoured.contains("hello"), armoured);
        Rec partnerSide = Rec.of("pgp", Rec.of("decryptKeyFile", partner[0].toString(), "passphrase", "their-pass-phrase", "verifyKeyFile", ours[1].toString()));
        assertEquals("<Document>hello</Document>", Pgp.text(Pgp.open(partnerSide, sent)));

        // the stranger cannot open it, and a file signed by the stranger is not taken as ours
        Rec strangerSide = Rec.of("pgp", Rec.of("decryptKeyFile", stranger[0].toString(), "passphrase", "x"));
        assertTrue(assertThrows(IOException.class, () -> Pgp.open(strangerSide, sent)).getMessage().contains("not encrypted to any of our keys"));
        byte[] forged = Pgp.protect(Rec.of("pgp", Rec.of("encryptKeyFile", partner[1].toString(), "signKeyFile", stranger[0].toString(), "passphrase", "x")),
                "<Document>forged</Document>".getBytes(StandardCharsets.UTF_8), "x.xml");
        assertTrue(assertThrows(IOException.class, () -> Pgp.open(partnerSide, forged)).getMessage().contains("not the partner's"));
        // unsigned where a signature is expected, and plain where encryption is expected
        byte[] unsigned = Pgp.protect(Rec.of("pgp", Rec.of("encryptKeyFile", partner[1].toString())), "x".getBytes(StandardCharsets.UTF_8), "x");
        assertTrue(assertThrows(IOException.class, () -> Pgp.open(partnerSide, unsigned)).getMessage().contains("not signed"));
        assertTrue(assertThrows(IOException.class, () -> Pgp.open(partnerSide, "<Document/>".getBytes(StandardCharsets.UTF_8))).getMessage().contains("not encrypted"));

        // ---- through the platform: a folder channel that takes encrypted and signed files, and one that sends them ----
        Path source = Path.of("..", "workspace").toAbsolutePath().normalize();
        Path workspace = temp.resolve("workspace");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = workspace.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        String pgpIn = "    pgp:\n      decryptKeyFile: " + ours[0].toString().replace("\\", "/") + "\n      passphrase: our-pass-phrase\n      verifyKeyFile: "
                + partner[1].toString().replace("\\", "/") + "\n";
        Path inbound = workspace.resolve("channels/CorporateIsoInbound.yaml");
        String text = Files.readString(inbound);
        String folder = "  - type: folder\n    path: inbound/corporate-iso\n";
        assertTrue(text.contains(folder), "the channel model changed; adjust this test");
        Files.writeString(inbound, text.replace(folder, folder + pgpIn));
        Path status = workspace.resolve("channels/CustomerStatusOutbound.yaml");
        String statusText = Files.readString(status);
        String dest = "destination:\n  type: folder\n  path: outbound/customer-status\n  extension: xml";
        assertTrue(statusText.contains(dest), "the status channel model changed; adjust this test");
        Files.writeString(status, statusText.replace(dest, dest + "\n  pgp:\n    encryptKeyFile: " + partner[1].toString().replace("\\", "/")
                + "\n    signKeyFile: " + ours[0].toString().replace("\\", "/") + "\n    passphrase: our-pass-phrase\n    armor: true"));

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        System.setProperty("ORVANTA_SIM_URL", "http://localhost:" + port + "/sim");
        DocStore store = new MemoryDocStore();
        Path data = temp.resolve("data");
        OrvantaServer server = new OrvantaServer(Config.of(Rec.of(
                "units", "all",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "a-pgp-test-secret-that-is-long-enough", "seedFile", "no-such-directory/seed.yaml"),
                "workspace", Rec.of("dir", workspace.toString()),
                "data", Rec.of("dir", data.toString()),
                "simulator", Rec.of("enabled", "true"))), store, new MemoryBus());
        try {
            server.start();
            Path drop = data.resolve("inbound/corporate-iso");
            Files.createDirectories(drop);
            String sample = Files.readString(source.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "PGP-OK");
            Rec partnerSends = Rec.of("pgp", Rec.of("encryptKeyFile", ours[1].toString(), "signKeyFile", partner[0].toString(), "passphrase", "their-pass-phrase"));
            Files.write(drop.resolve("salaries.xml.gpg.tmp"), Pgp.protect(partnerSends, sample.getBytes(StandardCharsets.UTF_8), "salaries.xml"));
            Files.move(drop.resolve("salaries.xml.gpg.tmp"), drop.resolve("salaries.xml.gpg"));
            // a file that the partner did not sign, and one in the clear
            Files.write(drop.resolve("unsigned.xml.gpg"), Pgp.protect(Rec.of("pgp", Rec.of("encryptKeyFile", ours[1].toString())),
                    sample.replace("PGP-OK", "PGP-UNSIGNED").getBytes(StandardCharsets.UTF_8), "u.xml"));
            Files.writeString(drop.resolve("plain.xml"), sample.replace("PGP-OK", "PGP-PLAIN"));

            long deadline = System.currentTimeMillis() + 30_000;
            while (store.count(DocStore.MESSAGE, new Rec()) < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            List<Rec> messages = store.find(DocStore.MESSAGE, new Rec(), "id", false, 10);
            assertEquals(3, messages.size(), messages.toString());
            Rec good = messages.stream().filter(m -> "PGP-OK".equals(m.str("msgId"))).findFirst().orElseThrow();
            assertTrue(!"REJECTED".equals(good.str("status")), good.toString());
            for (Rec m : messages) {
                if (m != good) {
                    assertEquals("REJECTED", m.str("status"), m.toString());
                    assertEquals("PGP_REFUSED", m.str("reasonCode"), m.toString());
                    assertTrue(!String.valueOf(m.get("raw")).contains("PGP-"), "the content of a refused file is not kept");
                }
            }

            // the status report goes out encrypted and signed; the partner opens it
            deadline = System.currentTimeMillis() + 60_000;
            List<Path> out = List.of();
            while (out.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(300);
                Path dir = data.resolve("outbound/customer-status");
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> files = Files.list(dir)) {
                        out = files.filter(f -> f.toString().endsWith(".xml.asc")).toList();
                    }
                }
            }
            assertTrue(!out.isEmpty(), "an encrypted status report was written");
            String report = Pgp.text(Pgp.open(partnerSide, Files.readAllBytes(out.get(0))));
            assertTrue(report.contains("pain.002.001.10") && report.contains("PGP-OK"), report);
            assertTrue(!Files.readString(out.get(0)).contains("pain.002"), "nothing readable on disk");
        } finally {
            server.stop();
            System.clearProperty("ORVANTA_SIM_URL");
        }
    }
}
