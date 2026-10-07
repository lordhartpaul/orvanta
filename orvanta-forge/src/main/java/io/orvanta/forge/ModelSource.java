package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One model file: a YAML document with a kind and a unique dotted name.
 * The text is kept verbatim because deployments store and version the text, not the parsed form.
 */
public record ModelSource(String kind, String name, String path, String text, Rec def) {

    private static final Pattern ENV = Pattern.compile("\\$\\{env\\.([A-Za-z0-9_]+)(?::-((?:[^${}]|\\$\\{[^{}]*})*))?}");

    public static ModelSource parse(String path, String text) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(substituteEnv(text));
        } catch (RuntimeException e) {
            throw new ModelException(path, "invalid YAML: " + firstLine(e.getMessage()));
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new ModelException(path, "a model file must be a YAML mapping with kind and name");
        }
        Rec def = Rec.from(map);
        String kind = def.str("kind");
        String name = def.str("name");
        if (kind == null || name == null) {
            throw new ModelException(path, "both 'kind' and 'name' are required");
        }
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")) {
            throw new ModelException(path, "name '" + name + "' must be dotted identifiers, for example payments.rules.TransactionValidation");
        }
        return new ModelSource(kind, name, path, text, def);
    }

    /**
     * The model as plain data for a visual editor. Environment placeholders are left as written,
     * so that writing the model back with {@link #toText} does not fix them to this machine's values.
     */
    public static Rec definition(String text) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw new ModelException("draft", "invalid YAML: " + firstLine(e.getMessage()));
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new ModelException("draft", "a model file must be a YAML mapping with kind and name");
        }
        return Rec.from(map);
    }

    /** Writes a definition as model text, keys in the given order. Comments of an earlier text are not kept. */
    public static String toText(Map<?, ?> definition) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setWidth(110);
        options.setSplitLines(true);
        return new Yaml(options).dump(plain(definition));
    }

    private static Object plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(plain(v)));
            return out;
        }
        return value;
    }

    /** Reads every *.yaml file below the workspace directory. Unreadable files are reported, not skipped. */
    public static List<ModelSource> loadWorkspace(Path dir, List<Problem> problems) {
        List<ModelSource> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".yaml") && Files.isRegularFile(f)).sorted().toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                try {
                    out.add(parse(rel, Files.readString(p, StandardCharsets.UTF_8)));
                } catch (ModelException e) {
                    problems.add(new Problem(rel, e.where(), e.getMessage()));
                }
            }
        } catch (IOException e) {
            problems.add(new Problem(dir.toString(), "", "cannot read workspace: " + e.getMessage()));
        }
        return out;
    }

    /**
     * ${env.NAME:-default} lets connector URLs and folders differ per environment. The default may itself
     * name a variable: ${env.A:-${env.B:-value}} is A, or else B, or else the value.
     */
    static String substituteEnv(String text) {
        Matcher m = ENV.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = System.getenv(m.group(1));
            if (value == null) {
                value = System.getProperty(m.group(1));
            }
            if (value == null) {
                value = m.group(2) == null ? "" : substituteEnv(m.group(2));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
