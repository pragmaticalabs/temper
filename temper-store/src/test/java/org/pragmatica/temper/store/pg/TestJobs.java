package org.pragmatica.temper.store.pg;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.facts.UnitEndFacts;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.MarkerEpoch;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.domain.verdict.VerdictInput;
import org.pragmatica.temper.domain.verdict.Verdicts;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobFinish;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.StoredEvidence;
import org.pragmatica.temper.store.api.UnitFinishRow;
import org.pragmatica.temper.store.api.UnitManifestRows;
import org.pragmatica.temper.store.api.UnitStartedRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;

/// Test data for the store ITs.
final class TestJobs {
    static final HostName HOST = HostName.hostName("bigboy").unwrap();
    static final HostConfig HOST_CONFIG = HostConfig.hostConfig(HOST, "localhost", none(), 5, 20480, false).unwrap();

    private TestJobs() {}

    static String randomHex(int length) {
        var bytes = new byte[length / 2];

        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static Sha sha() {
        return Sha.sha(randomHex(40)).unwrap();
    }

    static DedupKey key() {
        return DedupKey.dedupKey(randomHex(64)).unwrap();
    }

    static UnitSpec unit(String name) {
        return UnitSpec.unitSpec(UnitName.unitName(name).unwrap(),
                                 UnitKind.MVN,
                                 List.of(List.of("mvn", "-B", "verify", "-Dmaven.repo.local=${ws}/.m2-local")),
                                 true,
                                 Duration.ofMinutes(60),
                                 MemoryMb.memoryMb(6144).unwrap(),
                                 false,
                                 none(),
                                 SideEffects.NONE);
    }

    /// A job submitted by SHA (never superseded).
    static NewJob bySha(DedupKey key, boolean fresh) {
        var sha = sha();

        return NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                             Priority.priority(none()).unwrap(),
                             false,
                             fresh,
                             new SourceRequest.BySha(sha),
                             some(PushedSource.pushedSource(none(), sha)),
                             Recipe.mvn(List.of("verify")).unwrap(),
                             some(key),
                             List.of(unit("main")));
    }

    /// A job submitted by ref at `sha`.
    static NewJob byRef(String ref, Sha sha, boolean keep, int priority) {
        return byRef(ref, sha, keep, priority, Recipe.mvn(List.of("verify")).unwrap());
    }

