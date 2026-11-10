package org.pragmatica.temper.worker.unit;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.Message;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// A parsed `StartUnit` (parse, don't validate): every value that reaches a docker argv or a host path is
/// checked here first.
///
/// - `runnerVersion` becomes a docker tag and a directory name, so it is restricted to a docker-tag-safe,
///   traversal-free shape (`+` is not allowed in a docker tag, unlike the `worker-launch` version pattern).
/// - `cpuShares` is docker's `--cpu-shares` range.
/// - `steps` must be non-empty lists of non-empty argv. The worker does not judge their content: the refusals
///   (R-SHELL, R-M2, ...) are applied at submit by the controller.
/// - `portGroup` (protocol v2): present, the unit starts through bb-hold ([HoldRequest]).
/// - `manifest` (protocol v2): present, the effective-POM probe runs before the unit and the manifest is computed
///   at exit (m1-design §2.3, §2.5).
/// - `instance`: the controller instance, the container's `temper.instance` label (F2); a docker label value.
/// - `attempt` (protocol v3, m4-design §4.2): the store's grant count of the unit, at least 1; written to
///   `.temper/attempt` and the container's `temper.attempt` label.
public record StartRequest(UnitRef ref,
                           int attempt,
                           String kind,
                           List<List<String>> steps,
                           Duration timeout,
                           MemoryMb memory,
                           int cpuShares,
                           String runnerVersion,
                           Option<String> mavenOpts,
                           Option<String> tcHubPrefix,
                           Option<HoldRequest> portGroup,
                           Option<ManifestSpec> manifest,
                           InstanceId instance) {
    public static final Pattern RUNNER_VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,39}$");
    public static final int MIN_CPU_SHARES = 2;
    public static final int MAX_CPU_SHARES = 262_144;

    public StartRequest {
        steps = steps.stream().map(List::copyOf).toList();
    }

    public static Result<StartRequest> startRequest(Message.StartUnit start) {
        return Result.all(UnitRefs.unitRef(start.job(),
                                           start.unit()),
                          Verify.ensure(start.attempt(),
                                        Verify.Is::positive,
                                        invalid("attempt")),
                          validSteps(start.steps()),
                          Verify.ensure(start.timeout(),
                                        StartRequest::positive,
                                        invalid("timeout")),
                          MemoryMb.memoryMb(start.memoryMb()),
                          Verify.ensure(start.cpuShares(),
                                        Verify.Is::between,
                                        MIN_CPU_SHARES,
                                        MAX_CPU_SHARES,
                                        invalid("cpuShares")),
                          Values.matching(start.runnerVersion(),
                                          RUNNER_VERSION,
                                          "runnerVersion"),
                          Values.optional(option(start.portGroup()).flatMap(hold -> hold),
                                          HoldRequest::holdRequest),
                          Values.optional(option(start.manifest()).flatMap(spec -> spec),
                                          ManifestSpecDto::toDomain),
                          InstanceId.instanceId(start.instance()))
                     .map((ref, attempt, steps, timeout, memory, shares, version, hold, spec, instance) -> new StartRequest(ref,
                                                                                                                            attempt,
                                                                                                                            start.kind(),
                                                                                                                            steps,
                                                                                                                            timeout,
                                                                                                                            memory,
                                                                                                                            shares,
                                                                                                                            version,
                                                                                                                            start.mavenOpts(),
                                                                                                                            start.tcHubPrefix(),
                                                                                                                            hold,
                                                                                                                            spec,
                                                                                                                            instance));
    }

    /// A Gate unit (`kind` `GATE_UNSHARDED`, the controller's own test in `HostSnapshot.holdsPortGroup`) runs on the
    /// host network ([UnitNetwork]).
    public boolean gate() {
        return UnitKind.GATE_UNSHARDED.name().equals(kind);
    }

    private static boolean positive(Duration duration) {
        return option(duration).filter(Duration::isPositive)
                     .isPresent();
    }

    private static Result<List<List<String>>> validSteps(List<List<String>> steps) {
        return Verify.ensure(steps, StartRequest::wellFormed, invalid("steps"));
    }

    private static boolean wellFormed(List<List<String>> steps) {
        return option(steps).filter(Predicate.not(List::isEmpty))
                     .filter(StartRequest::everyStepWellFormed)
                     .isPresent();
    }

    private static boolean wellFormedStep(List<String> step) {
        return option(step).filter(Predicate.not(List::isEmpty))
                     .filter(StartRequest::everyArgumentNonEmpty)
                     .isPresent();
    }

    private static boolean everyStepWellFormed(List<List<String>> steps) {
        return steps.stream()
                    .allMatch(StartRequest::wellFormedStep);
    }

    private static boolean everyArgumentNonEmpty(List<String> step) {
        return step.stream()
                   .allMatch(StartRequest::nonEmpty);
    }

    private static boolean nonEmpty(String arg) {
        return option(arg).filter(Predicate.not(String::isEmpty))
                     .isPresent();
    }
}
