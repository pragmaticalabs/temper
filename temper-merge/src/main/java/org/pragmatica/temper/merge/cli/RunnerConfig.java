package org.pragmatica.temper.merge.cli;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.merge.engine.OneLine;
import org.pragmatica.temper.merge.engine.Policy;


/// The runner's settings: `<home>/merge-runner.properties`, optional (absent: the defaults). `home` is
/// `$TEMPER_MERGE_HOME`, else `~/temper/merge-runner`; the jobs live in `<home>/jobs`.
///
/// | key | default | |
/// |---|---|---|
/// | `repo` | `pragmaticalabs/pragmatica` | `owner/name` the jobs are for |
/// | `eventsLog` | `<home>/events.log` | the report file, one line per state change |
/// | `poll` | 60 | seconds between two rounds, at least 10 |
/// | `deadline.submitted`, `.checks`, `.enqueuing`, `.queue`, `.run` | 900, 10800, 900, 14400, 10800 | seconds per phase |
///
/// An unknown key, a value that is not a whole number of seconds, or a policy [Policy#policy] refuses is an error at
/// start (exit 2), never a job state.
public record RunnerConfig(Path home, String repo, Path eventsLog, Policy policy) {
    static final String FILE = "merge-runner.properties";
    static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    static final Set<String> KEYS = Set.of("repo",
                                           "eventsLog",
                                           "poll",
                                           "deadline.submitted",
                                           "deadline.checks",
                                           "deadline.enqueuing",
                                           "deadline.queue",
                                           "deadline.run");

    public Path jobs() {
        return home.resolve("jobs");
    }

    static Path homeOf(Map<String, String> env) {
        var given = env.getOrDefault("TEMPER_MERGE_HOME", "");

        return given.isBlank()
               ? Path.of(env.getOrDefault("HOME", "."), "temper", "merge-runner")
               : Path.of(given);
    }

    public static Result<RunnerConfig> load(Map<String, String> env) {
        var home = homeOf(env);
        var file = home.resolve(FILE);

        return Result.lift(thrown -> Causes.cause("cannot read " + file + ": " + thrown),
                           () -> Files.exists(file)
                                 ? Files.readString(file)
                                 : "")
                     .flatMap(text -> parsed(home, text));
    }

    static Result<RunnerConfig> parsed(Path home, String text) {
        return properties(text).flatMap(props -> fromProperties(home, props));
    }

    private static Result<Properties> properties(String text) {
        var props = new Properties();

        return Result.lift(thrown -> Causes.cause(FILE + " is not a properties file: " + thrown),
                           () -> props.load(new StringReader(text)))
                     .map(_ -> props);
    }

    private static Result<RunnerConfig> fromProperties(Path home, Properties props) {
        var unknown = props.stringPropertyNames().stream().filter(key -> !KEYS.contains(key)).sorted().toList();

        if (!unknown.isEmpty()) {
            return Causes.cause(FILE + ": unknown key(s) " + String.join(", ", unknown)).result();
        }

        var defaults = Policy.DEFAULT;
        var repo = props.getProperty("repo", "pragmaticalabs/pragmatica").trim();

        if (!REPO.matcher(repo).matches()) {
            return Causes.cause(FILE + ": repo must be owner/name: " + repo).result();
        }

        var events = props.getProperty("eventsLog", "").trim();
        var eventsLog = events.isEmpty()
                        ? home.resolve("events.log")
                        : Path.of(events);

        return Result.all(seconds(props,
                                  "poll",
                                  defaults.poll()),
                          seconds(props,
                                  "deadline.submitted",
                                  defaults.submitted()),
                          seconds(props,
                                  "deadline.checks",
                                  defaults.checks()),
                          seconds(props,
                                  "deadline.enqueuing",
                                  defaults.enqueuing()),
                          seconds(props,
                                  "deadline.queue",
                                  defaults.queue()),
                          seconds(props,
                                  "deadline.run",
                                  defaults.run()))
                     .flatMap(Policy::policy)
                     .mapError(cause -> Causes.cause(FILE + ": " + OneLine.of(cause)))
                     .map(policy -> new RunnerConfig(home, repo, eventsLog, policy));
    }

    private static Result<Duration> seconds(Properties props, String key, Duration fallback) {
        var value = props.getProperty(key, "").trim();

        if (value.isEmpty()) {
            return Result.success(fallback);
        }

        return value.matches("[0-9]{1,9}")
               ? Result.success(Duration.ofSeconds(Long.parseLong(value)))
               : Causes.cause(key + " must be whole seconds: " + value).result();
    }
}
