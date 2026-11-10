package org.pragmatica.temper.domain.rerun;

import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.ModulePath;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.state.JobState;

import static org.pragmatica.lang.Result.success;


/// The preconditions of `temper rerun <job>` (m2-design §5.1, ruling R-M2-4), checked on the submit path. Each failure
/// is `REFUSED(rerun)` with a detail; a missing parent is the caller's 404. In order:
///
/// 1. The parent's recipe is `gate` or `module-test` (a rerun job is never a parent) and the parent is terminal.
/// 2. Its verdict is `FAILED(tests)` [G]: `missing-classes` is a crash, `rc` and `timeout` are not flakes.
/// 3. The targets are the classes of its record's `failed[]` rows that are not all marked KNOWN: 1 to [#MAX_CLASSES]
///    classes, counted as classes, not cases. The marks are the record's (ruling R-M2-9, Q-M2-24), so the target set is
///    fixed and a repeat request deduplicates.
/// 4. Each target has one module and runner in the parent's manifest (required or optional entries) and is a binary
///    class name `-Dtest` can select. A pruned manifest (90 d) has no entries, so its classes have no module.
public sealed interface RerunPreconditions {
    int MAX_CLASSES = 3;

    /// What the precondition reads of the parent: the job row's state and recipe, its result record once terminal,
    /// and its manifest entries (empty without a manifest, or once pruned).
    record Parent(JobId job, JobState state, Recipe recipe, Option<Judged> record, List<ManifestEntry> manifest) {
        public Parent {
            manifest = List.copyOf(manifest);
        }

        public static Parent parent(JobId job,
                                    JobState state,
                                    Recipe recipe,
                                    Option<Judged> record,
                                    List<ManifestEntry> manifest) {
            return new Parent(job, state, recipe, record, manifest);
        }
    }

    /// The parent's result record as the precondition reads it: verdict, reason and every `failed[]` row with its mark.
    record Judged(String verdict, Option<String> reason, List<Recipe.Rerun.Failure> failed) {
        public Judged {
            failed = List.copyOf(failed);
        }

        public static Judged judged(String verdict, Option<String> reason, List<Recipe.Rerun.Failure> failed) {
            return new Judged(verdict, reason, failed);
        }
    }

    /// The rerun recipe of `parent`, or `REFUSED(rerun)` naming the first precondition it fails.
    static Result<Recipe.Rerun> rerun(Parent parent) {
        return rerunnable(parent).flatMap(RerunPreconditions::failedTests)
                         .flatMap(judged -> targeted(parent, judged));
    }

    private static Result<Parent> rerunnable(Parent parent) {
        return switch (parent.recipe()) {
            case Recipe.Gate _, Recipe.ModuleTest _ -> terminal(parent);
            case Recipe.Rerun _ -> refused("job " + parent.job() + " is a rerun; a rerun is never rerun");
            case Recipe.Mvn _, Recipe.Unsupported _ -> refused("job " + parent.job() + " is a " + parent.recipe().kind() + " job; only gate and module-test jobs are rerun");
        };
    }

    private static Result<Parent> terminal(Parent parent) {
        return parent.state()
                     .isTerminal()
               ? success(parent)
               : refused("job " + parent.job() + " is " + parent.state() + ", not terminal");
    }

    private static Result<Judged> failedTests(Parent parent) {
        return parent.record()
                     .filter(RerunPreconditions::isFailedTests)
                     .toResult(refusal("job " + parent.job() + " is " + outcome(parent) + ", not FAILED(tests)"));
    }

    private static boolean isFailedTests(Judged judged) {
        return judged.verdict()
                     .equals("FAILED") && judged.reason()
                                                .filter("tests"::equals)
                                                .isPresent();
    }

    private static String outcome(Parent parent) {
        return parent.record()
                     .map(RerunPreconditions::outcome)
                     .or(parent.state().name());
    }

    private static String outcome(Judged judged) {
        return judged.verdict() + judged.reason()
                                        .map(reason -> "(" + reason + ")")
                                        .or("");
    }

