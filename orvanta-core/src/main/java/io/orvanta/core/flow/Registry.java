package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one deployment contains: the compiled elements by name, the configuration
 * models (channels, connectors, test cases) and the generated sources for inspection.
 */
public final class Registry {

    private final Map<String, Element> elements = new LinkedHashMap<>();
    private final Map<String, Rec> configs = new LinkedHashMap<>();
    private final Map<String, String> generatedSources = new LinkedHashMap<>();
    private final Map<String, Map<String, Rec>> tableIndexes = new java.util.concurrent.ConcurrentHashMap<>();

    public void register(Element element) {
        elements.put(element.name(), element);
    }

    public void registerConfig(Rec definition) {
        configs.put(definition.str("name"), definition);
    }

    public void registerSource(String elementName, String javaSource) {
        generatedSources.put(elementName, javaSource);
    }

    public Element element(String name) {
        return elements.get(name);
    }

    public <T extends Element> T require(String name, Class<T> type) {
        Element e = elements.get(name);
        if (e == null) {
            throw new IllegalStateException("no " + type.getSimpleName() + " named '" + name + "' is deployed");
        }
        if (!type.isInstance(e)) {
            throw new IllegalStateException("'" + name + "' is a " + e.kind() + ", not a " + type.getSimpleName());
        }
        return type.cast(e);
    }

    public Rec config(String name) {
        return configs.get(name);
    }

    public List<Rec> configs(String kind) {
        List<Rec> out = new ArrayList<>();
        for (Rec c : configs.values()) {
            if (kind.equals(c.str("kind"))) {
                out.add(c);
            }
        }
        return out;
    }

    /** Row of a ReferenceTable model by its key value, or null when there is no such row. */
    public Rec lookup(String table, Object key) {
        Map<String, Rec> index = tableIndexes.computeIfAbsent(table, t -> {
            Rec def = configs.get(t);
            if (def == null || !"ReferenceTable".equals(def.str("kind"))) {
                throw new IllegalStateException("no reference table named '" + t + "' is deployed");
            }
            Map<String, Rec> rows = new java.util.HashMap<>();
            for (Object row : io.orvanta.core.expr.Ops.list(def.get("rows"))) {
                if (row instanceof Rec r) {
                    rows.put(String.valueOf(io.orvanta.core.expr.Ops.str(r.get(def.str("key")))), r);
                }
            }
            return rows;
        });
        String k = io.orvanta.core.expr.Ops.str(key);
        return k == null ? null : index.get(k);
    }

    public Map<String, Element> elements() {
        return elements;
    }

    public String generatedSource(String elementName) {
        return generatedSources.get(elementName);
    }
}
