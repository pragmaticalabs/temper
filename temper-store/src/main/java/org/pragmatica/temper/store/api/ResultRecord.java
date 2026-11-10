package org.pragmatica.temper.store.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.verdict.Counts;


/// The immutable result record (spec §5.4) as the application reads it: `results.record`, served by
/// `GET /jobs/{id}/result`. It mirrors the wire record member for member (ruling R-10), so a stored record read
/// through it and written out again is the same JSON; the store's adapter maps it to and from the wire form.
/// `manifest` (M1, m1-design §2.5) is present when a unit of the job had a manifest spec. `reasonDetail` is the
/// free text after the verdict reason's token. `judge` (M2, m2-design §1.7) is the build of the controller that wrote
/// it, absent in a record written before the judge existed.
public record ResultRecord(long job,
                           String verdict,
                           Option<String> reason,
                           Option<String> reasonDetail,
                           Source source,
                           Recipe recipe,
                           List<String> annotations,
                           Counts counts,
                           List<Failed> failed,
                           int observedFailures,
                           List<UnitResult> units,
                           Option<Manifest> manifest,
                           Evidence evidence,
                           Timings timings,
                           Option<String> judge) {
    public ResultRecord {
        annotations = List.copyOf(annotations);
        failed = List.copyOf(failed);
        units = List.copyOf(units);
    }

    public static ResultRecord resultRecord(long job,
                                            String verdict,
                                            Option<String> reason,
                                            Option<String> reasonDetail,
                                            Source source,
                                            Recipe recipe,
                                            List<String> annotations,
                                            Counts counts,
                                            List<Failed> failed,
                                            int observedFailures,
                                            List<UnitResult> units,
                                            Option<Manifest> manifest,
                                            Evidence evidence,
                                            Timings timings,
                                            Option<String> judge) {
        return new ResultRecord(job,
                                verdict,
                                reason,
                                reasonDetail,
                                source,
                                recipe,
                                annotations,
                                counts,
                                failed,
                                observedFailures,
                                units,
                                manifest,
                                evidence,
                                timings,
                                judge);
    }

    /// The job's source: `sha` is absent only for a job refused before resolution.
    public record Source(Option<String> ref, Option<String> sha) {
        public static Source source(Option<String> ref, Option<String> sha) {
            return new Source(ref, sha);
        }
    }

    /// The submitted recipe as recorded; `Mutate`'s `spec` is bb-mutate's format, kept opaque until M3.
    public sealed interface Recipe {
        record Mvn(List<String> args) implements Recipe {
            public static Mvn mvn(List<String> args) {
                return new Mvn(args);
            }
        }

        record ModuleTest(List<String> pl, Option<String> test, Option<String> itTest) implements Recipe {
            public static ModuleTest moduleTest(List<String> pl, Option<String> test, Option<String> itTest) {
                return new ModuleTest(pl, test, itTest);
            }
        }

        record Gate() implements Recipe {
            public static Gate gate() {
                return new Gate();
            }
        }

        record Mutate(Option<Object> spec) implements Recipe {
            public static Mutate mutate(Option<Object> spec) {
                return new Mutate(spec);
            }
        }

        /// A rerun job's recipe (m2-design §5.2): its parent `of`, its target classes and `original`, the parent's
        /// outcome copied at submit (the record's `original`, §5.3).
        record Rerun(long of, List<Target> classes, Option<Original> original, Option<Bound> bound) implements Recipe {
            public Rerun {
                classes = List.copyOf(classes);
            }

            public static Rerun rerun(long of, List<Target> classes, Option<Original> original, Option<Bound> bound) {
                return new Rerun(of, classes, original, bound);
            }

            /// The rerun unit's bound decided at submit, in milliseconds, and its derivation.
            public record Bound(long millis, String derivation) {
                public static Bound bound(long millis, String derivation) {
                    return new Bound(millis, derivation);
                }
            }

            /// One target class, its module and its runner (`surefire`, `failsafe`).
            public record Target(String className, String module, String runner) {
                public static Target target(String className, String module, String runner) {
                    return new Target(className, module, runner);
                }
            }

            /// The parent's verdict, reason and failed rows of the target classes.
            public record Original(long job, String verdict, Option<String> reason, List<Failed> failed) {
                public Original {
                    failed = List.copyOf(failed);
                }

                public static Original original(long job, String verdict, Option<String> reason, List<Failed> failed) {
                    return new Original(job, verdict, reason, failed);
                }
            }
        }
    }

    /// One failed or error testcase; `known` is the KNOWN registry issue (M2).
    public record Failed(String className, String name, Option<String> known) {
        public static Failed failed(String className, String name, Option<String> known) {
            return new Failed(className, name, known);
        }
    }

    /// One unit: `id` is the unit name, `image` the runner image id, `tcLeftovers` the Testcontainers reap of its
    /// end facts, `portGroup` the bb-* ticket it held and since when.
    public record UnitResult(String id,
                             Option<Integer> rc,
                             Option<String> host,
                             Option<String> image,
                             Option<Instant> start,
                             Option<Instant> end,
                             Option<TcLeftovers> tcLeftovers,
                             Option<PortGroup> portGroup,
                             Option<Integer> attempt,
                             Option<Boolean> sharedCpu,
                             List<LostRun> lostAttempts) {
        public UnitResult {
            lostAttempts = List.copyOf(lostAttempts);
        }

        public static UnitResult unitResult(String id,
                                            Option<Integer> rc,
                                            Option<String> host,
                                            Option<String> image,
                                            Option<Instant> start,
                                            Option<Instant> end,
                                            Option<TcLeftovers> tcLeftovers,
                                            Option<PortGroup> portGroup,
                                            Option<Integer> attempt,
                                            Option<Boolean> sharedCpu,
                                            List<LostRun> lostAttempts) {
            return new UnitResult(id,
                                  rc,
                                  host,
                                  image,
                                  start,
                                  end,
                                  tcLeftovers,
                                  portGroup,
                                  attempt,
                                  sharedCpu,
                                  lostAttempts);
        }
    }

    /// An attempt of the unit lost with its host (m4-design §2.3): a `unit_attempts` row.
    public record LostRun(int attempt, String host, Instant grantedAt, Option<Instant> startedAt, Instant endedAt) {
        public static LostRun lostRun(int attempt,
                                      String host,
                                      Instant grantedAt,
                                      Option<Instant> startedAt,
                                      Instant endedAt) {
            return new LostRun(attempt, host, grantedAt, startedAt, endedAt);
        }
    }

    /// The Testcontainers containers a unit left behind, or why the reap is not verified.
    public record TcLeftovers(List<Container> containers, Option<String> unverified) {
        public static TcLeftovers tcLeftovers(List<Container> containers, Option<String> unverified) {
            return new TcLeftovers(containers, unverified);
        }
    }

    /// One leftover container: its id and Testcontainers session id.
    public record Container(String id, String session) {
        public static Container container(String id, String session) {
            return new Container(id, session);
        }
    }

    /// The port-group hold of a unit: the bb-* ticket and when it was granted.
    public record PortGroup(String ticket, Instant heldFrom) {
        public static PortGroup portGroup(String ticket, Instant heldFrom) {
            return new PortGroup(ticket, heldFrom);
        }
    }

    /// `manifest` (m1-design §2.5): `missing` and `drift` as recorded (sorted, capped), `notReached` uncapped.
    public record Manifest(boolean computed,
                           List<String> missing,
                           List<String> drift,
                           List<String> notReached,
                           int unresolvedAnnotations) {
        public Manifest {
            missing = List.copyOf(missing);
            drift = List.copyOf(drift);
            notReached = List.copyOf(notReached);
        }

        public static Manifest manifest(boolean computed,
                                        List<String> missing,
                                        List<String> drift,
                                        List<String> notReached,
                                        int unresolvedAnnotations) {
            return new Manifest(computed, missing, drift, notReached, unresolvedAnnotations);
        }
    }

    /// Evidence location (m1-design §3.2): stored with its path and size, or not stored.
    public record Evidence(boolean stored, Option<String> path, Option<Long> bytes) {
        public static Evidence evidence(boolean stored, Option<String> path, Option<Long> bytes) {
            return new Evidence(stored, path, bytes);
        }
    }

    /// `queued` = first start − submit; `run` = last end − first start. Absent when no unit started.
    public record Timings(Option<Duration> queued, Option<Duration> run) {
        public static Timings timings(Option<Duration> queued, Option<Duration> run) {
            return new Timings(queued, run);
        }
    }
}