    private static Result<Recipe.Rerun> targeted(Parent parent, Judged judged) {
        return counted(targetClasses(judged)).flatMap(classes -> locatedAll(parent.manifest(),
                                                                            classes))
                      .map(targets -> Recipe.rerun(parent.job(),
                                                   targets,
                                                   original(parent.job(),
                                                            judged,
                                                            targets)));
    }

    /// The failing classes not every row of which is KNOWN (a class with one unmarked failed row), sorted.
    static List<String> targetClasses(Judged judged) {
        return List.copyOf(unmarkedClasses(judged));
    }

    private static TreeSet<String> unmarkedClasses(Judged judged) {
        return judged.failed()
                     .stream()
                     .filter(RerunPreconditions::unmarked)
                     .map(Recipe.Rerun.Failure::className)
                     .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean unmarked(Recipe.Rerun.Failure failure) {
        return failure.known()
                      .isEmpty();
    }

    private static Result<List<String>> counted(List<String> classes) {
        return classes.isEmpty()
               ? refused("all failing classes are KNOWN")
               : bounded(classes);
    }

    private static Result<List<String>> bounded(List<String> classes) {
        return classes.size() <= MAX_CLASSES
               ? success(classes)
               : refused(classes.size() + " failing classes (max " + MAX_CLASSES + ")");
    }

    /// Class by class, the first that fails refusing the rerun with its own refusal, never a composite of several: the
    /// submit path persists a refusal only when the cause is one. (At most three classes, so the recursion is shallow.)
    private static Result<List<Recipe.Rerun.Target>> locatedAll(List<ManifestEntry> manifest, List<String> classes) {
        return classes.isEmpty()
               ? success(List.of())
               : target(manifest, classes.getFirst()).flatMap(first -> followedBy(first,
                                                                                  manifest,
                                                                                  classes.subList(1, classes.size())));
    }

    private static Result<List<Recipe.Rerun.Target>> followedBy(Recipe.Rerun.Target first,
                                                                List<ManifestEntry> manifest,
                                                                List<String> rest) {
        return locatedAll(manifest, rest).map(others -> Stream.concat(Stream.of(first),
                                                                      others.stream())
                                                              .toList());
    }

    /// Per class (a refusal names one class): selectable, then exactly one module and runner.
    private static Result<Recipe.Rerun.Target> target(List<ManifestEntry> manifest, String className) {
        return Recipe.Rerun.Target.selectable(className)
               ? locatedOne(className, entriesOf(manifest, className))
               : refused(className + " is not a class name -Dtest can select");
    }

    private static List<ManifestEntry> entriesOf(List<ManifestEntry> manifest, String className) {
        return manifest.stream()
                       .filter(candidate -> candidate.className()
                                                     .equals(className))
                       .distinct()
                       .toList();
    }

    private static Result<Recipe.Rerun.Target> locatedOne(String className, List<ManifestEntry> entries) {
        return switch (entries.size()) {
            case 0 -> refused("no module for " + className);
            case 1 -> inModule(className, entries.getFirst());
            default -> refused(className + " is in " + entries.size() + " modules or runners");
        };
    }

    /// The manifest's module directory must be a `-pl` path; the refusal is decided here, before a parse could fail.
    private static Result<Recipe.Rerun.Target> inModule(String className, ManifestEntry entry) {
        return ModulePath.isModulePath(entry.module())
               ? Recipe.Rerun.Target.target(className, entry.module(), entry.runner())
               : refused("module " + entry.module() + " of " + className + " is not a -pl path");
    }

    /// The parent's verdict and reason, and its failed rows of the target classes with their marks.
    private static Recipe.Rerun.Original original(JobId job, Judged judged, List<Recipe.Rerun.Target> targets) {
        var classes = targets.stream().map(Recipe.Rerun.Target::className).collect(Collectors.toUnmodifiableSet());

        return Recipe.Rerun.Original.original(job,
                                              judged.verdict(),
                                              judged.reason(),
                                              judged.failed()
                                                    .stream()
                                                    .filter(failure -> classes.contains(failure.className()))
                                                    .toList());
    }

    private static <T> Result<T> refused(String detail) {
        return refusal(detail).result();
    }

    private static Refusal refusal(String detail) {
        return Refusal.refusal(RefusalCode.RERUN, detail);
    }

    record unused() implements RerunPreconditions {}
}
