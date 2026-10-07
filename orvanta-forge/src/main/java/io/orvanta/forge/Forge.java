package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Element;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.format.Messages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The build pipeline: validate models, generate one Java class per element, compile them
 * in memory and load them into a Registry. A build either succeeds completely or reports
 * every problem found; nothing is half deployed.
 */
public final class Forge {

    public record Build(Registry registry, List<Problem> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** What an inbound channel can carry; the engine has one service per purpose. */
    public static final List<String> INBOUND_PURPOSES = List.of("instruction", "acknowledgement", "cancellation", "resolution", "return", "callback", "statement", "recall", "reversal", "statusEnquiry", "deliveryNotification", "investigation", "requestToPay", "mandate");

    /** Engine data a DataSet may expose read-only. Users, roles, approvals and deployments are never exposed. */
    public static final List<String> ENGINE_SOURCES = List.of("engine.transactions", "engine.messages", "engine.batches", "engine.outbound");

    private Forge() {
    }

    public static Build buildWorkspace(Path dir) {
        List<Problem> problems = new ArrayList<>();
        List<ModelSource> sources = ModelSource.loadWorkspace(dir, problems);
        return build(sources, problems);
    }

    public static Build build(List<ModelSource> sources) {
        return build(sources, new ArrayList<>());
    }

    private static Build build(List<ModelSource> sources, List<Problem> problems) {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (ModelSource m : sources) {
            if (!Generator.CODE_KINDS.contains(m.kind()) && !Generator.CONFIG_KINDS.contains(m.kind())) {
                problems.add(new Problem(m.name(), "kind", "unknown kind '" + m.kind()
                        + "' (RuleSet, Mapping, DecisionTable, Flow, Channel, Connector, ReferenceTable, DataSet, Api, MessageSpec, Format, Schedule, TestCase)"));
            } else if (kinds.putIfAbsent(m.name(), m.kind()) != null) {
                problems.add(new Problem(m.name(), m.path(), "the name is used by more than one model"));
            }
        }

        Map<String, String> routes = new LinkedHashMap<>();
        for (ModelSource m : sources) {
            if ("Api".equals(m.kind()) && m.def().str("method") != null && m.def().str("path") != null) {
                String route = m.def().str("method").toUpperCase(java.util.Locale.ROOT) + " " + m.def().str("path").replaceAll("\\{[^}]*}", "{}");
                String other = routes.putIfAbsent(route, m.name());
                if (other != null) {
                    problems.add(new Problem(m.name(), "path", "the same method and path are already served by " + other));
                }
            }
        }

        Registry registry = new Registry();
        Generator generator = new Generator(kinds);
        Map<String, String> javaSources = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        for (ModelSource m : sources) {
            try {
                if (Generator.CONFIG_KINDS.contains(m.kind())) {
                    checkConfig(m, kinds);
                    Rec def = m.def().copy();
                    registry.registerConfig(def);
                }
                if (Generator.CODE_KINDS.contains(m.kind())) {
                    String cls = Generator.className(m);
                    String java = generator.generate(m);
                    javaSources.put(cls, java);
                    owners.put(cls, m.name());
                    registry.registerSource(m.name(), java);
                }
            } catch (ModelException e) {
                problems.add(new Problem(m.name(), e.where(), e.getMessage()));
            }
        }
        if (!problems.isEmpty()) {
            return new Build(null, problems);
        }

        if (javaSources.isEmpty()) {
            // only configuration models: nothing to compile
            return new Build(registry, problems);
        }
        MemoryCompiler.Result compiled = MemoryCompiler.compile(javaSources, owners);
        if (!compiled.problems().isEmpty()) {
            return new Build(null, compiled.problems());
        }
        for (String cls : javaSources.keySet()) {
            try {
                Class<?> type = compiled.loader().loadClass(Generator.PACKAGE + "." + cls);
                Element element = (Element) type.getDeclaredConstructor().newInstance();
                // a TestCase's checks live beside the element under test, under their own name
                registry.register(element);
            } catch (ReflectiveOperationException e) {
                problems.add(new Problem(owners.get(cls), "", "cannot load generated class: " + e));
            }
        }
        return problems.isEmpty() ? new Build(registry, problems) : new Build(null, problems);
    }

    private static void checkConfig(ModelSource m, Map<String, String> kinds) {
        Rec def = m.def();
        switch (m.kind()) {
            case "Channel" -> {
                String direction = need(def, "direction");
                if (!direction.equals("inbound") && !direction.equals("outbound")) {
                    throw new ModelException("direction", "must be inbound or outbound");
                }
                String format = need(def, "format");
                if (!List.of(Messages.ISO20022, Messages.SWIFT_MT, Messages.JSON, Messages.FLAT).contains(format)) {
                    throw new ModelException("format", "must be " + Messages.ISO20022 + ", " + Messages.SWIFT_MT + ", " + Messages.JSON + " or " + Messages.FLAT);
                }
                if (Messages.FLAT.equals(format)) {
                    // records of fixed width or delimited text: the Format model says how they are read and written
                    needRef(def, "formatSpec", "Format", kinds, true);
                } else if (def.get("formatSpec") != null) {
                    throw new ModelException("formatSpec", "only a channel of format flat names a Format model");
                }
                needRef(def, "mapping", "Mapping", kinds, true);
                needRef(def, "validation", "RuleSet", kinds, false);
                if (direction.equals("inbound")) {
                    String purpose = need(def, "purpose");
                    if (!INBOUND_PURPOSES.contains(purpose)) {
                        throw new ModelException("purpose", "must be one of " + INBOUND_PURPOSES);
                    }
                    needRef(def, "statusReport", "Channel", kinds, false);
                    needRef(def, "processingFlow", "Flow", kinds, false);
                    needRef(def, "returnChannel", "Channel", kinds, false);
                    needRef(def, "answerChannel", "Channel", kinds, false);
                    needRef(def, "notificationChannel", "Channel", kinds, false);
                    needRef(def, "chargeClaimChannel", "Channel", kinds, false);
                    needRef(def, "confirmationChannel", "Channel", kinds, false);
                    needRef(def, "refundChannel", "Channel", kinds, false);
                    needRef(def, "infoChannel", "Channel", kinds, false);
                    if (def.get("match") != null && (!(def.get("match") instanceof Rec match) || match.str("path") == null || match.get("equals") == null)) {
                        throw new ModelException("match", "'match' needs a path in the message and the value it must equal");
                    }
                    int n = 0;
                    for (Object t : io.orvanta.core.expr.Ops.list(def.get("transport"))) {
                        String at = "transport[" + n++ + "]";
                        if (!(t instanceof Rec transport)) {
                            throw new ModelException(at, "a transport is a map with a type");
                        }
                        pgpSettings(transport, at, false);
                        checksumSetting(transport, at);
                        receiptSetting(transport, at);
                        switch (String.valueOf(transport.str("type"))) {
                            case "folder" -> {
                                if (transport.str("path") == null || transport.str("path").contains("..")) {
                                    throw new ModelException(at, "a folder transport needs a 'path' inside the data directory");
                                }
                            }
                            case "rabbitmq" -> {
                                if (transport.str("queue") == null) {
                                    throw new ModelException(at, "a rabbitmq transport needs a 'queue'");
                                }
                            }
                            case "rest" -> {
                            }
                            case "http" -> {
                                if (transport.str("url") == null || transport.str("url").isBlank()) {
                                    throw new ModelException(at, "an http transport needs the 'url' to fetch messages from");
                                }
                                if (transport.get("acknowledge") != null && (!(transport.get("acknowledge") instanceof Rec ack) || ack.str("url") == null
                                        || !ack.str("url").contains("{id}"))) {
                                    throw new ModelException(at, "'acknowledge' needs a 'url' with {id} where the id of the message goes");
                                }
                            }
                            case "sftp" -> sftpSettings(transport, at);
                            case "kafka" -> kafkaSettings(transport, at);
                            case "ibmmq" -> ibmMqSettings(transport, at);
                            default -> throw new ModelException(at, "transport type must be folder, rabbitmq, rest, http, sftp, kafka or ibmmq");
                        }
                    }
                    if (!(def.get("messageTypes") instanceof List<?> l) || l.isEmpty()) {
                        throw new ModelException("messageTypes", "an inbound channel lists the message types it accepts");
                    }
                } else {
                    if (!(def.get("destination") instanceof Map<?, ?>)) {
                        throw new ModelException("destination", "an outbound channel needs a destination");
                    }
                    needRef(def, "cancellationChannel", "Channel", kinds, false);
                    needRef(def, "enquiryChannel", "Channel", kinds, false);
                    if (def.get("sentStatus") != null && !"RETURNED".equals(def.str("sentStatus"))) {
                        throw new ModelException("sentStatus", "the only status a channel can give its payments when they are sent is RETURNED, for a channel that sends payments back");
                    }
                    Rec destination = def.rec("destination");
                    pgpSettings(destination, "destination", true);
                    checksumSetting(destination, "destination");
                    if (def.get("allowedPurposeCodes") != null) {
                        for (Object code : io.orvanta.core.expr.Ops.list(def.get("allowedPurposeCodes"))) {
                            if (!String.valueOf(code).matches("[A-Z]{4}")) {
                                throw new ModelException("allowedPurposeCodes", "'" + code + "' is not a category purpose code (four capital letters)");
                            }
                        }
                    }
                    for (String bound : List.of("minAmount", "maxAmount")) {
                        if (def.get(bound) != null && !(def.get(bound) instanceof Number)) {
                            throw new ModelException(bound, "'" + def.get(bound) + "' is not an amount");
                        }
                    }
                    if (def.get("minAmount") instanceof Number min && def.get("maxAmount") instanceof Number max
                            && io.orvanta.core.expr.Ops.gt(min, max)) {
                        throw new ModelException("maxAmount", "maxAmount " + max + " is below minAmount " + min);
                    }
                    if (def.at("bulking.groupBy") != null) {
                        for (Object field : io.orvanta.core.expr.Ops.list(def.at("bulking.groupBy"))) {
                            if (!String.valueOf(field).matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                                throw new ModelException("bulking.groupBy", "'" + field + "' is not a field path of the payment (for example instructionId or debtor.account)");
                            }
                        }
                    }
                    if (def.str("messageIdTemplate") != null) {
                        try {
                            io.orvanta.core.expr.Ids.render(def.str("messageIdTemplate"), 1, def.str("name"), java.time.ZonedDateTime.now());
                        } catch (IllegalArgumentException e) {
                            throw new ModelException("messageIdTemplate", e.getMessage());
                        }
                    }
                    switch (String.valueOf(destination.str("type"))) {
                        case "folder" -> {
                            if (destination.str("path") == null || destination.str("path").contains("..")) {
                                throw new ModelException("destination", "a folder destination needs a 'path' inside the data directory");
                            }
                        }
                        case "http" -> {
                            if (destination.str("url") == null) {
                                throw new ModelException("destination", "an http destination needs a 'url'");
                            }
                        }
                        case "sftp" -> sftpSettings(destination, "destination");
                        case "kafka" -> kafkaSettings(destination, "destination");
                        case "ibmmq" -> ibmMqSettings(destination, "destination");
                        case "rabbitmq" -> {
                            if (destination.str("queue") == null && destination.str("routingKey") == null) {
                                throw new ModelException("destination", "a rabbitmq destination needs a 'queue', or an 'exchange' with a 'routingKey'");
                            }
                        }
                        default -> throw new ModelException("destination", "destination type must be folder, http, rabbitmq, sftp, kafka or ibmmq");
                    }
                }
            }
            case "DataSet" -> {
                need(def, "key");
                if (def.get("console") != null) {
                    // how the Console shows the rows and through which APIs it asks for changes
                    if (!(def.get("console") instanceof Rec console) || console.str("title") == null || !(console.get("columns") instanceof List<?>)) {
                        throw new ModelException("console", "'console' needs a title and a list of columns");
                    }
                    for (String action : List.of("write", "remove")) {
                        if (console.get(action) != null && !"Api".equals(kinds.get(String.valueOf(console.at(action + ".api"))))) {
                            throw new ModelException("console." + action, "'api' must name an Api model of this workspace");
                        }
                    }
                }
                String collection = def.str("collection");
                String source = def.str("source");
                String table = def.str("table");
                int given = (collection != null ? 1 : 0) + (source != null ? 1 : 0) + (table != null ? 1 : 0);
                if (given != 1) {
                    throw new ModelException("collection", "give exactly one of 'collection' (own data in the document store), "
                            + "'table' with 'datasource' (a table of a relational database) or 'source' (a read-only view of engine data)");
                }
                if (table != null) {
                    if (!table.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
                        throw new ModelException("table", "must be a plain SQL name: letters, digits and underscores");
                    }
                    if (!need(def, "datasource").matches("[a-z][a-z0-9_-]{0,40}")) {
                        throw new ModelException("datasource", "names a data source of the configuration: lower case letters, digits, underscores and hyphens");
                    }
                    if (!need(def, "key").matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
                        throw new ModelException("key", "the key of a table is one of its columns");
                    }
                }
                if (collection != null && !collection.matches("[a-z][a-z0-9_]{1,40}")) {
                    throw new ModelException("collection", "must be lower case letters, digits and underscores");
                }
                if (source != null && !ENGINE_SOURCES.contains(source)) {
                    throw new ModelException("source", "must be one of " + ENGINE_SOURCES);
                }
            }
            case "Api" -> {
                String method = need(def, "method").toUpperCase(java.util.Locale.ROOT);
                if (!List.of("GET", "POST", "PUT", "DELETE").contains(method)) {
                    throw new ModelException("method", "must be GET, POST, PUT or DELETE");
                }
                if (!need(def, "path").matches("(/([A-Za-z0-9_-]+|\\{[A-Za-z][A-Za-z0-9]*}))+")) {
                    throw new ModelException("path", "must look like /payments/{id}/status");
                }
                need(def, "permission");
                if (def.get("approval") != null && !(def.get("approval") instanceof Boolean)) {
                    throw new ModelException("approval", "'approval' is true or false: whether a call that changes data needs a second person");
                }
                if (def.get("soap") != null) {
                    if (!(def.get("soap") instanceof Rec soap) || soap.str("operation") == null || !soap.str("operation").matches("[A-Za-z][A-Za-z0-9_]{0,60}")) {
                        throw new ModelException("soap", "'soap' is {operation: Name}: the element in a SOAP Body that calls this API (letters, digits and underscores)");
                    }
                }
                String target = need(def, "target");
                if (!Generator.CODE_KINDS.contains(String.valueOf(kinds.get(target))) || "TestCase".equals(kinds.get(target))) {
                    throw new ModelException("target", "'" + target + "' is not a Flow, Mapping, RuleSet or DecisionTable in this workspace");
                }
            }
            case "ReferenceTable" -> {
                String key = need(def, "key");
                if (!(def.get("rows") instanceof List<?> rows)) {
                    throw new ModelException("rows", "a reference table needs a list of rows");
                }
                java.util.Set<String> seen = new java.util.HashSet<>();
                int n = 0;
                for (Object row : rows) {
                    if (!(row instanceof Rec r) || r.str(key) == null) {
                        throw new ModelException("rows[" + n + "]", "every row needs the key field '" + key + "'");
                    }
                    if (!seen.add(r.str(key))) {
                        throw new ModelException("rows[" + n + "]", "key '" + r.str(key) + "' appears more than once");
                    }
                    n++;
                }
            }
            case "Schedule" -> {
                String cron = need(def, "cron");
                try {
                    io.orvanta.core.expr.Cron.parse(cron);
                } catch (IllegalArgumentException e) {
                    throw new ModelException("cron", e.getMessage());
                }
                if (def.str("timezone") != null) {
                    try {
                        java.time.ZoneId.of(def.str("timezone"));
                    } catch (java.time.DateTimeException e) {
                        throw new ModelException("timezone", "'" + def.str("timezone") + "' is not a time zone (for example Africa/Johannesburg)");
                    }
                }
                String job = need(def, "job");
                if (!List.of("closeDay", "statement", "accountReports", "flow").contains(job)) {
                    throw new ModelException("job", "must be closeDay, statement, accountReports or flow");
                }
                if (job.equals("flow")) {
                    needRef(def, "flow", "Flow", kinds, true);
                }
                if (job.equals("statement")) {
                    needRef(def, "channel", "Channel", kinds, true);
                    need(def, "account");
                }
                if (def.str("day") != null && !List.of("today", "yesterday").contains(def.str("day"))) {
                    throw new ModelException("day", "is today or yesterday");
                }
            }
            case "Format" -> {
                try {
                    io.orvanta.core.format.FlatFile.compile(def);
                } catch (IllegalArgumentException e) {
                    String message = e.getMessage();
                    int colon = message.indexOf(':');
                    throw new ModelException(colon > 0 ? message.substring(0, colon) : "records", colon > 0 ? message.substring(colon + 1).trim() : message);
                }
            }
            case "MessageSpec" -> {
                if (!"swift.mt".equals(need(def, "format"))) {
                    throw new ModelException("format", "a message specification is for format swift.mt; ISO 20022 messages are checked against their XSD");
                }
                try {
                    io.orvanta.core.format.MtSpec.compile(def);
                } catch (IllegalArgumentException e) {
                    throw new ModelException("fields", e.getMessage());
                }
            }
            case "Connector" -> {
                String type = need(def, "type");
                if (type.equals("http")) {
                    need(def, "url");
                } else if (type.equals("soap")) {
                    need(def, "url");
                    need(def, "operation");
                    need(def, "namespace");
                } else if (!type.equals("mock")) {
                    throw new ModelException("type", "must be http, soap or mock");
                }
                if (def.get("auth") != null) {
                    try {
                        io.orvanta.core.flow.HttpConnector.headers(def, false);
                    } catch (IllegalArgumentException e) {
                        throw new ModelException("auth", e.getMessage());
                    }
                }
                if (def.get("circuitBreaker") != null) {
                    try {
                        io.orvanta.core.flow.CircuitBreaker.of(def.str("name"), def);
                    } catch (IllegalArgumentException e) {
                        throw new ModelException("circuitBreaker", e.getMessage());
                    }
                }
                if (def.get("tls") != null) {
                    try {
                        io.orvanta.core.flow.ClientTls.of(def);
                    } catch (IllegalArgumentException e) {
                        throw new ModelException("tls", e.getMessage());
                    }
                }
            }
            default -> {
            }
        }
    }

    /** receipt: {queue} (rabbitmq, ibmmq) or {topic} (kafka) asks for a receipt per message stored, back to the sender. */
    private static void receiptSetting(Rec transport, String at) {
        if (transport.get("receipt") == null) {
            return;
        }
        if (!(transport.get("receipt") instanceof Rec receipt)) {
            throw new ModelException(at, "receipt is a map: {queue: ...} on rabbitmq and ibmmq, {topic: ...} on kafka");
        }
        String type = String.valueOf(transport.str("type"));
        if ((type.equals("rabbitmq") || type.equals("ibmmq")) && receipt.str("queue") == null) {
            throw new ModelException(at, "a receipt on a " + type + " transport needs 'queue'");
        }
        if (type.equals("kafka") && receipt.str("topic") == null) {
            throw new ModelException(at, "a receipt on a kafka transport needs 'topic'");
        }
        if (!List.of("rabbitmq", "ibmmq", "kafka").contains(type)) {
            throw new ModelException(at, "a receipt goes back on a rabbitmq, ibmmq or kafka transport");
        }
    }

    /** checksum: sha256 asks for a checksum companion file next to every file of a folder or sftp transport or destination. */
    private static void checksumSetting(Rec settings, String at) {
        if (settings.get("checksum") == null) {
            return;
        }
        if (!"sha256".equals(settings.str("checksum"))) {
            throw new ModelException(at, "checksum is sha256, or left out");
        }
        if (!List.of("folder", "sftp").contains(String.valueOf(settings.str("type")))) {
            throw new ModelException(at, "a checksum companion file goes with a folder or sftp transport or destination");
        }
    }

    private static String need(Rec def, String key) {
        String v = def.str(key);
        if (v == null || v.isBlank()) {
            throw new ModelException(key, "'" + key + "' is required");
        }
        return v;
    }

    private static void needRef(Rec def, String key, String kind, Map<String, String> kinds, boolean required) {
        String ref = def.str(key);
        if (ref == null) {
            if (required) {
                throw new ModelException(key, "'" + key + "' is required");
            }
            return;
        }
        if (!kind.equals(kinds.get(ref))) {
            throw new ModelException(key, kind + " '" + ref + "' does not exist in this workspace");
        }
    }

    /** An OpenPGP setting names key files for what it is to do; a setting that names none does nothing and is a mistake. */
    private static void pgpSettings(Rec settings, String at, boolean outbound) {
        if (!(settings.get("pgp") instanceof Rec pgp)) {
            return;
        }
        boolean named = outbound ? pgp.str("encryptKeyFile") != null || pgp.str("signKeyFile") != null
                : pgp.str("decryptKeyFile") != null || pgp.str("verifyKeyFile") != null;
        if (!named) {
            throw new ModelException(at + ".pgp", outbound ? "names 'encryptKeyFile', 'signKeyFile' or both" : "names 'decryptKeyFile', 'verifyKeyFile' or both");
        }
    }

    /** What an IBM MQ transport or destination must say: the queue manager, where it is, the channel into it, and the queue. */
    private static void ibmMqSettings(Rec settings, String at) {
        for (String field : List.of("host", "channel", "queueManager", "queue")) {
            if (settings.str(field) == null || settings.str(field).isBlank()) {
                throw new ModelException(at, "an ibmmq setting needs '" + field + "'");
            }
        }
        if (settings.get("port") != null && !String.valueOf(settings.get("port")).matches("[0-9]{1,5}")) {
            throw new ModelException(at, "'port' of an ibmmq setting is a number");
        }
    }

    private static void kafkaSettings(Rec settings, String at) {
        for (String field : List.of("bootstrapServers", "topic")) {
            if (settings.str(field) == null || settings.str(field).isBlank()) {
                throw new ModelException(at, "a kafka setting needs '" + field + "'");
            }
        }
    }

    /** What an SFTP transport or destination must say: where, as whom, and the key the server has to show. */
    private static void sftpSettings(Rec settings, String at) {
        for (String field : List.of("host", "username", "path")) {
            if (settings.str(field) == null || settings.str(field).isBlank()) {
                throw new ModelException(at, "an sftp setting needs '" + field + "'");
            }
        }
        if (settings.str("password") == null && settings.str("keyFile") == null) {
            throw new ModelException(at, "an sftp setting needs a 'password' or a 'keyFile'");
        }
        if (settings.str("hostKey") == null || !settings.str("hostKey").startsWith("SHA256:")) {
            throw new ModelException(at, "an sftp setting needs 'hostKey', the fingerprint of the server's key in the form SHA256:...");
        }
    }
}
