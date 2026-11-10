package org.pragmatica.temper.acceptance.trust;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// `T1-<date>.md` (m1-design §8.1 step 5), pure: one section per SHA with ✔/✘ per check, the
/// diffs, the job id, the facts at start and end, and the overall verdict. A SHA with a fork crash on either side is
/// NOT COMPARABLE (ruling R-14): it is listed with its crash lines, its missing classes and a prompt for their
/// explanation, never passes, and is left out of the count. The criterion holds only when every other SHA was judged
/// and passed, at least [#MIN_SHAS] such comparable SHAs were (the criterion's "≥ 3 SHAs"), and at least one of them
/// is the planted red: bb's `FAILED:` set contains the planted class, the test the red SHA deliberately breaks (maintainer
/// ruling "T1 red", m1-design §14; a red SHA with a fork crash does not count, F7; rc ≠ 0 alone never counts). A
/// comparable SHA on which temper and bb disagree is ✘ and counted, whatever class failed, so it always fails T1 (a
/// missed red is what T1 exists to catch; reading "T1 disagreement always fails", m1-design §14). A red on which they
/// agree from any other class, instead of the planted one or beside it, is NOT THE PLANTED RED: listed with those
/// classes and a prompt for their explanation, and never T1's red. A SHA red from other classes only is, like a
/// crashed one, left out of the count ("explained, and not counted"); one red from the planted class beside others
/// still counts as the planted red. T1 PASSES only when the criterion holds and no crash or red explanation is left
/// [#UNWRITTEN]; with one left it is INCOMPLETE (F3).
///
/// A SHA whose job never got to run (still QUEUED at `--queue-hours`, or at a `--rerun`) is NOT COMPARABLE (capacity)
/// (owner ruling, option A): never judged, never ✘, and left out of the count like a crashed one, but with nothing to
/// explain, so it never makes the report INCOMPLETE. The report counts it apart from the fork crashes, as a capacity signal.
public sealed interface TrustReport {
    int MIN_SHAS = 3;
    String PASS = "✔";
    String FAIL = "✘";
    String NOT_COMPARABLE = "NOT COMPARABLE (fork crash)";
    String NOT_COMPARABLE_CAPACITY = "NOT COMPARABLE (capacity)";
    String NOT_PLANTED = "NOT THE PLANTED RED";
    String UNWRITTEN = "_to be written by the reviewer; never waved away_";

    static String t1(LocalDate date, String planted, List<ShaRun> runs, boolean rerun) {
        return Stream.of(Stream.of("# T1 trust gate, " + date + (rerun
                                                                 ? " (re-run of step 4 from the snapshots)"
                                                                 : ""),
                                   "",
                                   "**Overall: " + overall(planted, runs)
                                  + "** (" + runs.size()
                                  + " SHAs, " + passedCount(runs)
                                  + " passed, " + crashedCount(runs)
                                  + " not comparable (fork crash, not counted), " + starvedCount(runs)
                                  + " not comparable (capacity, not counted), " + notPlantedCount(planted, runs)
                                  + " not the planted red (not counted); T1 needs >= " + MIN_SHAS
                                  + " comparable SHAs, every one passed, at least one of them the planted red).",
                                   "",
                                   "- planted red class: `" + planted + "`",
                                   ""),
                         redNeeded(planted, runs),
                         unwritten(runs),
                         unexplainedReds(planted, runs),
                         runs.stream().flatMap(run -> section(planted, run)))
                     .flatMap(lines -> lines)
                     .collect(Collectors.joining("\n", "", "\n"));
    }

    static String overall(String planted, List<ShaRun> runs) {
        return criterionHolds(planted, runs)
               ? explained(planted, runs)
               : "FAIL";
    }

    private static boolean criterionHolds(String planted, List<ShaRun> runs) {
        var counted = runs.stream().filter(run -> run.counted(planted)).toList();

        return counted.size() >= MIN_SHAS
               && counted.stream()
                         .allMatch(ShaRun::passed)
               && counted.stream()
                         .anyMatch(run -> run.plantedRed(planted));
    }

    /// F3: one crashed SHA, or one red that is not the planted one, leaves one explanation [#UNWRITTEN] in the report
    /// the harness writes.
    private static String explained(String planted, List<ShaRun> runs) {
        return crashedCount(runs) + otherRedCount(planted, runs) == 0
               ? "PASS"
               : "INCOMPLETE";
    }

    /// F7 and the maintainer ruling "T1 red": the spec's "including ≥ 1 red" is judged, and only the planted class counts; a
    /// red SHA with a fork crash, rc ≠ 0 without a `FAILED:` class, or a red from another class does not.
    private static Stream<String> redNeeded(String planted, List<ShaRun> runs) {
        return runs.stream()
                   .anyMatch(run -> run.plantedRed(planted))
               ? Stream.empty()
               : Stream.of("- **T1 needs a comparable red SHA** (spec T1: \"including >= 1 red\"): no comparable SHA is the"
                          + " planted red (bb's FAILED classes include `" + planted
                          + "`); rc != 0 without a FAILED class, a red from another class and a red SHA with a fork crash"
                          + " do not count. Add one.",
                           "");
    }

