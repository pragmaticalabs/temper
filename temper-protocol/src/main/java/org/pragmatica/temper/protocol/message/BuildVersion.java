package org.pragmatica.temper.protocol.message;

import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.utils.Causes.cause;


/// Build identity (D-6): the version a controller, a worker and a CLI carry is `<project.version>+<judge12>`, the
/// build's `${project.version}` and the first 12 hex digits of its judge (the commit `ops/build-dist.sh` builds), so
/// two builds of one `project.version` are told apart. A `-dirty` judge keeps its suffix
/// (`0.1.0-SNAPSHOT+3f9c0a1b2c4d-dirty`, 33 characters); a build with no judge is `<project.version>+unjudged`.
/// 12 digits, not 40: the version travels as the `worker-launch` argument, capped at 40 by
/// [Handshake#VERSION_ALPHABET].
///
/// The controller's bundled version and the worker's are both derived here, from the `version=` and `judge=` lines of
/// their version resources, so the two jars of one build agree, and a worker of another build is `Incompatible`.
///
/// The part before `+` ([#projectVersion]) is the runner version: it becomes a docker tag, where `+` is not allowed.
public sealed interface BuildVersion {
    /// The HTTP response header carrying the controller's build version on every API answer; the CLI compares it with
    /// its own. The CLI sends its own on every request, and the controller refuses a mutating request of a skewed CLI
    /// before it acts ([#compare]).
    String HEADER = "Temper-Version";
    /// The worker jar's version resource, as an entry name inside the jar: the worker reads it at start, the
    /// controller's deploy before it installs a jar (L4).
    String WORKER_RESOURCE = "org/pragmatica/temper/worker/worker-version.properties";
    String UNJUDGED = "unjudged";
    String DIRTY = "-dirty";
    int JUDGE_DIGITS = 12;
    /// The judges a build carries: a commit (clean or `-dirty`) or `unjudged` (dev and test builds).
    Pattern JUDGE = Pattern.compile("^([0-9a-f]{40}(-dirty)?|unjudged)$");
    /// A `project.version` the runner version can be: a docker-tag-safe shape without `+`, which separates the judge.
    Pattern PROJECT_VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,39}$");

    /// `<projectVersion>+<judge12>[-dirty]` or `<projectVersion>+unjudged`; a project version or judge outside its
    /// pattern, or a result outside [Handshake#VERSION_ALPHABET], is refused.
    static Result<String> buildVersion(String projectVersion, String judge) {
        return Result.all(Verify.ensure(projectVersion,
                                        Verify.Is::matches,
                                        PROJECT_VERSION,
                                        cause("project version '" + projectVersion
                                             + "' is outside " + PROJECT_VERSION.pattern())),
                          Verify.ensure(judge,
                                        Verify.Is::matches,
                                        JUDGE,
                                        cause("judge '" + judge + "' is outside " + JUDGE.pattern())))
                     .map(BuildVersion::joined)
                     .flatMap(BuildVersion::withinAlphabet);
    }

    /// The part before the judge: the runner version (a docker tag). Not the dedup key's runner component, which is the
    /// whole build version (D-6 A1). A version without `+` (a test's fixed version) is its own project version.
    static String projectVersion(String buildVersion) {
        return buildVersion.split("\\+", 2) [0];
    }

    /// Whether a version names its build: a judge after `+`, any but `unjudged`. A `-dirty` build is judged; a version
    /// without `+` (a test's fixed version, such as the acceptance stack's `it-<hash>`) names no build.
    static boolean judged(String buildVersion) {
        return buildVersion.contains("+") && !buildVersion.endsWith("+" + UNJUDGED);
    }

    /// How one side's build version compares with its peer's (the CLI's with the controller's answer, the controller's
    /// with a mutating request's `Temper-Version`).
    ///
    /// - SAME: equal versions.
    /// - PREDATES: the peer sent no version, or one without `+` (G1/G2, transition rules): a peer that predates build
    ///   identity is not checked, only warned about. END CONDITION: when the rollback set (the deploy runbook) no longer holds
    ///   any build older than D-6, PREDATES becomes a refusal (CLI exit 13, controller 412), as SKEWED is.
    /// - UNJUDGED: different, and either side `+unjudged` (a dev or test build): warned about, not refused.
    /// - SKEWED: different, both judged (a `-dirty` build is judged): refused.
    enum Comparison {
        SAME,
        PREDATES,
        UNJUDGED,
        SKEWED
    }

    /// [Comparison] of `own` with `peer`; equal versions are SAME before anything else, so two `+`-less test versions
    /// that agree pass.
    static Comparison compare(String own, Option<String> peer) {
        return peer.map(version -> compared(own, version))
                   .or(Comparison.PREDATES);
    }

    /// The value of the `<key>=` line of a filtered version resource (`version=${project.version}`,
    /// `judge=${temper.judge}`); a blank value or an unfiltered `${...}` placeholder is none.
    static Option<String> property(String properties, String key) {
        return Option.from(properties.lines()
                                     .map(String::trim)
                                     .filter(line -> line.startsWith(key + "="))
                                     .map(line -> line.substring(key.length() + 1)
                                                      .trim())
                                     .filter(value -> !value.isEmpty() && !value.startsWith("${"))
                                     .findFirst());
    }

    private static Comparison compared(String own, String peer) {
        return own.equals(peer)
               ? Comparison.SAME
               : differing(own, peer);
    }

    /// A `+`-less peer predates build identity (G2, transition rule; end condition at [Comparison]).
    private static Comparison differing(String own, String peer) {
        return peer.contains("+")
               ? judgedOrNot(own, peer)
               : Comparison.PREDATES;
    }

    private static Comparison judgedOrNot(String own, String peer) {
        return judged(own) && judged(peer)
               ? Comparison.SKEWED
               : Comparison.UNJUDGED;
    }

    private static String joined(String projectVersion, String judge) {
        return projectVersion + "+" + shortJudge(judge);
    }

    private static String shortJudge(String judge) {
        return judge.equals(UNJUDGED)
               ? UNJUDGED
               : judge.substring(0, JUDGE_DIGITS) + dirtySuffix(judge);
    }

    private static String dirtySuffix(String judge) {
        return judge.endsWith(DIRTY)
               ? DIRTY
               : "";
    }

    private static Result<String> withinAlphabet(String version) {
        return Verify.ensure(version,
                             Verify.Is::matches,
                             Handshake.VERSION_ALPHABET,
                             cause("version '" + version + "' is outside " + Handshake.VERSION_ALPHABET.pattern()));
    }

    record unused() implements BuildVersion {}
}
