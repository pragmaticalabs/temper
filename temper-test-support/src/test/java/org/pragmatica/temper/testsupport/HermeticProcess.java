package org.pragmatica.temper.testsupport;

import java.util.List;
import java.util.Map;
import java.util.Set;


/// The one place a test child process gets its environment (issue #121). The build JVM's environment carries the build
/// host's state: bb-submit's BB_* variables, a Claude session's CLAUDE*, git's GIT_*, credentials such as SSH_AUTH_SOCK. A
/// child that inherits them can behave differently on a different host, or reach what a test must not reach. Every test
/// launch goes through `builder` or `prepared` (this class is the one place that constructs a ProcessBuilder, hence the exemption below); `HermeticLaunchRules` fails the build when one does not.
@InheritsHostEnvironment
public final class HermeticProcess {
    /// Variable families of the build host that must not reach a child under test: bb-submit's BB_*, TEMPER_*, deploy-lib DL_*,
    /// Claude session variables (CLAUDE*, ANTHROPIC_*), git's GIT_* (GIT_DIR, GIT_INDEX_FILE ...), DOCKER_*, the money guard's
    /// HCLOUD_* and GitHub credentials (GH_*, GITHUB_*), plus SSH_AUTH_SOCK (the host's ssh identity, a credential) and
    /// SSH_ASKPASS* (a host program that answers ssh/git password prompts), AWS_* (cloud credentials and profile), MAVEN_*
    /// (MAVEN_OPTS, MAVEN_ARGS, MAVEN_PROJECTBASEDIR: change what a build child does) and XDG_* (config, data, cache and
    /// runtime directories of host tools). Stripped by prefix, not allow-listed, because PATH, HOME, JAVA_HOME and the locale
    /// are legitimately inherited by the launchers that do not set them; whatever a test needs it passes explicitly.
    ///
    /// Decision per variable (issue #129), every one stripped; none is inherited on purpose:
    /// - AWS_*, KUBECONFIG, GNUPGHOME, SSH_ASKPASS*, SSH_CLIENT, SSH_CONNECTION, the proxies: credentials or the host's route
    ///   to the network; a test child talks to localhost fixtures and must not leave the box or sign with the host's key.
    /// - BASH_ENV, ENV: make every non-interactive bash/sh child source a host file before the script under test.
    /// - MAVEN_*, JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, JDK_JAVA_OPTIONS: re-configure any maven/java child (agents, heap,
    ///   -D overrides). No test launches a real mvn (they put a stub `mvn` on PATH); java children are `java -jar` of built
    ///   artifacts and need no host options. A test that does need one passes it through `builder(command, env)`.
    /// - TMPDIR: where the host puts scratch space. A child falls back to /tmp; KilnIngestIT, whose assertion is about the
    ///   tmp directory, passes its own TMPDIR explicitly.
    /// - XDG_*: includes XDG_CONFIG_HOME, XDG_RUNTIME_DIR, XDG_DATA_HOME, XDG_CACHE_HOME.
    ///
    /// Names that are not families (ENV, TMPDIR, KUBECONFIG ...) live in `HOST_NAMES` and match exactly, so ENVIRONMENT_NAME
    /// or TMPDIR_X are kept.
    public static final List<String> HOST_PREFIXES = List.of("BB_",
                                                             "TEMPER_",
                                                             "DL_",
                                                             "CLAUDE",
                                                             "ANTHROPIC_",
                                                             "GIT_",
                                                             "DOCKER_",
                                                             "HCLOUD_",
                                                             "GH_",
                                                             "GITHUB_",
                                                             "SSH_AUTH_SOCK",
                                                             "SSH_ASKPASS",
                                                             "AWS_",
                                                             "MAVEN_",
                                                             "XDG_");

    /// Single variables stripped by exact name: a prefix match would also take unrelated names (ENV -> ENVIRONMENT_NAME,
    /// TMPDIR -> TMPDIR_X). Proxies in both cases, because tools read either.
    public static final Set<String> HOST_NAMES = Set.of("KUBECONFIG",
                                                        "GNUPGHOME",
                                                        "SSH_CLIENT",
                                                        "SSH_CONNECTION",
                                                        "HTTP_PROXY",
                                                        "HTTPS_PROXY",
                                                        "ALL_PROXY",
                                                        "NO_PROXY",
                                                        "http_proxy",
                                                        "https_proxy",
                                                        "all_proxy",
                                                        "no_proxy",
                                                        "BASH_ENV",
                                                        "ENV",
                                                        "JAVA_TOOL_OPTIONS",
                                                        "_JAVA_OPTIONS",
                                                        "JDK_JAVA_OPTIONS",
                                                        "TMPDIR");

