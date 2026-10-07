package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Receives wire messages from any source (REST upload, inbound folder), recognises format and
 * message type, finds the inbound channel and stores the message. Nothing is ever dropped:
 * a payload that cannot be parsed or has no channel is stored as REJECTED with the reason.
 */
public final class IngestService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);

    private static final java.util.Map<String, String> ID_PREFIX = java.util.Map.of("instruction", "ORVINS",
            "acknowledgement", "ORVACK", "cancellation", "ORVCXL", "resolution", "ORVRES", "return", "ORVRTN", "callback", "ORVCBK", "statement", "ORVSTM", "recall", "ORVRCL", "reversal", "ORVRVS");

    private final Platform platform;
    private final MessageChecks checks;
    private ScheduledExecutorService poller;

    public IngestService(Platform platform) {
        this.platform = platform;
        this.checks = platform.checks();
    }

    /** @return the stored message documents (without the raw payload), one per wire message in the payload */
    public List<Rec> receive(String raw, String fileName, String channelHint, String actor) {
        List<Rec> stored = new ArrayList<>();
        if (raw == null) {
            // refused before it was read (OpenPGP); the rejection is stored already
            return stored;
        }
        List<Messages.Parsed> parsed;
        try {
            // a flat file is read by the Format model of the channel it is for; the channel must be named for such a file
            Rec hinted = channelHint == null ? null : platform.deployments.registry().config(channelHint);
            parsed = Messages.parse(raw, hinted, platform.deployments.registry()::config);
        } catch (RuntimeException e) {
            stored.add(reject(raw, fileName, null, null, "PARSE_ERROR", e.getMessage(), actor));
            return stored;
        }
        for (Messages.Parsed p : parsed) {
            Rec channel = resolveChannel(p, channelHint);
            if (channel == null) {
                stored.add(reject(p.raw(), fileName, p.format(), p.messageType(), "NO_CHANNEL",
                        "no inbound channel accepts " + p.messageType() + (channelHint == null ? "" : " (requested channel " + channelHint + ")"), actor));
                continue;
            }
            if (channel.get("maxBytes") != null
                    && p.raw().getBytes(StandardCharsets.UTF_8).length > Ops.num(channel.get("maxBytes")).longValue()) {
                stored.add(reject(p.raw().substring(0, Math.min(p.raw().length(), 2000)), fileName, p.format(), p.messageType(), "SIZE_EXCEEDED",
                        "the message is larger than the " + Ops.str(channel.get("maxBytes")) + " bytes channel " + channel.str("name")
                                + " accepts; only its first 2000 characters were kept", actor));
                continue;
            }
            // against the XSD or the message specification installed for the type; every problem names the field
            MessageChecks.Result check;
            try {
                check = checks.check(p);
            } catch (RuntimeException e) {
                // a broken schema must not let unchecked messages through
                check = new MessageChecks.Result("check of " + p.messageType(), List.of("the message could not be checked: " + e.getMessage()));
            }
            if (!check.ok()) {
                stored.add(reject(p.raw(), fileName, p.format(), p.messageType(), Messages.ISO20022.equals(p.format()) ? "SCHEMA_INVALID" : "FORMAT_INVALID",
                        String.join("; ", check.problems()) + " (checked against the " + check.checkedAgainst() + ")", actor));
                continue;
            }
            String purpose = channel.str("purpose");
            Rec doc = Rec.of("id", platform.newId(ID_PREFIX.getOrDefault(purpose, "ORVMSG")), "purpose", purpose,
                    "format", p.format(), "messageType", p.messageType(), "channel", channel.str("name"), "fileName", fileName,
                    "status", Status.RECEIVED, "receivedAt", Platform.now(), "receivedBy", actor, "raw", p.raw());
            doc.set("checkedAgainst", check.checkedAgainst());
            platform.store.insert(DocStore.MESSAGE, doc);
            platform.event(doc.str("id"), "RECEIVED", p.messageType() + " received on " + channel.str("name"), null, actor);
            platform.bus.publish(Bus.inboundTopic(purpose), Rec.of("id", doc.str("id")));
            doc.remove("raw");
            stored.add(doc);
        }
        return stored;
    }

    public MessageChecks checks() {
        return checks;
    }

    private Rec reject(String raw, String fileName, String format, String messageType, String code, String reason, String actor) {
        Rec doc = Rec.of("id", platform.newId("ORVMSG"), "purpose", "unknown", "format", format, "messageType", messageType,
                "fileName", fileName, "status", Status.REJECTED, "reasonCode", code, "reasonText", reason,
                "receivedAt", Platform.now(), "receivedBy", actor, "raw", raw);
        platform.store.insert(DocStore.MESSAGE, doc);
        platform.event(doc.str("id"), "REJECTED", code + ": " + reason, null, actor);
        doc.remove("raw");
        return doc;
    }

    /**
     * The inbound channel for a message: its format and type must fit. Several channels may take the same
     * type; one that says which messages are its own ('match': a path in the message and the value it must
     * have) is asked first, so that for example instant payments find their channel and the rest go to the general one.
     */
    private Rec resolveChannel(Messages.Parsed p, String hint) {
        Rec general = null;
        for (Rec channel : platform.deployments.registry().configs("Channel")) {
            if (!"inbound".equals(channel.str("direction")) || !p.format().equals(channel.str("format"))) {
                continue;
            }
            if (hint != null && !hint.equals(channel.str("name"))) {
                continue;
            }
            // a channel for the Console form takes only what the form delivers, never a message that merely looks like one
            if (Boolean.TRUE.equals(channel.get("consoleOnly")) && !channel.str("name").equals(hint)) {
                continue;
            }
            boolean takesType = false;
            for (Object type : Ops.list(channel.get("messageTypes"))) {
                takesType = takesType || Messages.typeMatches(Ops.str(type), p.messageType());
            }
            if (!takesType) {
                continue;
            }
            if (!(channel.get("match") instanceof Rec match)) {
                general = general == null ? channel : general;
            } else if (String.valueOf(match.get("equals")).equals(Ops.str(p.tree().at(String.valueOf(match.str("path")))))) {
                return channel;
            }
        }
        return general;
    }

    /** Polls the inbound folder; handled files are moved to its archive sub-folder. */
    public void startFolderPolling() {
        Path inbound = platform.dataDir.resolve("inbound");
        try {
            prepareFolder(inbound);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create " + inbound, e);
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-inbound-folder");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> pollFolder(inbound, null, "inbound-folder"), 1, 1, TimeUnit.SECONDS);
        LOG.info("polling inbound folder {}", inbound);
    }

    public static void prepareFolder(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("archive"));
    }

    /**
     * Takes every file of the directory (names ending in .tmp are still being written and are left alone),
     * moves it to the archive sub-folder and receives it.
     * @param channelHint the channel the directory belongs to, or null to recognise the channel from the message
     */
    public void pollFolder(Path dir, String channelHint, String actor) {
        pollFolder(dir, channelHint, actor, null);
    }

    /** @param transport the transport setting of the folder, for OpenPGP; null for none */
    public void pollFolder(Path dir, String channelHint, String actor, Rec transport) {
        boolean sums = io.orvanta.pay.transport.Checksums.wanted(transport);
        Path archive = dir.resolve("archive");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> Files.isRegularFile(f) && !f.toString().endsWith(".tmp")).sorted().toList()) {
                String name = file.getFileName().toString();
                if (io.orvanta.pay.transport.Checksums.isCompanion(name)) {
                    // a checksum file is taken with its file (and is gone by now when the file came first in this round);
                    // one left behind (no file came, or checksums are not asked for) is archived after a while
                    if (!Files.exists(file)) {
                        continue;
                    }
                    if (!sums || !Files.exists(dir.resolve(name.substring(0, name.length() - ".sha256".length()))) && olderThanTenMinutes(file)) {
                        Files.move(file, archive.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                    continue;
                }
                byte[] bytes = Files.readAllBytes(file);
                if (sums) {
                    // the partner writes the checksum file next to the file: the file waits for it, ten minutes at most
                    Path companion = dir.resolve(io.orvanta.pay.transport.Checksums.companionName(name));
                    if (!Files.exists(companion)) {
                        if (!olderThanTenMinutes(file)) {
                            continue;
                        }
                        Files.move(file, archive.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                        reject("", name, null, null, "CHECKSUM_MISSING", "no " + companion.getFileName() + " arrived within ten minutes of the file", actor);
                        continue;
                    }
                    String problem = io.orvanta.pay.transport.Checksums.verify(bytes, Files.readString(companion, StandardCharsets.UTF_8));
                    Files.move(companion, archive.resolve(companion.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    if (problem != null) {
                        Files.move(file, archive.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                        reject("", name, null, null, "CHECKSUM_MISMATCH", problem, actor);
                        continue;
                    }
                }
                Files.move(file, archive.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                receive(unwrapped(bytes, name, channelHint, actor, transport), name, channelHint, actor);
            }
        } catch (Exception e) {
            LOG.error("polling {} failed", dir, e);
        }
    }

    private static boolean olderThanTenMinutes(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toInstant().isBefore(java.time.Instant.now().minusSeconds(600));
    }

    /** A file refused before it was read as a message (its checksum, its signature): stored as rejected so that it is seen. */
    public Rec rejectFile(String fileName, String code, String reason, String actor) {
        return reject("", fileName, null, null, code, reason, actor);
    }

    /**
     * The text of a received file: decrypted and its signature checked when the transport asks for OpenPGP.
     * A file that does not pass is stored as rejected (PGP_REFUSED) so that it is seen; its content is not.
     */
    public String unwrapped(byte[] bytes, String fileName, String channelHint, String actor, Rec transport) {
        if (!io.orvanta.pay.transport.Pgp.wanted(transport)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return io.orvanta.pay.transport.Pgp.text(io.orvanta.pay.transport.Pgp.open(transport, bytes));
        } catch (java.io.IOException e) {
            reject("", fileName, channelHint, null, "PGP_REFUSED", e.getMessage(), actor);
            return null;
        }
    }

    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }
}
