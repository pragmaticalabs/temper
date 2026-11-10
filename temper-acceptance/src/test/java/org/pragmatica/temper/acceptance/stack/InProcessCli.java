package org.pragmatica.temper.acceptance.stack;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/// The real `temper` CLI, run in this JVM: `Cli.run(args, context, ports)` from the CLI's shaded
/// jar (`temper.cli.jar`), with the T2 verbs' injected ports (the `file://` git base, a [GhSpy] for `gh pr merge`).
///
/// The jar is loaded by a class loader of its own whose parent is the platform loader only: its bundled
/// slf4j-nop and its copies of pragmatica-lite never meet the test classpath's (logback, the controller's), and the
/// ClasspathIT rule (the CLI's shaded jar is never on the classpath, F5) still holds. Everything is reached by
/// reflection, so the CLI classes are the jar's, byte for byte, as the process form runs them.
final class InProcessCli implements AutoCloseable {
    record Run(int exit, List<String> out, List<String> err) {}

    /// `gh pr merge` replaced: each call is recorded as `<repo> <pr> <sha>`; it answers exit 0 (or `code`).
    static final class GhSpy {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile int code;
        volatile String stderr = "";

        List<String> calls() {
            return List.copyOf(calls);
        }
    }

    private final URLClassLoader loader;

    InProcessCli() throws Exception {
        this.loader = new URLClassLoader(new URL[] {Path.of(System.getProperty("temper.cli.jar")).toUri().toURL()},
                                         ClassLoader.getPlatformClassLoader());
    }

    /// `temper <args>` with `HOME` = `home`, `TEMPER_URL` = `url`, `TEMPER_OWNER` = `acceptance`, git fetched from `gitBase`
    /// (`file://…/`, ends in `/`, `<base><repo>.git`), `gh pr merge` answered by `gh`.
    Run run(Path home, String url, String gitBase, GhSpy gh, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var env = Map.of("TEMPER_URL", url, "TEMPER_OWNER", "acceptance");

        try (var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             var errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            var option = cls("org.pragmatica.lang.Option").getMethod("option", Object.class);
            var fn1 = cls("org.pragmatica.lang.Functions$Fn1");
            Object environment = Proxy.newProxyInstance(loader, new Class<?>[] {fn1}, (_, method, params) -> {
                if (!method.getName().equals("apply")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                return option.invoke(null, env.get((String) params[0]));
            });
            var console = cls("org.pragmatica.temper.cli.Console").getMethod("console", PrintStream.class, PrintStream.class)
                                                                      .invoke(null, outStream, errStream);
            var pacer = cls("org.pragmatica.temper.cli.Pacer").getMethod("pacer").invoke(null);
            var context = cls("org.pragmatica.temper.cli.CliContext")
                .getMethod("cliContext", fn1, Path.class, InstantSource.class, cls("org.pragmatica.temper.cli.Pacer"),
                           cls("org.pragmatica.temper.cli.Console"))
                .invoke(null, environment, home, InstantSource.system(), pacer, console);
            var ghType = cls("org.pragmatica.temper.cli.merge.GhMerge");
            var exit = cls("org.pragmatica.temper.cli.merge.GhMerge$GhExit").getMethod("ghExit", int.class, String.class);
            var success = cls("org.pragmatica.lang.Promise").getMethod("success", Object.class);
            Object merge = Proxy.newProxyInstance(loader, new Class<?>[] {ghType}, (_, method, params) -> {
                if (!method.getName().equals("merge")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                gh.calls.add(params[0] + " " + params[1] + " " + params[2]);
                return success.invoke(null, exit.invoke(null, gh.code, gh.stderr));
            });
            var runner = cls("org.pragmatica.temper.protocol.exec.ProcessRunner").getMethod("processRunner").invoke(null);
            var ports = cls("org.pragmatica.temper.cli.merge.MergePorts")
                .getMethod("mergePorts", String.class, cls("org.pragmatica.temper.protocol.exec.ProcessRunner"), ghType, Duration.class,
                           LongSupplier.class)
                .invoke(null, gitBase, runner, merge, Duration.ofSeconds(45), (LongSupplier) System::nanoTime);
            var code = (int) cls("org.pragmatica.temper.cli.Cli")
                .getMethod("run", String[].class, cls("org.pragmatica.temper.cli.CliContext"), cls("org.pragmatica.temper.cli.merge.MergePorts"))
                .invoke(null, args, context, ports);

            return new Run(code, lines(out), lines(err));
        } catch (InvocationTargetException e) {
            throw new AssertionError("temper " + String.join(" ", args) + " threw", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("in-process CLI wiring", e);
        }
    }

    private Class<?> cls(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    private static List<String> lines(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Override
    public void close() throws Exception {
        loader.close();
    }
}
