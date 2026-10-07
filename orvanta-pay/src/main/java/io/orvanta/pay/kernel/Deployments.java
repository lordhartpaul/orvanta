package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Connector;
import io.orvanta.core.flow.HttpConnector;
import io.orvanta.core.flow.Registry;
import io.orvanta.forge.Forge;
import io.orvanta.forge.ModelException;
import io.orvanta.forge.ModelSource;
import io.orvanta.forge.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned deployments of the low-code models. A deployment is the full set of model texts;
 * it is stored, compiled by Forge and swapped in atomically. Every process compiles the active
 * deployment at start and again when another process activates a new one, so a change approved
 * in the console reaches all running services without a restart.
 */
public final class Deployments {

    private static final Logger LOG = LoggerFactory.getLogger(Deployments.class);
    private static final String ACTIVE = "activeDeployment";

    private record Active(String id, long version, Registry registry, Map<String, Connector> connectors) {
    }

    private final DocStore store;
    private final Bus bus;
    private volatile Active active;
    private volatile List<String> allowedHosts = List.of();
    private volatile String integrityKey;
    private final List<Runnable> activationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public Deployments(DocStore store, Bus bus) {
        this.store = store;
        this.bus = bus;
    }

    /**
     * Loads the active deployment. An empty store is seeded from the workspace directory.
     * With syncWorkspace the directory is deployed whenever it differs from the active deployment
     * (development convenience: it bypasses maker-checker, so keep it off outside development).
     */
    public void start(Path workspace, boolean syncWorkspace) {
        Rec pointer = store.get(DocStore.SETTING, ACTIVE);
        if (pointer == null || syncWorkspace) {
            List<Problem> problems = new ArrayList<>();
            List<Rec> models = new ArrayList<>();
            if (Files.isDirectory(workspace)) {
                for (ModelSource m : ModelSource.loadWorkspace(workspace, problems)) {
                    models.add(modelRec(m));
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException("workspace " + workspace + " has problems: " + problems);
            }
            if (pointer == null || !sameModels(models, models(store.get(DocStore.DEPLOYMENT, pointer.str("deploymentId"))))) {
                if (pointer == null && models.isEmpty()) {
                    throw new IllegalStateException("nothing is deployed and the workspace " + workspace + " has no models");
                }
                bus.subscribeAll(Bus.DEPLOYMENT_ACTIVATED, m -> reload());
                if (pointer == null) {
                    seed(models);
                } else {
                    deploy(models, "system", null, "workspace sync");
                }
                return;
            }
        }
        reload();
        bus.subscribeAll(Bus.DEPLOYMENT_ACTIVATED, m -> reload());
    }

    /**
     * First deployment of an empty store. Several processes may start at the same moment;
     * the one that creates the pointer wins and the others load what it deployed.
     */
    private void seed(List<Rec> models) {
        Forge.Build build = build(models);
        if (!build.ok()) {
            throw new IllegalStateException("the workspace models do not build: " + build.problems());
        }
        long version = store.nextSequence("deployment");
        Rec deployment = Rec.of("id", String.format("ORVDEP%06d", version), "version", version, "models", models,
                "modelCount", models.size(), "createdBy", "system", "comment", "initial deployment from workspace",
                "createdAt", Platform.now());
        deployment.set("seal", seal(models));
        if (!store.insertIfAbsent(DocStore.SETTING, Rec.of("id", ACTIVE, "deploymentId", deployment.str("id"), "version", version))) {
            for (int i = 0; i < 50 && active == null; i++) {
                reload();
                if (active == null) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (active == null) {
                throw new IllegalStateException("another process is seeding the first deployment and did not finish");
            }
            return;
        }
        store.insert(DocStore.DEPLOYMENT, deployment);
        activate(deployment, build.registry());
        bus.publish(Bus.DEPLOYMENT_ACTIVATED, Rec.of("deploymentId", deployment.str("id")));
    }

    private synchronized void reload() {
        Rec pointer = store.get(DocStore.SETTING, ACTIVE);
        if (pointer == null || (active != null && active.id().equals(pointer.str("deploymentId")))) {
            return;
        }
        Rec deployment = store.get(DocStore.DEPLOYMENT, pointer.str("deploymentId"));
        if (deployment == null) {
            // the seeding process has written the pointer but not yet the deployment
            return;
        }
        if (!sealIntact(deployment)) {
            // models changed outside the approval path, or sealed with another key: never run them
            LOG.error("deployment {} failed its integrity check and was NOT activated. Deploy again through an approved change, "
                    + "or once with --workspace.syncOnStart=true.", deployment.str("id"));
            if (active == null) {
                throw new IllegalStateException("the active deployment " + deployment.str("id") + " failed its integrity check");
            }
            return;
        }
        Forge.Build build = Forge.build(sources(models(deployment)));
        if (!build.ok()) {
            // keeps serving the previous version; a stored deployment was validated before it was stored
            LOG.error("deployment {} does not build here and was not activated: {}", deployment.str("id"), build.problems());
            return;
        }
        activate(deployment, build.registry());
    }

    private void activate(Rec deployment, Registry registry) {
        active = new Active(deployment.str("id"), deployment.num("version").longValue(), registry, new ConcurrentHashMap<>());
        io.orvanta.core.flow.RefData.activate(registry);
        for (Runnable listener : activationListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.error("a listener failed after deployment {} became active", active.id(), e);
            }
        }
        LOG.info("deployment {} (version {}) is active: {} elements", active.id(), active.version(), registry.elements().size());
    }

    /** Runs the action every time a deployment becomes active in this process. */
    public void onActivate(Runnable action) {
        activationListeners.add(action);
    }

    public Registry registry() {
        Active a = active;
        if (a == null) {
            throw new IllegalStateException("no deployment is active");
        }
        return a.registry();
    }

    public String activeId() {
        return active == null ? null : active.id();
    }

    public long activeVersion() {
        return active == null ? 0 : active.version();
    }

    /** Model records {name, kind, path, text} of the active deployment. */
    public List<Rec> activeModels() {
        return models(store.get(DocStore.DEPLOYMENT, active.id()));
    }

    /** The active models with one model added or replaced (matched by name, then by path). */
    public List<Rec> overlay(String path, String text) {
        ModelSource changed = ModelSource.parse(path, text);
        List<Rec> models = new ArrayList<>();
        boolean replaced = false;
        for (Rec m : activeModels()) {
            if (m.str("name").equals(changed.name())) {
                Rec r = modelRec(changed);
                r.put("path", m.str("path"));
                models.add(r);
                replaced = true;
            } else {
                models.add(m);
            }
        }
        if (!replaced) {
            models.add(modelRec(changed));
        }
        return models;
    }

    /** The active models without the named one. */
    public List<Rec> without(String name) {
        List<Rec> models = new ArrayList<>();
        boolean found = false;
        for (Rec m : activeModels()) {
            if (m.str("name").equals(name)) {
                found = true;
            } else {
                models.add(m);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("no model named " + name + " is deployed");
        }
        return models;
    }

    /** A stored deployment with its models, or null. One that fails its integrity check is never handed out. */
    public Rec stored(String id) {
        Rec deployment = store.get(DocStore.DEPLOYMENT, id);
        if (deployment != null && !sealIntact(deployment)) {
            throw new IllegalStateException("deployment " + id + " failed its integrity check");
        }
        return deployment;
    }

    /** The deployment made just before the given one, or null for the first. */
    public Rec previous(Rec deployment) {
        long version = deployment.num("version").longValue();
        Rec best = null;
        for (Rec d : store.find(DocStore.DEPLOYMENT, null, "version", true, 0)) {
            if (d.num("version").longValue() < version && (best == null || d.num("version").longValue() > best.num("version").longValue())) {
                best = d;
            }
        }
        return best;
    }

    /**
     * What turns the models of one deployment into those of another: one entry per model that was
     * added, removed or changed, with the text before and after. A null deployment counts as empty.
     */
    public static List<Rec> changes(Rec from, Rec to) {
        Map<String, Rec> before = new java.util.TreeMap<>();
        Map<String, Rec> after = new java.util.TreeMap<>();
        models(from).forEach(m -> before.put(m.str("name"), m));
        models(to).forEach(m -> after.put(m.str("name"), m));
        java.util.Set<String> names = new java.util.TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        List<Rec> out = new ArrayList<>();
        for (String name : names) {
            Rec b = before.get(name);
            Rec a = after.get(name);
            if (b != null && a != null && b.str("text").equals(a.str("text"))) {
                continue;
            }
            Rec known = a != null ? a : b;
            out.add(Rec.of("name", name, "kind", known.str("kind"), "path", known.str("path"),
                    "change", b == null ? "ADDED" : a == null ? "REMOVED" : "CHANGED",
                    "before", b == null ? null : b.str("text"), "after", a == null ? null : a.str("text")));
        }
        return out;
    }

    public static List<Rec> modelsOf(Rec deployment) {
        return models(deployment);
    }

    public Forge.Build build(List<Rec> models) {
        try {
            Forge.Build build = Forge.build(sources(models));
            if (!build.ok()) {
                return build;
            }
            // a model may only make the server call where the installation allows it
            List<Problem> problems = new ArrayList<>();
            for (Rec connector : build.registry().configs("Connector")) {
                if ("http".equals(connector.str("type")) || "soap".equals(connector.str("type"))) {
                    String problem = io.orvanta.pay.security.Policies.urlProblem(String.valueOf(connector.str("url")), allowedHosts);
                    if (problem != null) {
                        problems.add(new Problem(connector.str("name"), "url", problem));
                    }
                    // the token URL of OAuth2 client credentials is a place the server calls as well
                    if (connector.at("auth.tokenUrl") != null) {
                        String tokenProblem = io.orvanta.pay.security.Policies.urlProblem(String.valueOf(connector.at("auth.tokenUrl")), allowedHosts);
                        if (tokenProblem != null) {
                            problems.add(new Problem(connector.str("name"), "auth.tokenUrl", tokenProblem));
                        }
                    }
                }
            }
            for (Rec channel : build.registry().configs("Channel")) {
                for (Object t : io.orvanta.core.expr.Ops.list(channel.get("transport"))) {
                    if (t instanceof Rec transport && "http".equals(transport.str("type"))) {
                        for (String url : new String[] {transport.str("url"), transport.at("acknowledge.url") == null ? null : String.valueOf(transport.at("acknowledge.url"))}) {
                            String problem = url == null ? null : io.orvanta.pay.security.Policies.urlProblem(url, allowedHosts);
                            if (problem != null) {
                                problems.add(new Problem(channel.str("name"), "transport.url", problem));
                            }
                        }
                    }
                }
                if ("http".equals(String.valueOf(channel.at("destination.type")))) {
                    String problem = io.orvanta.pay.security.Policies.urlProblem(String.valueOf(channel.at("destination.url")), allowedHosts);
                    if (problem != null) {
                        problems.add(new Problem(channel.str("name"), "destination.url", problem));
                    }
                    if (channel.at("destination.auth.tokenUrl") != null) {
                        String tokenProblem = io.orvanta.pay.security.Policies.urlProblem(String.valueOf(channel.at("destination.auth.tokenUrl")), allowedHosts);
                        if (tokenProblem != null) {
                            problems.add(new Problem(channel.str("name"), "destination.auth.tokenUrl", tokenProblem));
                        }
                    }
                }
            }
            return problems.isEmpty() ? build : new Forge.Build(null, problems);
        } catch (ModelException e) {
            return new Forge.Build(null, List.of(new Problem("model", e.where(), e.getMessage())));
        }
    }

    /** Hosts the models may call (empty: any public host) and the key that seals stored deployments (null: no seal). */
    public void secure(List<String> allowedHosts, String integrityKey) {
        this.allowedHosts = allowedHosts;
        this.integrityKey = integrityKey;
    }

    /** A keyed digest over every model text: a deployment changed directly in the database no longer matches it. */
    private String seal(List<Rec> models) {
        if (integrityKey == null) {
            return null;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(integrityKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            List<Rec> sorted = new ArrayList<>(models);
            sorted.sort(java.util.Comparator.comparing(m -> m.str("name")));
            for (Rec m : sorted) {
                mac.update((m.str("name") + "\n" + m.str("path") + "\n" + m.str("text") + "\u0000").getBytes(StandardCharsets.UTF_8));
            }
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean sealIntact(Rec deployment) {
        if (integrityKey == null) {
            return true;
        }
        String expected = seal(models(deployment));
        String stored = deployment.str("seal");
        return stored != null && java.security.MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /** Builds, stores and activates a new deployment, then tells every other process. */
    public synchronized Rec deploy(List<Rec> models, String createdBy, String approvedBy, String comment) {
        Forge.Build build = build(models);
        if (!build.ok()) {
            throw new IllegalStateException("the models do not build: " + build.problems());
        }
        long version = store.nextSequence("deployment");
        Rec deployment = Rec.of("id", String.format("ORVDEP%06d", version), "version", version, "models", models,
                "modelCount", models.size(), "createdBy", createdBy, "approvedBy", approvedBy, "comment", comment,
                "createdAt", Platform.now());
        deployment.set("seal", seal(models));
        store.insert(DocStore.DEPLOYMENT, deployment);
        store.save(DocStore.SETTING, Rec.of("id", ACTIVE, "deploymentId", deployment.str("id"), "version", version));
        activate(deployment, build.registry());
        bus.publish(Bus.DEPLOYMENT_ACTIVATED, Rec.of("deploymentId", deployment.str("id")));
        return deployment;
    }

    /** Connector by name, created from the Connector model of the active deployment. */
    public Connector connector(String name) {
        Active a = active;
        return a.connectors().computeIfAbsent(name, n -> {
            Rec def = a.registry().config(n);
            if (def == null || !"Connector".equals(def.str("kind"))) {
                throw new IllegalStateException("no connector named '" + n + "' is deployed");
            }
            if ("mock".equals(def.str("type"))) {
                Rec reply = def.get("reply") instanceof Map<?, ?> m ? Rec.from(m) : new Rec();
                return request -> reply.copy();
            }
            return "soap".equals(def.str("type")) ? new io.orvanta.core.flow.SoapConnector(n, def) : new HttpConnector(n, def);
        });
    }

    /** Writes a model back to the workspace directory so the files in git match what is deployed. */
    public static void writeBack(Path workspace, String path, String text) {
        try {
            Path file = workspace.resolve(path).normalize();
            if (!file.startsWith(workspace.normalize())) {
                throw new IllegalArgumentException("model path leaves the workspace: " + path);
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            LOG.warn("could not write {} back to the workspace: {}", path, e.getMessage());
        }
    }

    /** Removes a model file from the workspace directory after its removal was deployed. */
    public static void removeFromWorkspace(Path workspace, String path) {
        try {
            Path file = workspace.resolve(path).normalize();
            if (!file.startsWith(workspace.normalize())) {
                throw new IllegalArgumentException("model path leaves the workspace: " + path);
            }
            Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            LOG.warn("could not remove {} from the workspace: {}", path, e.getMessage());
        }
    }

    private static Rec modelRec(ModelSource m) {
        return Rec.of("name", m.name(), "kind", m.kind(), "path", m.path(), "text", m.text(),
                "description", m.def().str("description"));
    }

    private static List<Rec> models(Rec deployment) {
        List<Rec> out = new ArrayList<>();
        if (deployment != null) {
            for (Object o : Ops.list(deployment.get("models"))) {
                out.add((Rec) o);
            }
        }
        return out;
    }

    private static List<ModelSource> sources(List<Rec> models) {
        List<ModelSource> out = new ArrayList<>();
        for (Rec m : models) {
            out.add(ModelSource.parse(m.str("path"), m.str("text")));
        }
        return out;
    }

    private static boolean sameModels(List<Rec> a, List<Rec> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, String> texts = new java.util.HashMap<>();
        for (Rec m : b) {
            texts.put(m.str("name"), m.str("text"));
        }
        for (Rec m : a) {
            if (!m.str("text").equals(texts.get(m.str("name")))) {
                return false;
            }
        }
        return true;
    }
}