    /// The ruling's "explained and not counted": like a crash, a red from another class keeps T1 from PASS until its
    /// explanation is written.
    private static Stream<String> unexplainedReds(String planted, List<ShaRun> runs) {
        var pending = otherRedCount(planted, runs);

        return pending == 0
               ? Stream.empty()
               : Stream.of("- **" + pending
                          + " red explanation(s) unwritten:** a SHA red from a class other than the planted one is " + NOT_PLANTED
                          + "; T1 is never PASS while its explanation below is unwritten.",
                           "");
    }

    private static long otherRedCount(String planted, List<ShaRun> runs) {
        return runs.stream()
                   .filter(run -> run.otherRed(planted))
                   .count();
    }

    /// F3: the report says what keeps it from PASS; the harness never rewrites an existing report (T1Harness). The
    /// placeholder itself appears only in the explanations, so its absence means every one is written.
    private static Stream<String> unwritten(List<ShaRun> runs) {
        var pending = crashedCount(runs);

        return pending == 0
               ? Stream.empty()
               : Stream.of("- **" + pending
                          + " crash explanation(s) unwritten:** T1 is never PASS while an explanation"
                          + " below is unwritten (INCOMPLETE when the criterion holds otherwise). Write each one in this"
                          + " file, then set the overall verdict; a later harness run writes a new report alongside, never"
                          + " over this one.",
                           "");
    }

    private static long crashedCount(List<ShaRun> runs) {
        return runs.stream()
                   .filter(ShaRun::crashed)
                   .count();
    }

    private static long starvedCount(List<ShaRun> runs) {
        return runs.stream()
                   .filter(ShaRun::starved)
                   .count();
    }

    private static long notPlantedCount(String planted, List<ShaRun> runs) {
        return runs.stream()
                   .filter(run -> run.notPlantedRed(planted))
                   .count();
    }

    private static long passedCount(List<ShaRun> runs) {
        return runs.stream()
                   .filter(ShaRun::passed)
                   .count();
    }

    private static Stream<String> section(String planted, ShaRun run) {
        return Stream.of(Stream.of("## " + run.sha() + " " + heading(planted, run),
                                   "",
                                   "- snapshot: `" + run.dir() + "`",
                                   "- job: " + run.job().map(String::valueOf).or("none")),
                         run.preflight().map(TrustReport::preflight).or(Stream.of("- preflight: not re-run (snapshot)")),
                         run.failure().map(failure -> Stream.of("- **stopped:** " + failure)).or(Stream.empty()),
                         run.capacity().map(TrustReport::capacity).or(Stream.empty()),
                         run.judged().map(TrustReport::judged).or(Stream.empty()),
                         notPlanted(planted, run),
                         Stream.of(""))
                     .flatMap(lines -> lines);
    }

    /// A SHA red from other classes only keeps its ✔/✘ and is marked out of the count.
    private static String heading(String planted, ShaRun run) {
        return run.notPlantedRed(planted)
               ? comparisonHeading(run) + " " + NOT_PLANTED + " (not counted)"
               : comparisonHeading(run);
    }

    private static String comparisonHeading(ShaRun run) {
        return run.passed()
               ? PASS
               : notPassedHeading(run);
    }

    private static String notPassedHeading(ShaRun run) {
        return run.crashed()
               ? NOT_COMPARABLE
               : notCrashedHeading(run);
    }

    private static String notCrashedHeading(ShaRun run) {
        return run.starved()
               ? NOT_COMPARABLE_CAPACITY
               : FAIL;
    }

    /// Option A: why the SHA is out of the count; its job is left as it is, and a later `--rerun` judges it once the
    /// job reached a final state ([T1Steps#rerun]).
    private static Stream<String> capacity(String capacity) {
        return Stream.of("- **not comparable (capacity):** " + capacity
                        + "; not judged, never ✘, and does not count toward the " + MIN_SHAS
                        + " SHAs. The job is left as it is (not cancelled): a later `--rerun` judges it once it reached a final state.");
    }

    private static Stream<String> preflight(Preflight preflight) {
        return Stream.of("- preflight " + mark(preflight.comparable())
                        + ": HEAD " + preflight.head()
                                               .or("unreadable")
                        + " " + mark(preflight.headMatches())
                        + ", .bb/args default " + mark(preflight.defaultArgs())
                        + ", .bb/rc " + mark(preflight.rcPresent())
                        + ", XML newer than run.marker " + mark(preflight.reportXmlPresent()));
    }

