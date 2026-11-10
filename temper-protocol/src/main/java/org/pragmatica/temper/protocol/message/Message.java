package org.pragmatica.temper.protocol.message;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.api.TestcaseDto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Controller⇄worker messages (m0-design §4.2, m1-design §1.3), protocol version [Handshake#PROTOCOL_VERSION].
/// Each record's JSON type name is the record name in camelCase, wrapped: `{"hello": {...}}`. Times are ISO
/// instants, durations ISO-8601. `job` is the job id, `unit` the unit name.
///
/// Protocol v2 (M1) adds the bb-hold start split ([UnitHolding], then an unsolicited [UnitStarted] or
/// [UnitStartFailed]), the holder status in the startup scan, the manifest ([StartUnit#manifest],
/// [UnitManifest]), the void reason of a run ([UnitExited#voidReason]) and host checkout GC ([GcJobs],
/// [GcDone]).
///
/// Protocol v3 (M4, m4-design §7) adds the unit's attempt (`attempt`, the store's grant count of the unit, §4.1) to
/// every unit frame both ways, to [UnitRefDto] and [DiskUnitDto], and [DiscardUnit]. A worker answers a unit request
/// whose attempt is not the one its unit directory holds with `Failure{stale-attempt}`.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = Message.Hello.class, name = "hello"), @JsonSubTypes.Type(value = Message.Welcome.class, name = "welcome"), @JsonSubTypes.Type(value = Message.Incompatible.class, name = "incompatible"), @JsonSubTypes.Type(value = Message.PrepareJob.class, name = "prepareJob"), @JsonSubTypes.Type(value = Message.JobPrepared.class, name = "jobPrepared"), @JsonSubTypes.Type(value = Message.StartUnit.class, name = "startUnit"), @JsonSubTypes.Type(value = Message.UnitStarted.class, name = "unitStarted"), @JsonSubTypes.Type(value = Message.UnitStartFailed.class, name = "unitStartFailed"), @JsonSubTypes.Type(value = Message.Reattach.class, name = "reattach"), @JsonSubTypes.Type(value = Message.Collect.class, name = "collect"), @JsonSubTypes.Type(value = Message.CancelUnit.class, name = "cancelUnit"), @JsonSubTypes.Type(value = Message.Ack.class, name = "ack"), @JsonSubTypes.Type(value = Message.UnitReportAck.class, name = "unitReportAck"), @JsonSubTypes.Type(value = Message.LogTail.class, name = "logTail"), @JsonSubTypes.Type(value = Message.LogChunk.class, name = "logChunk"), @JsonSubTypes.Type(value = Message.FactsRequest.class, name = "factsRequest"), @JsonSubTypes.Type(value = Message.Facts.class, name = "facts"), @JsonSubTypes.Type(value = Message.Shutdown.class, name = "shutdown"), @JsonSubTypes.Type(value = Message.Heartbeat.class, name = "heartbeat"), @JsonSubTypes.Type(value = Message.UnitExited.class, name = "unitExited"), @JsonSubTypes.Type(value = Message.UnitReport.class, name = "unitReport"), @JsonSubTypes.Type(value = Message.Failure.class, name = "failure"), @JsonSubTypes.Type(value = Message.UnitHolding.class, name = "unitHolding"), @JsonSubTypes.Type(value = Message.UnitManifest.class, name = "unitManifest"), @JsonSubTypes.Type(value = Message.GcJobs.class, name = "gcJobs"), @JsonSubTypes.Type(value = Message.GcDone.class, name = "gcDone"), @JsonSubTypes.Type(value = Message.DiscardUnit.class, name = "discardUnit")})
public sealed interface Message {
    // ---- handshake -----------------------------------------------------------------------------------------
    /// W→C, first frame. Facts and the startup disk scan feed reconcile.
    record Hello(int protocol,
                 String workerVersion,
                 String host,
                 int uid,
                 int gid,
                 int dockerGid,
                 int cores,
                 HostFactsDto facts,
                 List<DiskUnitDto> units) implements Message {}

    /// C→W: the handshake is accepted.
    record Welcome(int protocol, String controllerVersion) implements Message {}

    /// C→W: wrong protocol or worker version; the worker exits 0 and the controller deploys, then reconnects.
    record Incompatible(int expectedProtocol, String expectedWorkerVersion, String reason) implements Message {}

    // ---- controller → worker requests ----------------------------------------------------------------------
    /// Idempotent workspace preparation: mirror, checkout, seed copy, seed verification. Replies [JobPrepared]
    /// or [Failure] (`seed-isolation`, ...).
    record PrepareJob(long job, String sha, String originUrl) implements Message {}

    record JobPrepared(long job, String ws) implements Message {}

    /// Start a unit. Replies [UnitStarted] or [UnitStartFailed]; a unit with `portGroup` is started through
    /// bb-hold and replies [UnitHolding] instead, its [UnitStarted] or [UnitStartFailed] following unsolicited
    /// once the hold is granted or fails (m1-design §1.2). `manifest` makes the worker run the effective-POM
    /// step before the unit and send [UnitManifest] chunks after it exits (§2.3, §2.5). `instance` is the
    /// controller's instance, the container's `temper.instance` label (M1 WP7 verification, F2).
    record StartUnit(long job,
                     String unit,
                     int attempt,
                     String kind,
                     List<List<String>> steps,
                     Duration timeout,
                     int memoryMb,
                     int cpuShares,
                     String runnerVersion,
                     Option<String> mavenOpts,
                     Option<String> tcHubPrefix,
                     Option<PortGroupHoldDto> portGroup,
                     Option<ManifestSpecDto> manifest,
                     String instance) implements Message {}

    /// W→C reply to a [StartUnit] with `portGroup`: the marker is written and bb-hold is waiting for the port
    /// group; no container exists yet. `facts` are the host facts at the enqueue.
    record UnitHolding(long job, String unit, int attempt, long markerEpochNs, HostFactsDto facts) implements Message {}

    /// The unit's container runs. A reply to [StartUnit] for a direct unit; unsolicited for a hold unit, sent
    /// when the hold is granted (the unit's timeout counts from then). `ticket` (protocol v2) is the bb-* ticket of
    /// the `granted` line, which the result record's unit `portGroup` names; none for a direct unit.
    record UnitStarted(long job,
                       String unit,
                       int attempt,
                       String containerId,
                       String imageId,
                       long markerEpochNs,
                       Instant startedAt,
                       HostFactsDto facts,
                       Option<String> ticket) implements Message {}

    /// The unit did not start. A reply to [StartUnit], or unsolicited for a hold unit whose hold failed.
    record UnitStartFailed(long job,
                           String unit,
                           int attempt,
                           StartFailureKind verdictKind,
                           String reason,
                           Option<HostFactsDto> facts) implements Message {}

    /// Re-arm the unit's timer with the controller's authoritative timeout. Replies [Ack].
    record Reattach(long job, String unit, int attempt, Duration timeout) implements Message {}

    /// Collect an exited unit. Replies [Ack], then [UnitExited] and the [UnitReport] chunks.
    record Collect(long job, String unit, int attempt) implements Message {}

    /// Kill a unit's container. Replies [Ack] with the unit state.
    record CancelUnit(long job, String unit, int attempt) implements Message {}

    /// Generic acknowledgement; `state` is set for [CancelUnit].
    record Ack(Option<String> state) implements Message {}

    /// The controller persisted the unit's report; the worker removes the container and network. `attempt` (protocol v3)
    /// is the attempt of the report acknowledged: the worker removes nothing when its unit directory or the container's
    /// `temper.attempt` label names another attempt (an ack of a superseded report must never remove the live attempt's
    /// container, m4-design §4.3), and logs the ack as ignored. No reply either way.
    record UnitReportAck(long job, String unit, int attempt) implements Message {}

    /// Log tail request, `lines` ≤ 10000. Replies [LogChunk].
    record LogTail(long job, String unit, int attempt, int lines) implements Message {}

    /// At most 1 MiB of text.
    record LogChunk(String text, boolean truncated, LogSource source) implements Message {}

    record FactsRequest() implements Message {}

    record Facts(HostFactsDto facts) implements Message {}

    record Shutdown() implements Message {}

    /// C→W (protocol v3, m4-design §4.5): discard a superseded attempt of a unit: kill its container if it runs, remove
    /// the container, its network and the unit directory, but only when the unit there is this `attempt` (an
    /// attempt-scoped, idempotent delete: another attempt, or nothing at all, is left as it is). Replies [Ack].
    record DiscardUnit(long job, String unit, int attempt) implements Message {}

    // ---- worker → controller, unsolicited ------------------------------------------------------------------
    /// Every 10 s; refreshes leases of the running units, holding units included (m1-design §1.3).
    record Heartbeat(List<UnitRefDto> running, HostFactsDto facts) implements Message {}

    /// A unit's container exited; followed by its [UnitManifest] chunks (a unit with a manifest) and its
    /// [UnitReport] chunks. `voidReason` (protocol v2) voids the unit whatever its rc; the observed reds are still
    /// reported (spec §5.3).
    record UnitExited(long job,
                      String unit,
                      int attempt,
                      int rc,
                      Option<KilledBy> killedBy,
                      Instant endedAt,
                      UnitEndFactsDto endFacts,
                      int reportFiles,
                      long testsAttr,
                      int threadDumps,
                      Option<String> errorReason,
                      Option<VoidReason> voidReason) implements Message {}

    /// One chunk of a unit's manifest outcome (protocol v2, m1-design §2.5), sent between [UnitExited] and the
    /// [UnitReport] chunks; `last` marks the final chunk. See [ManifestOutcomeDto] for the chunking contract.
    record UnitManifest(long job, String unit, int attempt, int chunk, boolean last, ManifestOutcomeDto outcome) implements Message {
        /// `outcome` as its chunks, numbered from 0; only the final one has `last = true`.
        public static List<UnitManifest> unitManifests(long job, String unit, int attempt, ManifestOutcomeDto outcome) {
            var chunks = outcome.chunks();

            return IntStream.range(0,
                                   chunks.size())
                            .mapToObj(index -> new UnitManifest(job,
                                                                unit,
                                                                attempt,
                                                                index,
                                                                index == chunks.size() - 1,
                                                                chunks.get(index)))
                            .toList();
        }
    }

    /// Testcase rows, at most [#MAX_ROWS_PER_CHUNK] per chunk; `last` marks the final chunk.
    record UnitReport(long job, String unit, int attempt, int chunk, boolean last, List<TestcaseDto> rows) implements Message {}

    /// A request failed (as a reply, `Envelope.replyTo` names the request) or an unsolicited failure. `unit` names
    /// the unit the failure is about (protocol v1 addition, m0-design §12.7): the unsolicited `lost` carries it, so
    /// the controller can end that unit `ERROR(lost)`; failures about no single unit leave it absent.
    record Failure(String code, String detail, Option<UnitRefDto> unit) implements Message {
        /// A failure about no single unit.
        public static Failure failure(String code, String detail) {
            return new Failure(code, detail, none());
        }

        /// A failure about one unit.
        public static Failure failure(String code, String detail, UnitRefDto unit) {
            return new Failure(code, detail, some(unit));
        }
    }

    // ---- host checkout GC (protocol v2, m1-design §4.3) -----------------------------------------------------
    /// C→W: delete these jobs' directories where that is safe. Replies [GcDone].
    record GcJobs(List<Long> jobs) implements Message {}

    /// W→C: the job directories deleted, and the ones kept with the reason (a unit still known, a labelled
    /// container, a unit without `rc`, a file the worker could not delete).
    record GcDone(List<Long> deleted, List<Kept> kept) implements Message {
        public record Kept(long job, String reason) {
            public static Kept kept(long job, String reason) {
                return new Kept(job, reason);
            }
        }
    }

    int MAX_ROWS_PER_CHUNK = 2000;
    int MAX_LOG_LINES = 10_000;
    int MAX_LOG_BYTES = 1024 * 1024;
}
