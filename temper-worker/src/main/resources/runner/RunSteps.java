import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/// The unit container's entry point (m0-design §7.4). It is not a shell: it runs each step's argv directly.
///
/// Arguments are count-prefixed steps, `<n1> <step1 argv...> <n2> <step2 argv...>`, so no separator or escaping
/// exists that a request field could forge (R-SHELL). The steps run in order with the R-ENV allow-list as their
/// whole environment: docker cannot unset the image's own `ENV` (LANG, JAVA_VERSION, ...), so the list the worker
/// applies with `-e` is applied a second time here. stderr is merged into stdout so `docker logs` keeps the mvn
/// output in order. The exit code is the first non-zero step rc, or 0; malformed arguments exit 2.
///
/// `RunSteps --jvms` lists the container's JVMs instead (`<pid> <command>` per line, the `jcmd -l` shape) for the
/// timeout thread dumps. `jcmd -l` cannot do it: the unit runs as the host's uid, which has no passwd entry in the
/// image, so the JVMs never create `/tmp/hsperfdata_<user>` and `jcmd -l` sees none of them; attaching by pid
/// (`jcmd <pid> Thread.print`) still works.
///
/// Plain JDK code on purpose: it is compiled inside the image with `javac` and has no dependencies. The allow-list
/// must equal `ContainerEnv.ALLOWED`; `RunStepsTest` pins that.
public final class RunSteps {
    static final Set<String> ALLOWED = Set.of("JAVA_HOME",
                                              "MAVEN_OPTS",
                                              "TESTCONTAINERS_HOST_OVERRIDE",
                                              "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX",
                                              "HOME",
                                              "PATH",
                                              "TZ");
    static final int USAGE = 2;

    private RunSteps() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--jvms")) {
            jvms().forEach(System.out::println);
            System.exit(0);
        }

        var steps = steps(args);

        if (steps.isEmpty()) {
            System.err.println("[temper] usage: RunSteps <n> <argv...> [<n> <argv...>]...");
            System.exit(USAGE);
        }

        var environment = allowed(System.getenv());

        for (var index = 0; index < steps.size(); index++) {
            var step = steps.get(index);

            System.err.println("[temper] step " + (index + 1) + "/" + steps.size() + ": " + step);

            var builder = new ProcessBuilder(step).redirectErrorStream(true)
                                                  .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                                                  .redirectInput(ProcessBuilder.Redirect.INHERIT);

            builder.environment().clear();
            builder.environment().putAll(environment);

            var rc = builder.start().waitFor();

            if (rc != 0) {
                System.err.println("[temper] step " + (index + 1) + " exited " + rc);
                System.exit(rc);
            }
        }

        System.exit(0);
    }

    /// The steps, or an empty list when the arguments are malformed (a bad count, a count past the end, or none).
    static List<List<String>> steps(String[] args) {
        var steps = new ArrayList<List<String>>();
        var position = 0;

        while (position < args.length) {
            var count = count(args[position]);

            if (count < 1 || position + 1 + count > args.length) {
                return List.of();
            }

            steps.add(List.of(args).subList(position + 1, position + 1 + count));
            position += 1 + count;
        }

        return steps;
    }

    /// Every other process whose executable is `java`, as `<pid> <command line>`.
    static List<String> jvms() {
        var self = ProcessHandle.current()
                                .pid();

        return ProcessHandle.allProcesses()
                            .filter(process -> process.pid() != self)
                            .filter(process -> process.info()
                                                      .command()
                                                      .map(command -> command.equals("java") || command.endsWith("/java"))
                                                      .orElse(false))
                            .map(process -> process.pid() + " " + process.info()
                                                                        .commandLine()
                                                                        .orElse("java"))
                            .toList();
    }

    static Map<String, String> allowed(Map<String, String> environment) {
        var filtered = new TreeMap<String, String>();

        environment.forEach((key, value) -> {
            if (ALLOWED.contains(key)) {
                filtered.put(key, value);
            }
        });

        return filtered;
    }

    private static int count(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
