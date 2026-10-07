package io.orvanta.pay.transport;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

/**
 * One connection to an SFTP server, for the time of one piece of work.
 *
 * <pre>
 *   type: sftp
 *   host: files.partner.example
 *   port: 22                       # default 22
 *   username: orvanta
 *   password: ${env.PARTNER_SFTP_PASSWORD}     # or keyFile: /run/secrets/partner_key (a private key in OpenSSH or PEM form)
 *   hostKey: SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8    # the fingerprint of the server's key, required
 *   path: /outbox                  # the directory on the server
 * </pre>
 *
 * The server must show the key whose fingerprint the model names; any other key ends the connection.
 * There is no setting that accepts whatever key a server shows.
 */
public final class SftpLink implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;

    public SftpLink(Rec settings) throws IOException {
        String expected = settings.str("hostKey");
        if (expected == null || !expected.startsWith("SHA256:")) {
            throw new IOException("an sftp setting needs 'hostKey', the SHA256 fingerprint of the server's key");
        }
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((clientSession, remote, key) -> expected.equals(KeyUtils.getFingerPrint(key)));
        client.start();
        try {
            int port = settings.get("port") == null ? 22 : Ops.num(settings.get("port")).intValue();
            session = client.connect(settings.str("username"), settings.str("host"), port).verify(WAIT).getSession();
            if (settings.str("keyFile") != null) {
                for (KeyPair pair : new FileKeyPairProvider(Path.of(settings.str("keyFile"))).loadKeys(session)) {
                    session.addPublicKeyIdentity(pair);
                }
            }
            if (settings.str("password") != null && !settings.str("password").isEmpty()) {
                session.addPasswordIdentity(settings.str("password"));
            }
            session.auth().verify(WAIT);
            sftp = SftpClientFactory.instance().createSftpClient(session);
        } catch (IOException | RuntimeException e) {
            client.stop();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /** The names of the regular files in a directory, sorted; names starting with a dot or ending in .tmp or .part are still being written. */
    public List<String> files(String dir) throws IOException {
        List<String> names = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.readDir(dir)) {
            String name = entry.getFilename();
            if (entry.getAttributes().isRegularFile() && !name.startsWith(".") && !name.endsWith(".tmp") && !name.endsWith(".part")) {
                names.add(name);
            }
        }
        names.sort(null);
        return names;
    }

    public String read(String path, long maxBytes) throws IOException {
        return new String(readBytes(path, maxBytes), StandardCharsets.UTF_8);
    }

    public byte[] readBytes(String path, long maxBytes) throws IOException {
        if (sftp.stat(path).getSize() > maxBytes) {
            throw new IOException(path + " is larger than " + maxBytes + " bytes");
        }
        try (InputStream in = sftp.read(path)) {
            return in.readAllBytes();
        }
    }

    /** Writes under a temporary name and renames, so that a reader on the other side never sees half a file. */
    public void write(String dir, String name, String content) throws IOException {
        write(dir, name, content.getBytes(StandardCharsets.UTF_8));
    }

    public void write(String dir, String name, byte[] content) throws IOException {
        String tmp = dir + "/" + name + ".tmp";
        try (OutputStream out = sftp.write(tmp)) {
            out.write(content);
        }
        sftp.rename(tmp, dir + "/" + name);
    }

    public void move(String from, String to) throws IOException {
        sftp.rename(from, to);
    }

    public void remove(String path) throws IOException {
        sftp.remove(path);
    }

    @Override
    public void close() {
        try {
            sftp.close();
        } catch (IOException ignored) {
            // the session is closed next either way
        }
        try {
            session.close();
        } catch (IOException ignored) {
            // the client is stopped next either way
        }
        client.stop();
    }
}
