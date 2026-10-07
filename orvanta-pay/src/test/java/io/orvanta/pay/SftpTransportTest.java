package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.transport.SftpLink;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Files fetched from, and delivered to, a directory on an SFTP server (a real one, started for the test). */
class SftpTransportTest {

    private static final String USER = "orvanta";
    private static final String SECRET = "sftp-test-" + System.nanoTime();

    @TempDir
    Path temp;

    @Test
    void filesAreFetchedFromAnSftpServerAndReportsAreDeliveredToIt() throws Exception {
        Path source = Path.of("..", "workspace").toAbsolutePath().normalize();
        String sample = Files.readString(source.resolve("tests/messages/pain001-salaries.xml"));
        Path remote = Files.createDirectories(temp.resolve("remote"));
        Files.createDirectories(remote.resolve("outbox/done"));
        Files.createDirectories(remote.resolve("inbox"));
        Files.writeString(remote.resolve("outbox/salaries-a.xml"), sample.replace("SALARY-2026-10-001", "SFTP-A"));
        Files.writeString(remote.resolve("outbox/salaries-b.xml"), sample.replace("SALARY-2026-10-001", "SFTP-B"));
        Files.writeString(remote.resolve("outbox/still-writing.xml.tmp"), "<half");

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((username, password, session) -> USER.equals(username) && SECRET.equals(password));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(remote));
        sshd.start();
        String fingerprint = KeyUtils.getFingerPrint(sshd.getKeyPairProvider().loadKeys(null).iterator().next().getPublic());
        String settings = "host: 127.0.0.1\n    port: " + sshd.getPort() + "\n    username: " + USER + "\n    password: " + SECRET + "\n    hostKey: \"" + fingerprint + "\"\n";

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
        Path inbound = workspace.resolve("channels/CorporateIsoInbound.yaml");
        String text = Files.readString(inbound);
        assertTrue(text.contains("transport:\n"), "the channel model changed; adjust this test");
        Files.writeString(inbound, text.replace("transport:\n", "transport:\n  - type: sftp\n    " + settings + "    path: /outbox\n    archive: /outbox/done\n    intervalSeconds: 1\n"));
        Path status = workspace.resolve("channels/CustomerStatusOutbound.yaml");
        String statusText = Files.readString(status);
        String folder = "destination:\n  type: folder\n  path: outbound/customer-status\n  extension: xml";
        assertTrue(statusText.contains(folder), "the status channel model changed; adjust this test");
        Files.writeString(status, statusText.replace(folder, "destination:\n  type: sftp\n  " + settings.replace("\n    ", "\n  ") + "  path: /inbox\n  extension: xml"));

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        System.setProperty("ORVANTA_SIM_URL", "http://localhost:" + port + "/sim");
        DocStore store = new MemoryDocStore();
        OrvantaServer server = new OrvantaServer(Config.of(Rec.of(
                "units", "all",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "an-sftp-test-secret-that-is-long-enough", "seedFile", "no-such-directory/seed.yaml"),
                "workspace", Rec.of("dir", workspace.toString()),
                "data", Rec.of("dir", temp.resolve("data").toString()),
                "simulator", Rec.of("enabled", "true"))), store, new MemoryBus());
        try {
            server.start();
            long deadline = System.currentTimeMillis() + 30_000;
            while (store.count(DocStore.MESSAGE, Rec.of("purpose", "instruction")) < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            List<Rec> messages = store.find(DocStore.MESSAGE, Rec.of("purpose", "instruction"), "id", false, 10);
            assertEquals(2, messages.size(), "both finished files; the one still being written is left alone");
            assertEquals("sftp:127.0.0.1", messages.get(0).str("receivedBy"));
            assertEquals("channels.CorporateIsoInbound", messages.get(0).str("channel"));
            // fetched files are moved to the archive directory on the server (after the message is stored, so a moment later)
            long moved = System.currentTimeMillis() + 10_000;
            while (!(Files.exists(remote.resolve("outbox/done/salaries-a.xml")) && Files.exists(remote.resolve("outbox/done/salaries-b.xml"))) && System.currentTimeMillis() < moved) {
                Thread.sleep(100);
            }
            assertTrue(Files.exists(remote.resolve("outbox/done/salaries-a.xml")) && Files.exists(remote.resolve("outbox/done/salaries-b.xml")));
            assertTrue(Files.notExists(remote.resolve("outbox/salaries-a.xml")) && Files.exists(remote.resolve("outbox/still-writing.xml.tmp")));

            // the status report of an instruction is delivered to the server, complete, under its final name
            deadline = System.currentTimeMillis() + 60_000;
            List<Path> delivered = List.of();
            while (delivered.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(300);
                try (Stream<Path> files = Files.list(remote.resolve("inbox"))) {
                    delivered = files.filter(f -> f.toString().endsWith(".xml")).toList();
                }
            }
            assertTrue(!delivered.isEmpty(), "a status report arrived on the SFTP server");
            String report = Files.readString(delivered.get(0));
            assertTrue(report.contains("pain.002.001.10") && report.contains("</Document>") && (report.contains("SFTP-A") || report.contains("SFTP-B")), report);
            Rec sent = store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "status", "SENT"), "id", false, 1).get(0);
            assertTrue(sent.str("location").startsWith("sftp://127.0.0.1/inbox/"), sent.str("location"));

            // a server that shows another key than the one the model names is not talked to
            Rec wrongKey = Rec.of("host", "127.0.0.1", "port", sshd.getPort(), "username", USER, "password", SECRET,
                    "hostKey", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            assertThrows(IOException.class, () -> new SftpLink(wrongKey).close());
            assertThrows(IOException.class, () -> new SftpLink(Rec.of("host", "127.0.0.1", "port", sshd.getPort(), "username", USER, "password", SECRET)).close(),
                    "no fingerprint, no connection");
        } finally {
            server.stop();
            sshd.stop(true);
            System.clearProperty("ORVANTA_SIM_URL");
        }
    }
}