    /// A job submitted by ref at `sha` running `recipe`.
    static NewJob byRef(String ref, Sha sha, boolean keep, int priority, Recipe recipe) {
        var parsed = Ref.ref(ref).unwrap();

        return NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                             Priority.priority(some(priority)).unwrap(),
                             keep,
                             false,
                             new SourceRequest.ByRef(parsed),
                             some(PushedSource.pushedSource(some(parsed), sha)),
                             recipe,
                             some(key()),
                             List.of(unit("main")));
    }

    /// A Gate (recipe `gate`, one unit `main`) submitted by SHA at `sha`.
    static NewJob gateBySha(Sha sha) {
        return NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                             Priority.priority(none()).unwrap(),
                             false,
                             false,
                             new SourceRequest.BySha(sha),
                             some(PushedSource.pushedSource(none(), sha)),
                             Recipe.gate(),
                             some(key()),
                             List.of(unit("main")));
    }

    /// A Gate submitted by ref `ref` at `sha`; a later one by the same ref at another SHA supersedes it.
    static NewJob gateByRef(String ref, Sha sha, boolean keep) {
        var parsed = Ref.ref(ref).unwrap();

        return NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                             Priority.priority(none()).unwrap(),
                             keep,
                             false,
                             new SourceRequest.ByRef(parsed),
                             some(PushedSource.pushedSource(some(parsed), sha)),
                             Recipe.gate(),
                             some(key()),
                             List.of(unit("main")));
    }

    /// A module test at `sha` (recipe kind `moduleTest`): never a Gate candidate.
    static NewJob moduleTestBySha(Sha sha) {
        return NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                             Priority.priority(none()).unwrap(),
                             false,
                             false,
                             new SourceRequest.BySha(sha),
                             some(PushedSource.pushedSource(none(), sha)),
                             Recipe.moduleTest(List.of("core"), none(), none()).unwrap(),
                             some(key()),
                             List.of(unit("main")));
    }

    /// One passed testcase: a PASSED unit with rc 0.
    static List<TestcaseRow> passing() {
        return List.of(row("a.ATest", "one", Outcome.PASSED));
    }

    /// One failed testcase: with rc 1, a FAILED unit.
    static List<TestcaseRow> failing() {
        return List.of(row("a.ATest", "one", Outcome.FAILED), row("a.ATest", "two", Outcome.PASSED));
    }

    /// A module-test recipe over `pl`, no test selection.
    static Recipe moduleTest(String... pl) {
        return Recipe.moduleTest(List.of(pl), none(), none()).unwrap();
    }

    static UnitRef main(JobId job) {
        return UnitRef.unitRef(job, UnitName.MAIN);
    }

    static final TcLeftovers LEFTOVERS = TcLeftovers.tcLeftovers(List.of(TcLeftovers.TcLeftover.tcLeftover("tc-left", "s-9")), Option.none());

    static HostFacts facts() {
        return HostFacts.hostFacts(1.5, 1.2, 32, 0, 1, none(), none(), 1_000_000L, 100L << 30, 200L << 30,
                                   Instant.parse("2026-10-13T10:00:00.123456Z"), List.of());
    }

    static UnitStartedRow startedRow() {
        return UnitStartedRow.unitStartedRow("c0ffee", "sha256:abc", MarkerEpoch.markerEpoch("1760349600000000000").unwrap(),
                                             Instant.parse("2026-10-13T10:01:00Z"), facts(), none());
    }

    static UnitFinishRow exited(int rc) {
        return exited(rc, LEFTOVERS);
    }

    static UnitFinishRow exited(int rc, TcLeftovers leftovers) {
        return UnitFinishRow.unitFinishRow(some(rc),
                                           none(),
                                           Instant.parse("2026-10-13T10:31:00Z"),
                                           some(UnitEndFacts.unitEndFacts(facts(), some(4L << 30),
                                                                          Instant.parse("2026-10-13T10:30:55Z"),
                                                                          Map.of("usage_usec", 123L),
                                                                          leftovers,
                                                                          none())),
                                           3,
                                           none(),
                                           none());
    }

    static UnitFinishRow killed(KillCause cause) {
        return UnitFinishRow.unitFinishRow(some(137), some(cause), Instant.parse("2026-10-13T10:31:00Z"), none(), 0, none(), none());
    }

    static UnitFinishRow lost() {
        return UnitFinishRow.unitFinishRow(none(), none(), Instant.parse("2026-10-13T10:31:00Z"), none(), 0, none(), some("lost"));
    }

    static TestcaseRow row(String className, String name, Outcome outcome) {
        return TestcaseRow.testcaseRow(UnitName.MAIN, className, name, outcome, 0.25, false);
    }

    /// The manifest spec of a Gate-like unit (m1-design §2.2).
    static final ManifestSpec SPEC = ManifestSpec.manifestSpec(List.of("mvn", "-B", ManifestSpec.EFFECTIVE_POM_GOAL,
                                                                       "-Doutput=${ws}/.temper/effective-pom.xml",
                                                                       "-Dmaven.repo.local=${ws}/.m2-local"),
                                                               Reach.INTEGRATION_TEST,
                                                               Map.of("skipITs", "false"),
                                                               none(),
                                                               some("*IT"));

    /// A unit with a manifest spec; `portGroup` makes it a hold unit.
    static UnitSpec specUnit(String name, boolean portGroup) {
        return UnitSpec.unitSpec(UnitName.unitName(name).unwrap(),
                                 UnitKind.MVN,
                                 List.of(List.of("mvn", "-B", "verify", "-Dmaven.repo.local=${ws}/.m2-local")),
                                 true,
                                 Duration.ofMinutes(60),
                                 MemoryMb.memoryMb(6144).unwrap(),
                                 portGroup,
                                 some(SPEC),
                                 SideEffects.NONE);
    }

    /// An unsharded Gate that needs the port group (ruling R-3).
    static UnitSpec gateUnit() {
        return UnitSpec.unitSpec(UnitName.GATE,
                                 UnitKind.GATE_UNSHARDED,
                                 List.of(List.of("mvn", "-B", "clean", "install", "-Dmaven.repo.local=${ws}/.m2-local")),
                                 true,
                                 Duration.ofHours(3),
                                 MemoryMb.memoryMb(16384).unwrap(),
                                 true,
                                 some(SPEC),
                                 SideEffects.NONE);
    }

    /// A job submitted by SHA with these units.
    static NewJob withUnits(List<UnitSpec> units, boolean keep) {
        var sha = sha();

        return NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                             Priority.priority(some(7)).unwrap(),
                             keep,
                             false,
                             new SourceRequest.BySha(sha),
                             some(PushedSource.pushedSource(none(), sha)),
                             Recipe.mvn(List.of("verify")).unwrap(),
                             some(key()),
                             units);
    }

    static UnitRef ref(JobId job, String unit) {
        return UnitRef.unitRef(job, UnitName.unitName(unit).unwrap());
    }

    static TestcaseRow row(String unit, String className, String name, Outcome outcome) {
        return TestcaseRow.testcaseRow(UnitName.unitName(unit).unwrap(), className, name, outcome, 0.25, false);
    }

    static MarkerEpoch marker() {
        return MarkerEpoch.markerEpoch("1760349500000000000").unwrap();
    }

    static UnitStartedRow startedRow(Option<String> ticket) {
        return UnitStartedRow.unitStartedRow("c0ffee", "sha256:abc", MarkerEpoch.markerEpoch("1760349600000000000").unwrap(),
                                             Instant.parse("2026-10-13T10:01:00Z"), facts(), ticket);
    }

    /// A tarball at the §3.1 layout under `/ev`.
    static StoredEvidence evidence(JobId job, String unit, String file, long bytes) {
        return StoredEvidence.storedEvidence(Path.of("/ev/jobs/" + job.value() + "/units/" + unit + "/" + file),
                                             bytes,
                                             randomHex(64));
    }

    /// A computed manifest expecting `classes` of module `mod`, run by surefire.
    static UnitManifestRows computed(String... classes) {
        return UnitManifestRows.unitManifestRows(ManifestOutcome.Computed.computed(Arrays.stream(classes)
                                                                                         .map(name -> ManifestEntry.manifestEntry("mod",
                                                                                                                                  name,
                                                                                                                                  Runner.SUREFIRE))
                                                                                         .collect(Collectors.toSet()),
                                                                                   Set.of(),
                                                                                   List.of(),
                                                                                   Set.of()));
    }

    /// The production decision: `Verdicts.evaluate`.
    static Option<JobFinish> evaluate(VerdictInput input) {
        return option(JobFinish.jobFinish(Verdicts.evaluate(input)));
    }
}