    /// One poisoning variable per stripped family and per exact name, for the environment tests.
    public static final Map<String, String> POISON = Map.ofEntries(Map.entry("BB_HOST", "cloudbb-d1"),
                                                                   Map.entry("TEMPER_WT_ROOT", "/poison"),
                                                                   Map.entry("DL_REMOTE", "poison"),
                                                                   Map.entry("CLAUDE_SESSION_ID", "poison"),
                                                                   Map.entry("CLAUDECODE", "1"),
                                                                   Map.entry("ANTHROPIC_API_KEY", "poison"),
                                                                   Map.entry("GIT_DIR", "/poison"),
                                                                   Map.entry("DOCKER_HOST", "unix:///poison"),
                                                                   Map.entry("HCLOUD_TOKEN", "poison"),
                                                                   Map.entry("GH_TOKEN", "poison"),
                                                                   Map.entry("GITHUB_TOKEN", "poison"),
                                                                   Map.entry("SSH_AUTH_SOCK", "/poison/agent"),
                                                                   Map.entry("SSH_ASKPASS", "/poison/askpass"),
                                                                   Map.entry("AWS_SECRET_ACCESS_KEY", "poison"),
                                                                   Map.entry("MAVEN_OPTS", "-Dpoison=1"),
                                                                   Map.entry("XDG_CONFIG_HOME", "/poison/xdg"),
                                                                   Map.entry("XDG_RUNTIME_DIR", "/poison/run"),
                                                                   Map.entry("KUBECONFIG", "/poison/kube"),
                                                                   Map.entry("GNUPGHOME", "/poison/gnupg"),
                                                                   Map.entry("SSH_CLIENT", "poison 1 2"),
                                                                   Map.entry("SSH_CONNECTION", "poison 1 2 3"),
                                                                   Map.entry("HTTP_PROXY", "http://poison:1"),
                                                                   Map.entry("HTTPS_PROXY", "http://poison:1"),
                                                                   Map.entry("ALL_PROXY", "http://poison:1"),
                                                                   Map.entry("NO_PROXY", "poison"),
                                                                   Map.entry("http_proxy", "http://poison:1"),
                                                                   Map.entry("https_proxy", "http://poison:1"),
                                                                   Map.entry("all_proxy", "http://poison:1"),
                                                                   Map.entry("no_proxy", "poison"),
                                                                   Map.entry("BASH_ENV", "/poison/bashenv"),
                                                                   Map.entry("ENV", "/poison/env"),
                                                                   Map.entry("JAVA_TOOL_OPTIONS", "-Dpoison=1"),
                                                                   Map.entry("_JAVA_OPTIONS", "-Dpoison=1"),
                                                                   Map.entry("JDK_JAVA_OPTIONS", "-Dpoison=1"),
                                                                   Map.entry("TMPDIR", "/poison/tmp"));

    private HermeticProcess() {}

    /// A builder for `command` whose environment has the host's families stripped.
    public static ProcessBuilder builder(String... command) {
        return builder(List.of(command));
    }

    /// A builder for `command` whose environment has the host's families stripped.
    public static ProcessBuilder builder(List<String> command) {
        return builder(command, Map.of());
    }

    /// A builder for `command`: the host's families stripped first, then only `env` added.
    public static ProcessBuilder builder(List<String> command, Map<String, String> env) {
        return prepared(new ProcessBuilder(command), env);
    }

    /// The builder with the host's families stripped first and only `env` added after: an explicit variable of a stripped
    /// family survives, an inherited one never does.
    public static ProcessBuilder prepared(ProcessBuilder builder, Map<String, String> env) {
        hermetic(builder.environment());
        builder.environment().putAll(env);

        return builder;
    }

    /// Removes the host's variable families (HOST_PREFIXES, HOST_NAMES) from a child's environment.
    public static void hermetic(Map<String, String> environment) {
        environment.keySet().removeIf(HermeticProcess::hostVariable);
    }

    /// True when `name` is a host variable: an exact HOST_NAMES entry or a HOST_PREFIXES family member.
    public static boolean hostVariable(String name) {
        return HOST_NAMES.contains(name) || HOST_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
