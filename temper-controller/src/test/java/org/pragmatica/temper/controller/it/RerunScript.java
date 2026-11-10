package org.pragmatica.temper.controller.it;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The fake worker's script for the rerun ITs (m2-design §5.6): a Gate unit exits 1 with `org.x.FooTest#works` failed
/// against [ControllerHarness#MANIFEST] (so the Gate is `FAILED(tests)` with one target class in module `core`); a rerun
/// unit either runs like every unit with a manifest (rc 0, FooTest passing: `rerun(org.x.FooTest)=PASSED`) or, when
/// [#holding], only starts, and ends killed by the cancel the controller sends for it. A unit whose steps name `held`
/// only starts too (a running parent).
final class RerunScript {
    static final String GATE_BY_SHA = "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"sha\":\"" + ControllerHarness.SHA_A
                                    + "\"}},\"recipe\":{\"gate\":{}}}";
    static final String RERUN = "{\"submittedBy\":\"v2174\"}";
    static final String RERUN_FRESH = "{\"submittedBy\":\"v2174\",\"fresh\":true}";

    private final ControllerHarness harness;
    private volatile boolean holding;

    RerunScript(ControllerHarness harness) {
        this.harness = harness;
        harness.worker.respondWith(this::respond);
    }

    /// From now on a rerun unit only starts, until its cancel.
    void holding(boolean hold) {
        holding = hold;
    }

    private List<Message> respond(Message request) {
        return switch (request) {
            case Message.StartUnit start when start.unit().equals("gate") -> List.of(started(start),
                                                                                     exited(start.job(), start.unit(), 1, none()),
                                                                                     new Message.UnitManifest(start.job(), start.unit(), 1, 0, true,
                                                                                                              ControllerHarness.MANIFEST),
                                                                                     report(start.job(), start.unit(), "failed"));
            case Message.StartUnit start when start.unit().equals("rerun") && holding -> List.of(started(start));
            case Message.StartUnit start when start.steps().stream().anyMatch(step -> step.contains("held")) -> List.of(started(start));
            case Message.CancelUnit cancel -> List.of(new Message.Ack(some("CANCELLING")),
                                                      exited(cancel.job(), cancel.unit(), 137, some(KilledBy.CANCEL)),
                                                      new Message.UnitReport(cancel.job(), cancel.unit(), 1, 0, true, List.of()));
            default -> harness.respond(request);
        };
    }

    private static Message started(Message.StartUnit start) {
        return new Message.UnitStarted(start.job(), start.unit(), 1, "c" + start.job(), "sha256:img", 1_700_000_000_000_000_000L,
                                       Instant.now(), ControllerHarness.facts(), none());
    }

    private static Message exited(long job, String unit, int rc, Option<KilledBy> killed) {
        return new Message.UnitExited(job, unit, 1, rc, killed, Instant.now(),
                                      new UnitEndFactsDto(ControllerHarness.facts(), none(), Instant.now(), Map.of(),
                                                          ControllerHarness.LEFTOVERS, none()),
                                      1, 1, 0, none(), none());
    }

    private static Message report(long job, String unit, String fooOutcome) {
        return new Message.UnitReport(job, unit, 1, 0, true, List.of(new TestcaseDto("org.x.FooTest", "works", fooOutcome, 0.2, unit, false),
                                                                  new TestcaseDto("org.x.BarTest", "runs", "passed", 0.1, unit, false)));
    }

    /// The `"job":N` of a submit answer.
    static long jobOf(ControllerHarness.Reply reply) {
        return ControllerHarness.jobOf(reply);
    }

    /// Whether `body` (a JSON answer) says `"deduplicated":true`.
    static boolean deduplicated(ControllerHarness.Reply reply) {
        return Pattern.compile("\"deduplicated\":true").matcher(reply.body()).find();
    }
}
