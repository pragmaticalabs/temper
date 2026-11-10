package org.pragmatica.kiln.core.pipeline;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;


/// The job families of the pipeline baseline (#102), matched on the workflow and job names. A name no rule knows is
/// filed under [#OTHER] and reported as unknown ([Filed#known] false), so a renamed shard shows rather than being
/// silently misfiled.
public enum Family {
    /// Workflow `CI`, jobs `test-group-g1`..`g4`, `static-gates`, `stub-suites` and `quarantine` (the ruling's full
    /// reactor). `build-and-test` (now a short aggregator) and `push-dedupe` are not reactor work: they are [#OTHER].
    FULL_REACTOR,
    /// Jobs `ember-shard-*`, and every job of workflow `FSM2 engine (Ember shards)`.
    EMBER,
    /// Job `forge-tests`, and every job of workflow `Heavy forge tests`.
    FORGE,
    /// Jobs `consensus-shard-*`.
    CONSENSUS,
    /// Everything else: workflow `CI`'s `build-and-test` and `push-dedupe`, S3, hierarchy, changelog, catalog, release,
    /// and every unknown name.
    OTHER;
    public static final String CI = "CI";
    public static final String EMBER_WORKFLOW = "FSM2 engine (Ember shards)";
    public static final String FORGE_WORKFLOW = "Heavy forge tests";
    static final Set<String> FULL = Set.of("test-group-g1",
                                           "test-group-g2",
                                           "test-group-g3",
                                           "test-group-g4",
                                           "static-gates",
                                           "stub-suites",
                                           "quarantine");
    /// Workflow `CI`'s jobs that are known but not reactor work, filed under [#OTHER].
    static final Set<String> CI_OTHER = Set.of("push-dedupe", "build-and-test");
    /// The known members of [#OTHER] [guess: the brief names them as S3, hierarchy, changelog, catalog and release, not
    /// by their exact workflow names, so a workflow or job name containing one of the words is known].
    static final Pattern KNOWN_OTHER = Pattern.compile("s3|hierarchy|changelog|catalog|release");
    /// A matrix job's name carries its values after a space: `test-group-g1 (x)` is `test-group-g1`.
    static final Pattern MATRIX = Pattern.compile(" .*$");
    /// A job's family, and whether a rule knew its name.
    public record Filed(Family family, boolean known) {}
    public static Filed filed(String workflow, String job) {
        var base = MATRIX.matcher(job).replaceFirst("");

        if (base.startsWith("ember-shard-") || workflow.equals(EMBER_WORKFLOW)) {
            return new Filed(EMBER, true);
        }

        if (base.equals("forge-tests") || workflow.equals(FORGE_WORKFLOW)) {
            return new Filed(FORGE, true);
        }

        if (base.startsWith("consensus-shard-")) {
            return new Filed(CONSENSUS, true);
        }

        if (workflow.equals(CI) && FULL.contains(base)) {
            return new Filed(FULL_REACTOR, true);
        }

        return new Filed(OTHER,
                         (workflow.equals(CI) && CI_OTHER.contains(base)) || knownOther(workflow, job));
    }
    private static boolean knownOther(String workflow, String job) {
        return KNOWN_OTHER.matcher((workflow + " " + job).toLowerCase(Locale.ROOT)).find();
    }
}
