package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.format.IsoXml;
import io.orvanta.core.format.Messages;
import io.orvanta.core.format.MtSpec;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import javax.xml.validation.Schema;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks a received message against what is installed for its type: an ISO 20022 message against
 * its XSD (imported through the Console, or a file in the schema directory), a SWIFT MT message
 * against the MessageSpec model of the active deployment. A type with nothing installed is not checked.
 */
public final class MessageChecks {

    public static final String SCHEMA_CHANGED = "orv.schema.changed";
    private static final long RELOAD_AFTER_MS = 60_000;
    private static final int MAX_PROBLEMS = 5;

    /** checkedAgainst is null when nothing is installed for the type. */
    public record Result(String checkedAgainst, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private record Loaded(Schema schema, String source, long at) {
    }

    private record Specs(String deploymentId, Map<String, MtSpec> byType) {
    }

    private final Platform platform;
    private final Map<String, Loaded> schemas = new ConcurrentHashMap<>();
    private volatile Specs specs;

    public MessageChecks(Platform platform) {
        this.platform = platform;
        // another process imported or removed a schema: forget ours so that the next message loads it again
        platform.bus.subscribeAll(SCHEMA_CHANGED, m -> schemas.remove(String.valueOf(m.str("messageType"))));
    }

    public Result check(Messages.Parsed message) {
        if (Messages.FLAT.equals(message.format())) {
            // reading it by its Format model is the check: a line that does not fit is refused before this point
            return new Result("Format " + message.messageType(), List.of());
        }
        if (Messages.ISO20022.equals(message.format())) {
            Loaded loaded = schema(message.messageType());
            if (loaded.schema() == null) {
                return new Result(null, List.of());
            }
            return new Result(loaded.source(), IsoXml.validate(message.raw(), loaded.schema(), MAX_PROBLEMS));
        }
        if (Messages.SWIFT_MT.equals(message.format())) {
            MtSpec spec = specs().byType().get(message.messageType());
            if (spec == null) {
                return new Result(null, List.of());
            }
            List<String> problems = spec.check(message.tree());
            return new Result("message specification of " + message.messageType(),
                    problems.size() > MAX_PROBLEMS ? problems.subList(0, MAX_PROBLEMS) : problems);
        }
        return new Result(null, List.of());
    }

    /** A message this platform built broke the rules of its type; it is not sent. */
    public static final class OutboundInvalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OutboundInvalid(String message) {
            super(message);
        }
    }

    /**
     * Checks a message the platform is about to send, the same way a received one is checked.
     * A channel can switch this off with {@code checkOutbound: false}.
     *
     * @throws OutboundInvalid naming the field, when the message breaks its schema or its field rules
     */
    public void requireValidOutbound(Rec channel, String payload) {
        if ("false".equals(String.valueOf(channel.get("checkOutbound")))) {
            return;
        }
        List<Messages.Parsed> messages;
        try {
            messages = Messages.parse(payload, channel, platform.deployments.registry()::config);
        } catch (RuntimeException e) {
            throw new OutboundInvalid("the message built for " + channel.str("name") + " cannot be read back: " + e.getMessage());
        }
        for (Messages.Parsed message : messages) {
            Result result = check(message);
            if (!result.ok()) {
                throw new OutboundInvalid(message.messageType() + " built for " + channel.str("name") + " is not valid: "
                        + String.join("; ", result.problems()) + " (checked against the " + result.checkedAgainst() + ")");
            }
        }
    }

    /** Called after a schema was stored or removed here; every process forgets its compiled copy. */
    public void schemaChanged(String messageType) {
        schemas.remove(messageType);
        platform.bus.publish(SCHEMA_CHANGED, Rec.of("messageType", messageType));
    }

    private Loaded schema(String messageType) {
        Loaded known = schemas.get(messageType);
        if (known != null && System.currentTimeMillis() - known.at() < RELOAD_AFTER_MS) {
            return known;
        }
        Loaded loaded = load(messageType);
        schemas.put(messageType, loaded);
        return loaded;
    }

    private Loaded load(String messageType) {
        long now = System.currentTimeMillis();
        // the type becomes a store key and part of a file name, so it must look like a message type and nothing else
        if (messageType == null || !messageType.matches("[A-Za-z]{4}\\.\\d{3}\\.\\d{3}\\.\\d{2}")) {
            return new Loaded(null, null, now);
        }
        Rec stored = platform.store.get(DocStore.SCHEMA, messageType);
        if (stored != null) {
            return new Loaded(IsoXml.compileSchema(stored.str("text")), "imported schema " + messageType + ".xsd", now);
        }
        // the file is looked up among those present, so the message type never becomes part of a path
        File[] present = platform.config.dir("schemas.dir", "schemas/iso20022").toFile().listFiles();
        for (File file : present == null ? new File[0] : present) {
            if (file.isFile() && file.getName().equals(messageType + ".xsd")) {
                try {
                    return new Loaded(IsoXml.compileSchema(Files.readString(file.toPath())), "schema file " + file.getName(), now);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("cannot read " + file + ": " + e.getMessage(), e);
                }
            }
        }
        return new Loaded(null, null, now);
    }

    private Specs specs() {
        String active = platform.deployments.activeId();
        Specs current = specs;
        if (current != null && current.deploymentId().equals(active)) {
            return current;
        }
        Map<String, MtSpec> byType = new HashMap<>();
        for (Rec def : platform.deployments.registry().configs("MessageSpec")) {
            MtSpec spec = MtSpec.compile(def);
            byType.put(spec.messageType(), spec);
        }
        current = new Specs(active, byType);
        specs = current;
        return current;
    }
}
