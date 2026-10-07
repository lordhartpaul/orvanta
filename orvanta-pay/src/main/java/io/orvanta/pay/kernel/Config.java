package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Settings from config/orvanta.yaml. Every setting can be overridden per process, which is how
 * the same jar runs as different services: a command line argument {@code --server.port=8481}
 * wins over the environment variable {@code ORVANTA_SERVER_PORT}, which wins over the file.
 */
public final class Config {

    private final Rec values;
    private final Rec overrides = new Rec();
    private final Path baseDir;

    private Config(Rec values, Path baseDir) {
        this.values = values;
        this.baseDir = baseDir;
    }

    public static Config load(Path file, String[] args) throws Exception {
        Rec values = new Rec();
        if (Files.isRegularFile(file)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file, StandardCharsets.UTF_8));
            if (loaded instanceof Map<?, ?> m) {
                values = Rec.from(m);
            }
        }
        Path base = file.toAbsolutePath().getParent() == null ? Path.of(".") : file.toAbsolutePath().getParent().getParent();
        Config config = new Config(values, base == null ? Path.of(".").toAbsolutePath() : base);
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                config.overrides.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
            }
        }
        return config;
    }

    public static Config of(Rec values) {
        return new Config(values, Path.of(".").toAbsolutePath());
    }

    public String get(String path, String fallback) {
        Object o = overrides.get(path);
        if (o == null) {
            o = System.getenv("ORVANTA_" + path.replace('.', '_').toUpperCase(java.util.Locale.ROOT));
        }
        if (o == null) {
            o = values.at(path);
        }
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }

    public int getInt(String path, int fallback) {
        return Integer.parseInt(get(path, String.valueOf(fallback)));
    }

    public boolean getBool(String path, boolean fallback) {
        return Boolean.parseBoolean(get(path, String.valueOf(fallback)));
    }

    /** A directory setting, resolved against the installation directory when relative. */
    public Path dir(String path, String fallback) {
        Path p = Path.of(get(path, fallback));
        return p.isAbsolute() ? p : baseDir.resolve(p).normalize();
    }

    public Path baseDir() {
        return baseDir;
    }
}