    private static Stream<String> judged(ShaRun.Judged judged) {
        var bb = judged.bb();
        var counts = judged.temper().result().counts();
        var comparison = judged.comparison();

        return Stream.of(Stream.of("",
                                   "| Check | bb | temper | |",
                                   "|---|---|---|---|",
                                   "| `<testcase>` count | " + bb.testcases()
                                  + " (tests= " + bb.testsAttr()
                                  + ") | " + counts.testcases()
                                  + " (tests= " + counts.testsAttr()
                                  + ") | " + mark(comparison.countsEqual())
                                  + " |",
                                   "| failed classes | " + bb.failedClasses()
                                                             .size()
                                  + " | " + temperFailedCount(bb, comparison)
                                  + " | " + mark(comparison.failedEqual())
                                  + " |",
                                   "| EMPTY | exit " + (bb.empty()
                                                        ? "3"
                                                        : "0")
                                  + " | " + judged.temper()
                                                  .result()
                                                  .verdict()
                                  + " | " + mark(comparison.emptyEqual())
                                  + " |",
                                   "| manifest | | computed=" + comparison.manifestComputed()
                                  + " missing=" + comparison.missing()
                                                            .size()
                                  + " drift=" + comparison.drift()
                                                          .size()
                                  + " | " + mark(comparison.manifestAgrees())
                                  + " |",
                                   "",
                                   "- bb rc " + bb.rc() + (bb.dependencyResolutionWarning()
                                                           ? ", **dependency-resolution WARNING**"
                                                           : "")),
                         listed("failed only in bb",
                                comparison.failedOnlyBb()),
                         listed("failed only in temper",
                                comparison.failedOnlyTemper()),
                         listed("missing",
                                comparison.missing()),
                         listed("drift",
                                comparison.drift()),
                         notComparable(comparison),
                         crash("fork crash in temper's log tail",
                               comparison.crashEvidence()),
                         crash("fork crash in bb's build log",
                               comparison.bbCrashEvidence()),
                         block("facts at start",
                               judged.factsStart()),
                         block("facts at end",
                               judged.factsEnd()),
                         block("case-level diff (diagnostic, at most " + TrustComparison.MAX_DIFF_LINES + " lines)",
                               String.join("\n",
                                           comparison.caseLevelDiff())))
                     .flatMap(lines -> lines);
    }

    private static Stream<String> listed(String title, Collection<String> names) {
        return names.isEmpty()
               ? Stream.empty()
               : Stream.of("- " + title + ": " + names.stream().sorted().collect(Collectors.joining(", ")));
    }

    private static Stream<String> crash(String title, Option<String> evidence) {
        return evidence.map(lines -> block(title, lines))
                       .or(Stream.empty());
    }

    /// R-14: why the SHA is out of the count, and the prompt for the explanation a human writes into the report.
    private static Stream<String> notComparable(ShaComparison comparison) {
        return comparison.comparable()
               ? Stream.empty()
               : Stream.of("- **" + NOT_COMPARABLE
                          + "** (R-14): a fork crash in " + crashSides(comparison)
                          + "; this SHA never passes and does not count toward the " + MIN_SHAS
                          + " SHAs. Crashed forks are T2's subject (§8.2).",
                           "- **explain** the crash below and each missing class (" + missingList(comparison)
                          + "): " + UNWRITTEN);
    }

    /// The ruling "T1 red": why this red is out of the count, and the prompt for its explanation.
    private static Stream<String> notPlanted(String planted, ShaRun run) {
        return run.otherRed(planted)
               ? Stream.of("- **" + NOT_PLANTED
                          + "**: bb's FAILED classes other than the planted `" + planted
                          + "` (" + otherFailed(planted, run)
                          + "); " + notPlantedCounting(planted, run),
                           "- **explain** why each of these classes failed: " + UNWRITTEN)
               : Stream.empty();
    }

    private static String notPlantedCounting(String planted, ShaRun run) {
        return run.notPlantedRed(planted)
               ? "this SHA is not T1's red and does not count toward the " + MIN_SHAS + " SHAs."
               : "the planted red still counts; these classes are explained, not T1's red.";
    }

    private static String otherFailed(String planted, ShaRun run) {
        return String.join(", ",
                           run.judged()
                              .map(found -> found.bb()
                                                 .failedClasses()
                                                 .stream()
                                                 .filter(failed -> !failed.equals(planted))
                                                 .sorted()
                                                 .toList())
                              .or(List.of()));
    }

    private static String missingList(ShaComparison comparison) {
        return comparison.missing()
                         .isEmpty()
               ? "none"
               : String.join(", ",
                             comparison.missing().stream().sorted().toList());
    }

    private static String crashSides(ShaComparison comparison) {
        return Stream.of(comparison.crashEvidence().map(_ -> "temper's log tail"),
                         comparison.bbCrashEvidence().map(_ -> "bb's build log"))
                     .flatMap(Option::stream)
                     .collect(Collectors.joining(" and "));
    }

    private static Stream<String> block(String title, String text) {
        return Stream.of("", title + ":", "", "```", text.strip(), "```");
    }

    /// The size of temper's failed set ([TrustComparison#failedClasses]), from bb's and the two differences:
    /// |temper| = |bb| − |bb − temper| + |temper − bb|.
    private static int temperFailedCount(BbReport bb, ShaComparison comparison) {
        var both = bb.failedClasses().size() - comparison.failedOnlyBb().size();

        return both + comparison.failedOnlyTemper()
                                .size();
    }

    private static String mark(boolean passed) {
        return passed
               ? PASS
               : FAIL;
    }

    record unused() implements TrustReport {}
}
