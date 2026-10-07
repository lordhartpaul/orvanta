package io.orvanta.forge;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Compiles generated sources with the JDK compiler, entirely in memory, into a fresh class loader. */
final class MemoryCompiler {

    record Result(ClassLoader loader, List<Problem> problems) {
    }

    private MemoryCompiler() {
    }

    /**
     * Class files of sources compiled before, by class name and source text. A build compiles only the
     * sources that are new or changed; the generated classes do not refer to each other, so the rest is
     * taken from here. Validating an edit in Studio then costs one class, not the whole deployment.
     */
    private static final Map<String, Map<String, byte[]>> COMPILED = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, byte[]>> eldest) {
                    return size() > 4000;
                }
            });

    private static String key(String className, String source) {
        return className + "\n" + source;
    }

    /** @param sources simple class name to source; @param owners simple class name to model name, for error reports */
    static Result compile(Map<String, String> sources, Map<String, String> owners) {
        Map<String, String> changed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (!COMPILED.containsKey(key(e.getKey(), e.getValue()))) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Map<String, byte[]>> fresh = new java.util.HashMap<>();
        List<Problem> problems = changed.isEmpty() ? new ArrayList<>() : javac(changed, owners, fresh);
        if (!problems.isEmpty() && changed.size() < sources.size()) {
            // should a class ever need another one to compile, everything is compiled together and the report comes from that
            fresh.clear();
            problems = javac(sources, owners, fresh);
        }
        if (!problems.isEmpty()) {
            return new Result(null, problems);
        }
        Map<String, byte[]> classes = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Map<String, byte[]> files = fresh.get(e.getKey());
            if (files != null) {
                COMPILED.put(key(e.getKey(), e.getValue()), files);
            } else {
                files = COMPILED.get(key(e.getKey(), e.getValue()));
            }
            if (files == null) {
                // dropped from the cache between the two looks: compile it after all
                Map<String, Map<String, byte[]>> again = new java.util.HashMap<>();
                List<Problem> late = javac(Map.of(e.getKey(), e.getValue()), owners, again);
                if (!late.isEmpty()) {
                    return new Result(null, late);
                }
                files = again.get(e.getKey());
            }
            classes.putAll(files);
        }
        ClassLoader loader = new ClassLoader(MemoryCompiler.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, b, 0, b.length);
            }
        };
        return new Result(loader, new ArrayList<>());
    }

    /** Compiles the sources; the class files go to 'out', grouped by the simple name of the source they came from. */
    private static List<Problem> javac(Map<String, String> sources, Map<String, String> owners, Map<String, Map<String, byte[]>> out) {
        List<Problem> problems = new ArrayList<>();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            problems.add(new Problem("forge", "", "no Java compiler available: run on a JDK, not a JRE"));
            return problems;
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> classes = new ConcurrentHashMap<>();
        StandardJavaFileManager standard = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        classes.put(className, bytes);
                        return bytes;
                    }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String uri = "string:///" + Generator.PACKAGE.replace('.', '/') + "/" + e.getKey() + ".java";
            units.add(new SimpleJavaFileObject(URI.create(uri), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return e.getValue();
                }
            });
        }
        List<String> options = List.of("-classpath", System.getProperty("java.class.path"), "-proc:none", "-Xlint:none");
        boolean ok = javac.getTask(null, manager, diagnostics, options, null, units).call();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            String file = d.getSource() == null ? "" : d.getSource().getName();
            String cls = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
            problems.add(new Problem(owners.getOrDefault(cls, cls), "generated line " + d.getLineNumber(),
                    "generated code does not compile: " + d.getMessage(null)));
        }
        if (!ok && problems.isEmpty()) {
            problems.add(new Problem("forge", "", "compilation failed without a diagnostic"));
        }
        if (problems.isEmpty()) {
            // a source gives the class of its own name and, should it have any, classes nested in it (Name$1)
            for (Map.Entry<String, ByteArrayOutputStream> c : classes.entrySet()) {
                String simple = c.getKey().substring(c.getKey().lastIndexOf('.') + 1);
                String source = simple.contains("$") ? simple.substring(0, simple.indexOf('$')) : simple;
                out.computeIfAbsent(source, k -> new java.util.HashMap<>()).put(c.getKey(), c.getValue().toByteArray());
            }
        }
        return problems;
    }
}
