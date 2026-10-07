package io.orvanta.forge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command line of Orvanta Forge.
 *
 * <pre>
 *   forge validate &lt;workspace&gt;          build the workspace and report problems
 *   forge test     &lt;workspace&gt;          build, then run every TestCase
 *   forge emit     &lt;workspace&gt; &lt;dir&gt;    write the generated Java sources for inspection
 * </pre>
 */
public final class ForgeCli {

    private ForgeCli() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    public static int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: forge validate|test|emit <workspace> [outputDir]");
            return 2;
        }
        Path workspace = Path.of(args[1]);
        Forge.Build build = Forge.buildWorkspace(workspace);
        if (!build.ok()) {
            System.out.println("BUILD FAILED: " + build.problems().size() + " problem(s)");
            build.problems().forEach(p -> System.out.println("  " + p));
            return 1;
        }
        System.out.println("build ok: " + build.registry().elements().size() + " compiled element(s), "
                + (build.registry().configs("Channel").size() + build.registry().configs("Connector").size()) + " configuration model(s)");
        switch (args[0]) {
            case "validate":
                return 0;
            case "test": {
                List<TestRunner.TestResult> results = TestRunner.runAll(build.registry(), workspace);
                int failed = 0;
                for (TestRunner.TestResult r : results) {
                    System.out.println((r.passed() ? "  PASS  " : "  FAIL  ") + r.name());
                    r.failures().forEach(f -> System.out.println("          " + f));
                    failed += r.passed() ? 0 : 1;
                }
                System.out.println(results.size() + " test(s), " + failed + " failed");
                return failed == 0 ? 0 : 1;
            }
            case "emit": {
                if (args.length < 3) {
                    System.out.println("emit needs an output directory");
                    return 2;
                }
                Path out = Path.of(args[2]);
                Files.createDirectories(out);
                for (String name : build.registry().elements().keySet()) {
                    Files.writeString(out.resolve(name + ".java"), build.registry().generatedSource(name), StandardCharsets.UTF_8);
                }
                System.out.println("wrote " + build.registry().elements().size() + " source file(s) to " + out.toAbsolutePath());
                return 0;
            }
            default:
                System.out.println("unknown command '" + args[0] + "'");
                return 2;
        }
    }
}
