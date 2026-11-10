package org.pragmatica.temper.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.domain.refusal.TestRunner;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.CancelResponse;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.protocol.api.ErrorResponse;
import org.pragmatica.temper.protocol.api.EventKind;
import org.pragmatica.temper.protocol.api.HostLossDto;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.LostAttemptDto;
import org.pragmatica.temper.protocol.api.MaintenanceRunDto;
import org.pragmatica.temper.protocol.api.MaintenanceView;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.RefusalAddRequest;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.SubmitRequest;
import org.pragmatica.temper.protocol.api.SubmitResponse;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.api.UnitView;
import org.pragmatica.temper.protocol.api.UserPropertyDto;
import org.pragmatica.temper.protocol.api.WsClientMessage;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.protocol.evidence.EvidenceRequest;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.HolderStatusDto;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.LogSource;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.PortGroupHoldDto;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.protocol.message.VoidReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// R3 settled: Jackson 3 (`tools.jackson` 3.1.0) with 2.21 annotations round-trips sealed records under
/// `@JsonTypeInfo(use = NAME, include = WRAPPER_OBJECT)`, empty records included, with `Option` fields as
/// value-or-null and absent `Option` fields read as `none()`. No hand-written codec is needed.
class JsonRoundTripTest {
    private static final JsonMapper MAPPER = TemperJson.mapper();
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";
    private static final Instant AT = Instant.parse("2026-10-13T10:00:00Z");
    private static final HostFactsDto FACTS = new HostFactsDto(1.5, 2.0, 32, 1, 2, Option.none(), Option.none(), 1_000_000L,
                                                               60L << 30, 70L << 30, AT, List.of());
    private static final TcLeftoversDto LEFTOVERS = new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto("c0ffee", "s-1")),
                                                                       Option.none());
    private static final ManifestSpecDto SPEC = new ManifestSpecDto(List.of("mvn", "-B", ManifestSpec.EFFECTIVE_POM_GOAL,
                                                                            "-Doutput=${ws}/.home/.temper/effective-pom-gate.xml",
                                                                            "-Dmaven.repo.local=${ws}/.m2-local"),
                                                                    "integration-test", Map.of("skipITs", "false"), Option.none(),
                                                                    Option.some("*IT"));
    private static final ManifestEntryDto ENTRY = new ManifestEntryDto("core", "org.example.FooTest", "surefire");
    private static final ManifestEntryDto OPTIONAL_ENTRY = new ManifestEntryDto("core", "org.example.FactoryTest", "surefire");

    private static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    private static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause, value -> fail("expected failure, got: " + value));
    }

    private static <T> T roundTrip(T value, Class<T> type) {
        var json = ok(MAPPER.writeAsString(value));

        return ok(MAPPER.readString(json, type));
    }

    @Nested
    class Frames {
        private static final List<Message> EVERY_MESSAGE = List.of(
            new Message.Hello(2, "0.1.0", "bigboy", 1000, 1000, 998, 32, FACTS,
                              List.of(new DiskUnitDto(4711, "main", true, Option.some(0), ContainerStatus.EXITED, Option.none(), Option.none(), Option.none(), Option.none()),
                                      new DiskUnitDto(4712, "main", false, Option.none(), ContainerStatus.ABSENT, Option.none(), Option.none(), Option.none(), Option.some(2)),
                                      new DiskUnitDto(4713, "gate", true, Option.none(), ContainerStatus.ABSENT, Option.some(HolderStatusDto.DEAD),
                                                      Option.some("20261013-1"), Option.none(), Option.none()))),
            new Message.Welcome(2, "0.1.0"),
            new Message.Incompatible(2, "0.1.0", "version"),
            new Message.PrepareJob(4711, SHA, "git@github.com:x/y.git"),
            new Message.JobPrepared(4711, "/home/builder/.temper/jobs/4711/ws"),
            new Message.StartUnit(4711, "main", 1, "MVN", List.of(List.of("mvn", "-B", "verify")), Duration.ofMinutes(60), 6144, 1024,
                                  "0.1.0", Option.some("-Xmx2g"), Option.none(), Option.none(), Option.none(), "temper"),
            new Message.StartUnit(4713, "gate", 1, "GATE_UNSHARDED", List.of(List.of("mvn", "-B", "clean", "install")), Duration.ofHours(3),
                                  16384, 1024, "0.1.0", Option.none(), Option.none(), Option.some(new PortGroupHoldDto("temper", 5)),
                                  Option.some(SPEC), "temper"),
            new Message.UnitHolding(4713, "gate", 1, 1_760_000_000_000_000_000L, FACTS),
            new Message.UnitStarted(4711, "main", 1, "c0ffee", "sha256:abc", 1_760_000_000_000_000_000L, AT, FACTS, Option.none()),
            new Message.UnitStarted(4713, "gate", 1, "d00d", "sha256:abc", 1_760_000_000_000_000_000L, AT, FACTS,
                                    Option.some("20261013-1")),
            new Message.UnitStartFailed(4711, "main", 1, StartFailureKind.VOID, "disk", Option.some(FACTS)),
            new Message.Reattach(4711, "main", 1, Duration.ofSeconds(90)),
            new Message.Collect(4711, "main", 1),
            new Message.CancelUnit(4711, "main", 1),
            new Message.Ack(Option.some("CANCELLING")),
            new Message.Ack(Option.none()),
            new Message.UnitReportAck(4711, "main", 1),
            new Message.LogTail(4711, "main", 1, 200),
            new Message.LogChunk("line\n", false, LogSource.DOCKER),
            new Message.FactsRequest(),
            new Message.Facts(FACTS),
            new Message.Shutdown(),
            new Message.Heartbeat(List.of(new UnitRefDto(4711, "main", 1)), FACTS),
            new Message.UnitExited(4711, "main", 1, 137, Option.some(KilledBy.TIMEOUT), AT,
                                   new UnitEndFactsDto(FACTS, Option.some(1L << 30), AT, Map.of("usage_usec", 42L), LEFTOVERS, Option.some(512_000L)),
                                   3, 12L, 3, Option.none(), Option.some(VoidReason.PORT_GROUP_LOST)),
            new Message.UnitManifest(4713, "gate", 1, 0, true,
                                     new ManifestOutcomeDto.Computed(List.of(ENTRY), List.of(OPTIONAL_ENTRY), List.of("aether/ember"),
                                                                     List.of("org.example.Slow"))),
            new Message.UnitManifest(4714, "gate", 1, 0, true, new ManifestOutcomeDto.Unsupported("includesFile in core")),
            new Message.UnitReport(4711, "main", 1, 0, true,
                                   List.of(new TestcaseDto("org.example.FooTest", "works", "passed", 0.25, "main", false))),
            Message.Failure.failure("seed-isolation", "2 files hard-linked"),
            Message.Failure.failure("lost", "temper-4711-main: container gone", new UnitRefDto(4711, "main", 1)),
            new Message.GcJobs(List.of(4711L, 4712L)),
            new Message.GcDone(List.of(4711L), List.of(new Message.GcDone.Kept(4712, "unit main has no rc"))),
            new Message.DiscardUnit(4711, "main", 1));

        @Test
        void everyMessage_roundTripsInsideAnEnvelope() {
            assertThat(Message.class.getPermittedSubclasses()).hasSize(27);
            EVERY_MESSAGE.forEach(message -> assertThat(roundTrip(Envelope.envelope(7, 3, message), Envelope.class))
                .isEqualTo(Envelope.envelope(7, 3, message)));
        }

        @Test
        void message_isWrappedInItsCamelCaseName() {
            assertThat(ok(MAPPER.writeAsString(Envelope.envelope(1, new Message.Shutdown()))))
                .isEqualTo("{\"id\":1,\"replyTo\":null,\"msg\":{\"shutdown\":{}}}");
            assertThat(ok(MAPPER.writeAsString(new Message.Reattach(1, "main", 1, Duration.ofMinutes(90)))))
                .isEqualTo("{\"reattach\":{\"job\":1,\"unit\":\"main\",\"attempt\":1,\"timeout\":\"PT1H30M\"}}");
        }

        @Test
        void wireForms_areLowercaseWhereTheSpecSaysSo() {
            var exited = ok(MAPPER.writeAsString(EVERY_MESSAGE.stream()
                                                              .filter(Message.UnitExited.class::isInstance)
                                                              .findFirst()
                                                              .orElseThrow()));

            assertThat(exited).contains("\"killedBy\":\"timeout\"")
                              .contains("\"endedAt\":\"2026-10-13T10:00:00Z\"")
                              .contains("\"voidReason\":\"port-group-lost\"");
            assertThat(ok(MAPPER.writeAsString(new Message.LogChunk("x", true, LogSource.FILE)))).contains("\"source\":\"file\"");
        }

        @Test
        void voidReason_wire_isPinnedForEveryReason() {
            // wire() is the `.temper/void` content and the VOID(<reason>) detail; the JSON form must say the same.
            var expected = Map.of(VoidReason.PORT_GROUP_LOST, "port-group-lost", VoidReason.DISK, "disk");

            assertThat(Set.of(VoidReason.values())).isEqualTo(expected.keySet());
            expected.forEach((reason, wire) -> assertThat(reason.wire()).isEqualTo(wire));
            expected.forEach((reason, wire) -> assertThat(ok(MAPPER.writeAsString(reason))).isEqualTo("\"" + wire + "\""));
        }

        @Test
        void absentOptionField_readsAsNone() {
            var envelope = ok(MAPPER.readString("{\"id\":5,\"msg\":{\"ack\":{}}}", Envelope.class));

            assertThat(envelope).isEqualTo(Envelope.envelope(5, new Message.Ack(Option.none())));
        }

        @Test
        void unknownMessageType_fails() {
            assertThat(failure(MAPPER.readString("{\"id\":5,\"msg\":{\"rmRf\":{}}}", Envelope.class))).isNotNull();
        }
    }

    @Nested
    class Api {
        @Test
        void submitRequest_specExamples_parseIntoDomain() {
            var byRef = ok(MAPPER.readString("""
                {"submittedBy":"v2174","priority":-20,"keep":false,
                 "source":{"pushed":{"ref":"rewrite/deployment-fsm"}},
                 "recipe":{"moduleTest":{"pl":["aether/ember"],"test":"Foo*,Bar","itTest":null}}}
                """, SubmitRequest.class));
            var request = ok(byRef.toDomain());

            assertThat(request.source()).isInstanceOf(SourceRequest.ByRef.class);
            assertThat(request.recipe()).isInstanceOf(Recipe.ModuleTest.class);
            assertThat(request.priority()
                              .value()).isEqualTo(-20);
            assertThat(request.envFieldPresent()).isFalse();
            assertThat(request.options()
                              .timeout()).isEqualTo(Option.none());
        }

        @Test
        void submitRequest_shaAndTimeout_parse() {
            var request = ok(ok(MAPPER.readString("""
                {"submittedBy":"v2174","source":{"pushed":{"sha":"%s"}},"recipe":{"mvn":{"args":["-pl","core","verify"]}},
                 "timeout":"PT60S"}
                """.formatted(SHA), SubmitRequest.class)).toDomain());

            assertThat(request.source()).isInstanceOf(SourceRequest.BySha.class);
            assertThat(request.options()
                              .timeout()
                              .map(timeout -> timeout.value()
                                                     .getSeconds())).isEqualTo(Option.some(60L));
            assertThat(request.priority()
                              .value()).isEqualTo(-20);
        }

        @Test
        void submitRequest_envField_isAcceptedOnlyToBeRefused() {
            var request = ok(MAPPER.readString("""
                {"submittedBy":"v2174","source":{"pushed":{"ref":"main"}},"recipe":{"mvn":{"args":["verify"]}},
                 "env":{"HCLOUD_TOKEN":"x"}}
                """, SubmitRequest.class));

            assertThat(ok(request.toDomain()).envFieldPresent()).isTrue();
        }

        @Test
        void submitRequest_unknownField_failsParsing() {
            assertThat(failure(MAPPER.readString("""
                {"submittedBy":"v2174","source":{"pushed":{"ref":"main"}},"recipe":{"mvn":{"args":["verify"]}},"shell":"rm"}
                """, SubmitRequest.class))).isNotNull();
        }

        @Test
        void submitRequest_unsupportedSourcesAndMutate_parseAsUnsupported_gateAsGate() {
            var gate = ok(ok(MAPPER.readString("""
                {"submittedBy":"v2174","source":{"bundle":{"blobId":"b","sha":"%s"}},"recipe":{"gate":{}}}
                """.formatted(SHA), SubmitRequest.class)).toDomain());
            var mutate = ok(ok(MAPPER.readString("""
                {"submittedBy":"v2174","source":{"patch":{"baseSha":"%s","diff":"--- a"}},"recipe":{"mutate":{"spec":{"build":{}}}}}
                """.formatted(SHA), SubmitRequest.class)).toDomain());

            assertThat(gate.source()).isEqualTo(SourceRequest.unsupported("bundle"));
            assertThat(gate.recipe()).isEqualTo(Recipe.gate());
            assertThat(mutate.source()).isEqualTo(SourceRequest.unsupported("patch"));
            assertThat(mutate.recipe()).isEqualTo(Recipe.unsupported("mutate"));
        }

        @Test
        void submitRequest_invalidFields_failToDomain() {
            var bothRefAndSha = ok(MAPPER.readString("""
                {"submittedBy":"v2174","source":{"pushed":{"ref":"main","sha":"%s"}},"recipe":{"mvn":{"args":["verify"]}}}
                """.formatted(SHA), SubmitRequest.class));
            var badOwner = ok(MAPPER.readString("""
                {"submittedBy":"no spaces","source":{"pushed":{"ref":"main"}},"recipe":{"mvn":{"args":["verify"]}}}
                """, SubmitRequest.class));
            var missingSource = ok(MAPPER.readString("""
                {"submittedBy":"v2174","recipe":{"mvn":{"args":["verify"]}}}
                """, SubmitRequest.class));

            assertThat(failure(bothRefAndSha.toDomain())).isNotNull();
            assertThat(failure(badOwner.toDomain())).isNotNull();
            assertThat(failure(missingSource.toDomain())).isNotNull();
        }

        @Test
        void sealedSourceAndRecipe_roundTrip() {
            SourceDto pushed = new SourceDto.Pushed(Option.some("main"), Option.none());
            RecipeDto moduleTest = new RecipeDto.ModuleTest(List.of("aether/ember"), Option.some("Foo*"), Option.none());

            assertThat(roundTrip(pushed, SourceDto.class)).isEqualTo(pushed);
            assertThat(roundTrip(moduleTest, RecipeDto.class)).isEqualTo(moduleTest);
            assertThat(ok(MAPPER.writeAsString(moduleTest))).isEqualTo(
                "{\"moduleTest\":{\"pl\":[\"aether/ember\"],\"test\":\"Foo*\",\"itTest\":null}}");
            assertThat(RecipeDto.from(ok(moduleTest.toDomain()))).isEqualTo(moduleTest);
            assertThat(RecipeDto.from(ok(new RecipeDto.Gate().toDomain()))).isEqualTo(new RecipeDto.Gate());
            assertThat(RecipeDto.from(ok(new RecipeDto.Mutate(Option.none()).toDomain()))).isEqualTo(new RecipeDto.Mutate(Option.none()));
        }

        @Test
        void responsesAndViews_roundTrip() {
            var unit = new UnitView("main", UnitState.RUNNING, Option.none(), Option.some("bigboy"), Option.none(), Option.some(AT),
                                    Option.none(), Option.some("sha256:abc"), Option.some("c0ffee"), Option.some(KilledBy.CANCEL), true,
                                    Option.some(UnitView.HELD), Option.some(AT), Option.some("20261013-1"), 2,
                                    Option.some(true), Option.some("none"),
                                    List.of(new LostAttemptDto(1, "cloudbb-3", AT.minusSeconds(600), Option.some(AT.minusSeconds(590)),
                                                               AT.minusSeconds(60))));
            var view = new JobView(4711, JobState.RUNNING, Option.none(), Option.none(), "v2174", AT, -20, false, false, false,
                                   new SourceView(Option.some("main"), Option.some(SHA)),
                                   new RecipeDto.Mvn(List.of("verify")), List.of(unit), List.of(), List.of(), true);

            assertThat(roundTrip(view, JobView.class)).isEqualTo(view);
            assertThat(roundTrip(new SubmitResponse(4711, JobState.QUEUED, false), SubmitResponse.class))
                .isEqualTo(new SubmitResponse(4711, JobState.QUEUED, false));
            assertThat(roundTrip(new RefusedResponse("R-M2", "x", Option.some(4712L)), RefusedResponse.class))
                .isEqualTo(new RefusedResponse("R-M2", "x", Option.some(4712L)));
            assertThat(roundTrip(new CancelResponse(JobState.CANCELLING), CancelResponse.class))
                .isEqualTo(new CancelResponse(JobState.CANCELLING));
            var lost = HostView.hostView("cloudbb-3", "CONNECTING", Option.some(new HostLossDto(AT, "link-down")), 3, 1, 0, 1,
                                         Option.some(FACTS), true, Option.some(AT.plusSeconds(60)), false);
            assertThat(roundTrip(lost, HostView.class)).isEqualTo(lost);
            assertThat(lost.state()).isEqualTo("LOST");
            assertThat(lost.link()).isEqualTo("CONNECTING");
            assertThat(ok(MAPPER.writeAsString(lost))).contains("\"state\":\"LOST\",\"link\":\"CONNECTING\",\"lost\":{\"at\":\"2026-10-13T10:00:00Z\",\"reason\":\"link-down\"}")
                                                    .contains("\"sharedCpu\":true,\"retireAt\":\"2026-10-13T10:01:00Z\",\"retired\":false");
            var up = HostView.hostView("bigboy", "UP", 5, 1, 0, 1, Option.some(FACTS));
            assertThat(roundTrip(up, HostView.class)).isEqualTo(up);
            assertThat(up.state()).isEqualTo("UP");
            assertThat(up.lost()).isEqualTo(Option.none());
            assertThat(ok(MAPPER.writeAsString(ErrorResponse.UNAUTHORIZED))).isEqualTo("{\"error\":\"unauthorized\"}");
        }

        /// M4 (m4-design §11): a view written without the M4 fields (an older controller, a hand-written answer) reads
        /// with them defaulted, as a view without `reruns` does: a host's `link` is its `state`, never LOST or retired,
        /// dedicated; a unit at attempt 0 with no lost attempts; a job not dedicated; a digest host unmarked; and a
        /// result record stored before M4 (`results.record`) has no attempt, CPU class or lost attempts.
        @Test
        void m4Fields_absent_readAsBeforeM4() {
            var host = ok(MAPPER.readString("{\"name\":\"bb\",\"state\":\"UP\",\"cap\":5,\"reactors\":1,\"foreign\":1,\"active\":0}",
                                            HostView.class));
            var unit = ok(MAPPER.readString("{\"name\":\"main\",\"state\":\"RUNNING\",\"leaseExpired\":false}", UnitView.class));
            var job = ok(MAPPER.readString("{\"job\":41,\"state\":\"RUNNING\",\"submittedBy\":\"v2174\",\"submittedAt\":\"2026-10-13T10:00:00Z\","
                                           + "\"priority\":-20,\"keep\":false,\"fresh\":false,\"superseded\":false,"
                                           + "\"source\":{\"ref\":\"main\",\"sha\":null},\"recipe\":{\"gate\":{}},\"units\":[]}",
                                           JobView.class));
            var digestHost = ok(MAPPER.readString("{\"name\":\"bb\",\"link\":\"UP\",\"cap\":5,\"reactors\":0,\"foreign\":0,\"active\":0}",
                                                  DigestView.Host.class));
            var result = ok(MAPPER.readString("{\"id\":\"main\"}", ResultRecordDto.UnitResultDto.class));

            assertThat(host).isEqualTo(HostView.hostView("bb", "UP", 5, 1, 1, 0, Option.none()));
            assertThat(unit.attempt()).isZero();
            assertThat(unit.lostAttempts()).isEmpty();
            assertThat(unit.sharedCpu()).isEqualTo(Option.none());
            assertThat(job.dedicatedCpu()).isFalse();
            assertThat(digestHost).isEqualTo(DigestView.Host.host("bb", "UP", Option.none(), 5, 0, 0, 0));
            assertThat(result.attempt()).isEqualTo(Option.none());
            assertThat(result.sharedCpu()).isEqualTo(Option.none());
            assertThat(result.lostAttempts()).isEmpty();
        }

        /// The result record's M4 unit fields round-trip under their pinned names.
        @Test
        void unitResult_m4Fields_roundTrip() {
            var unit = ResultRecordDto.UnitResultDto.unitResultDto("main", Option.some(0), Option.some("bigboy"), Option.none(),
                                                                   Option.some(AT), Option.some(AT), Option.none(), Option.none(),
                                                                   Option.some(2), Option.some(false),
                                                                   List.of(new LostAttemptDto(1, "cloudbb-3", AT, Option.none(), AT)));

            assertThat(roundTrip(unit, ResultRecordDto.UnitResultDto.class)).isEqualTo(unit);
            assertThat(ok(MAPPER.writeAsString(unit))).contains("\"attempt\":2,\"sharedCpu\":false,\"lostAttempts\":[{\"attempt\":1,"
                                                               + "\"host\":\"cloudbb-3\",\"grantedAt\":\"2026-10-13T10:00:00Z\"");
        }

        /// The alarm checker (`temper alarm-check`) reads these names; an absent run is `null`.
        @Test
        void maintenanceView_roundTrips_withItsPinnedFieldNames() {
            var failed = new MaintenanceRunDto("pg_dump", AT.minusSeconds(60), AT, false, Option.some("pg_restore --list failed"));
            var view = new MaintenanceView(Option.some(failed), Option.some(AT.minusSeconds(86_400)));

            assertThat(roundTrip(view, MaintenanceView.class)).isEqualTo(view);
            assertThat(ok(MAPPER.writeAsString(view))).isEqualTo("{\"latestDump\":{\"kind\":\"pg_dump\",\"startedAt\":\"2026-10-13T09:59:00Z\","
                                                                + "\"endedAt\":\"2026-10-13T10:00:00Z\",\"ok\":false,"
                                                                + "\"detail\":\"pg_restore --list failed\"},"
                                                                + "\"lastGoodDumpEndedAt\":\"2026-10-12T10:00:00Z\"}");
            assertThat(roundTrip(new MaintenanceView(Option.none(), Option.none()), MaintenanceView.class))
                .isEqualTo(new MaintenanceView(Option.none(), Option.none()));
        }

        /// Q-M4-11c: the factories derive `userProperties` from the recipe, and a JSON without the field (a view or record
        /// from before it) derives them too.
        @Test
        void userProperties_derivedFromTheRecipe_alsoWhenAbsent() {
            var recipe = new RecipeDto.Mvn(List.of("-DskipTests", "install"));
            var shown = List.of(UserPropertyDto.userPropertyDto("-DskipTests=true", Option.some("skipped")));
            var view = JobView.jobView(4711, JobState.PASSED, Option.some("PASSED"), Option.none(), "v2174", AT, -20, false, false,
                                       false, new SourceView(Option.some("main"), Option.some(SHA)), recipe, List.of(), List.of());
            var record = ResultRecordDto.resultRecordDto(4711, "PASSED", Option.none(), Option.none(),
                                                         new SourceView(Option.some("main"), Option.some(SHA)), recipe, List.of(),
                                                         new ResultRecordDto.CountsDto(0, 0, 0, 0, 0, 0, 0, Option.none(), Option.none(),
                                                                                       Option.none()),
                                                         List.of(), 0, List.of(), Option.none(), ResultRecordDto.EvidenceDto.NOT_STORED,
                                                         new ResultRecordDto.TimingsDto(Option.none(), Option.none()), Option.none());
            var field = ",\"userProperties\":[{\"define\":\"-DskipTests=true\",\"tests\":\"skipped\"}]";

            assertThat(view.userProperties()).isEqualTo(shown);
            assertThat(record.userProperties()).isEqualTo(shown);
            assertThat(ok(MAPPER.writeAsString(view))).contains(field);
            assertThat(ok(MAPPER.readString(ok(MAPPER.writeAsString(view)).replace(field, ""), JobView.class)).userProperties())
                .isEqualTo(shown);
            assertThat(ok(MAPPER.readString(ok(MAPPER.writeAsString(record)).replace(field, ""), ResultRecordDto.class)).userProperties())
                .isEqualTo(shown);
        }

        @Test
        void resultRecord_matchesSpecShape() {
            var record = new ResultRecordDto(4711, "FAILED", Option.some("tests"), Option.none(), new SourceView(Option.some("main"), Option.some(SHA)),
                                             new RecipeDto.Gate(), List.of("superseded"),
                                             new ResultRecordDto.CountsDto(3, 2, 1, 0, 0, 3, 1, Option.some(2), Option.some(1), Option.some(1)),
                                             List.of(new ResultRecordDto.FailedDto("org.example.FooTest", "breaks", Option.none())), 1,
                                             List.of(ResultRecordDto.UnitResultDto.unitResultDto("main", Option.some(1), Option.some("bigboy"),
                                                                                       Option.some("sha256:abc"), Option.some(AT),
                                                                                       Option.some(AT.plusSeconds(60)), Option.some(LEFTOVERS),
                                                                                       Option.some(new ResultRecordDto.PortGroupDto("20261013-1", AT)))),
                                             Option.some(ResultRecordDto.ManifestDto.manifestDto(true, Set.of("org.example.Gone"),
                                                                                                 Set.of("org.example.Extra"), List.of(), 0)),
                                             ResultRecordDto.EvidenceDto.NOT_STORED,
                                             new ResultRecordDto.TimingsDto(Option.some(Duration.ofMinutes(2)), Option.some(Duration.ofMinutes(36))),
                                             Option.some("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d"), List.of());
            var json = ok(MAPPER.writeAsString(record));

            assertThat(roundTrip(record, ResultRecordDto.class)).isEqualTo(record);
            assertThat(json).contains("\"failed\":[{\"class\":\"org.example.FooTest\"")
                            .contains("\"evidence\":{\"stored\":false")
                            .contains("\"timings\":{\"queued\":\"PT2M\",\"run\":\"PT36M\"}")
                            .contains("\"recipe\":{\"gate\":{}}")
                            .contains("\"manifest\":{\"computed\":true,\"missing\":[\"org.example.Gone\"],\"drift\":[\"org.example.Extra\"],"
                                      + "\"notReached\":[],\"unresolvedAnnotations\":0}")
                            .contains("\"portGroup\":{\"ticket\":\"20261013-1\",\"heldFrom\":\"2026-10-13T10:00:00Z\"}");
        }

        @Test
        void testcaseDto_mapsDomainRowsBothWays() {
            var row = TestcaseRow.testcaseRow(UnitName.MAIN, "org.example.FooTest", "works", Outcome.SKIPPED, 0.5, false);
            var dto = TestcaseDto.testcaseDto(row);

            assertThat(ok(MAPPER.writeAsString(dto))).isEqualTo(
                "{\"class\":\"org.example.FooTest\",\"name\":\"works\",\"outcome\":\"skipped\",\"timeSeconds\":0.5,\"unit\":\"main\",\"rerun\":false}");
            assertThat(ok(roundTrip(dto, TestcaseDto.class).toDomain())).isEqualTo(row);
            assertThat(failure(new TestcaseDto("C", "n", "exploded", 0, "main", false).toDomain())).isNotNull();
        }

        /// R-13, amended: UNKNOWN is a state of the wire vocabulary, for the job and its unit, and `start-unknown` an
        /// event kind.
        @Test
        void unknownState_andStartUnknownEvent_roundTrip() {
            var unit = new UnitView("main", UnitState.UNKNOWN, Option.none(), Option.some("bigboy"), Option.none(), Option.none(),
                                    Option.none(), Option.none(), Option.none(), Option.none(), false, Option.none(), Option.none(),
                                    Option.none(), 0, Option.none(), Option.none(), List.of());
            var view = new JobView(4711, JobState.UNKNOWN, Option.none(), Option.none(), "v2174", AT, -20, false, false, false,
                                   new SourceView(Option.some("main"), Option.some(SHA)),
                                   new RecipeDto.Mvn(List.of("verify")), List.of(unit), List.of(), List.of(), true);
            WsServerMessage event = new WsServerMessage.WsEvent(89, AT, Option.some(4711L), EventKind.START_UNKNOWN, Option.none(),
                                                                "start-unknown 4711 unit=main seq=89");

            assertThat(roundTrip(view, JobView.class)).isEqualTo(view);
            assertThat(ok(MAPPER.writeAsString(view))).contains("\"state\":\"UNKNOWN\"");
            assertThat(roundTrip(event, WsServerMessage.class)).isEqualTo(event);
            assertThat(ok(MAPPER.writeAsString(event))).contains("\"kind\":\"start-unknown\"");
        }

        @Test
        void webSocketMessages_roundTrip() {
            WsClientMessage subscribe = new WsClientMessage.WsSubscribe("token", "cto", true);
            WsServerMessage event = new WsServerMessage.WsEvent(88, AT, Option.some(4711L), EventKind.UNIT_FINISHED, Option.some("FAILED"),
                                                                "unit-finished 4711 seq=88");

            assertThat(ok(MAPPER.writeAsString(subscribe))).isEqualTo(
                "{\"subscribe\":{\"token\":\"token\",\"subscriber\":\"cto\",\"terminal\":true}}");
            assertThat(roundTrip(subscribe, WsClientMessage.class)).isEqualTo(subscribe);
            assertThat(roundTrip(new WsClientMessage.WsAck(88), WsClientMessage.class)).isEqualTo(new WsClientMessage.WsAck(88));
            assertThat(roundTrip(event, WsServerMessage.class)).isEqualTo(event);
            assertThat(ok(MAPPER.writeAsString(event))).contains("\"kind\":\"unit-finished\"");
            assertThat(roundTrip(new WsServerMessage.WsGap(3, 9), WsServerMessage.class)).isEqualTo(new WsServerMessage.WsGap(3, 9));
            assertThat(roundTrip(new WsServerMessage.WsError("unauthorized"), WsServerMessage.class))
                .isEqualTo(new WsServerMessage.WsError("unauthorized"));
        }

        @Test
        void refusalSeedRows_parseIntoRules() {
            var exclude = ok(MAPPER.readString("""
                {"excludeTestClass":{"simpleClassName":"StreamPublishReshuffleTest","disabledMarker":"#1553","runner":"failsafe"}}
                """, RefusalMatcherDto.class));
            var designSeed = ok(MAPPER.readString("""
                {"excludeTestClass":{"simpleClassName":"StreamPublishReshuffleTest","disabledMarker":"#1553"}}
                """, RefusalMatcherDto.class));
            var itTest = ok(MAPPER.readString("{\"itTestRequired\":{\"moduleSegment\":\"forge-tests\"}}", RefusalMatcherDto.class));

            assertThat(ok(exclude.toDomain("R-1553"))).isEqualTo(RefusalRule.ExcludeTestClass.excludeTestClass("R-1553",
                                                                                                             "StreamPublishReshuffleTest",
                                                                                                             "#1553",
                                                                                                             Option.some(TestRunner.FAILSAFE)));
            assertThat(ok(designSeed.toDomain("R-1553"))).isEqualTo(RefusalRule.ExcludeTestClass.excludeTestClass("R-1553",
                                                                                                                "StreamPublishReshuffleTest",
                                                                                                                "#1553",
                                                                                                                Option.none()));
            assertThat(ok(itTest.toDomain("R-FAILSAFE"))).isEqualTo(RefusalRule.ItTestRequired.itTestRequired("R-FAILSAFE", "forge-tests"));
            assertThat(failure(new RefusalMatcherDto.ExcludeTestClass("Bad Name", "#1", Option.none()).toDomain("R-X"))).isNotNull();
                        assertThat(failure(new RefusalMatcherDto.ExcludeTestClass("Ok", "#1", Option.some("junit")).toDomain("R-X"))).isNotNull();
            assertThat(failure(new RefusalMatcherDto.ExcludeTestClass("Ok", "#1", Option.some("FAILSAFE")).toDomain("R-X")))
                .as("the runner is matched exactly, as it is written")
                .isNotNull();
        }

        /// m1-design §6: what the management API accepts, and the 400 cases.
        @Test
        void refusalAddRequest_parsesIntoANewRefusal() {
            var request = ok(MAPPER.readString("""
                {"code":"R-2190","matcher":{"itTestRequired":{"moduleSegment":"ember"}},"issue":2190,"reason":"flaky ITs","until":"#2190 closes"}
                """, RefusalAddRequest.class));
            var parsed = ok(request.toDomain());

            assertThat(parsed.rule()).isEqualTo(RefusalRule.ItTestRequired.itTestRequired("R-2190", "ember"));
            assertThat(parsed.issue()).isEqualTo(Option.some(2190));
            assertThat(parsed.until()).isEqualTo(Option.some("#2190 closes"));
        }

        @Test
        void refusalAddRequest_refusesStructuralBadOrUnknown() {
            var matcher = new RefusalMatcherDto.ItTestRequired("ember");

            assertThat(failure(new RefusalAddRequest("R-ENV", matcher, Option.none(), "x", Option.none()).toDomain())).isNotNull();
            assertThat(failure(new RefusalAddRequest("R-DISK", matcher, Option.none(), "x", Option.none()).toDomain())).isNotNull();
            assertThat(failure(new RefusalAddRequest("r-lower", matcher, Option.none(), "x", Option.none()).toDomain())).isNotNull();
            assertThat(failure(new RefusalAddRequest("R-OK", null, Option.none(), "x", Option.none()).toDomain())).isNotNull();
            assertThat(failure(new RefusalAddRequest("R-OK", new RefusalMatcherDto.ItTestRequired("a b"), Option.none(), "x",
                                                     Option.none()).toDomain())).isNotNull();
            assertThat(failure(new RefusalAddRequest("R-OK", matcher, Option.none(), " ", Option.none()).toDomain())).isNotNull();
            assertThat(failure(new RefusalAddRequest("R-OK", matcher, Option.some(0), "x", Option.none()).toDomain())).isNotNull();
            assertThat(failure(MAPPER.readString("{\"code\":\"R-OK\",\"matcher\":{\"shell\":{}},\"reason\":\"x\"}",
                                                 RefusalAddRequest.class))).as("unknown matcher kind")
                                                                           .isNotNull();
        }

        @Test
        void refusalDto_listsTheMatcherAsStored() {
            var rule = RefusalRule.ExcludeTestClass.excludeTestClass("R-1553", "StreamPublishReshuffleTest", "#1553",
                                                                     Option.some(TestRunner.FAILSAFE));
            var dto = RefusalDto.refusalDto(rule, Option.some(1553), "flaky", Option.none(), Option.some(AT));

            assertThat(roundTrip(dto, RefusalDto.class)).isEqualTo(dto);
            assertThat(ok(dto.matcher()
                             .toDomain("R-1553"))).isEqualTo(rule);
            assertThat(ok(MAPPER.writeAsString(dto))).contains(
                "\"matcher\":{\"excludeTestClass\":{\"simpleClassName\":\"StreamPublishReshuffleTest\",\"disabledMarker\":\"#1553\",\"runner\":\"failsafe\"}}");
            assertThat(RefusalMatcherDto.from(RefusalRule.ItTestRequired.itTestRequired("R-FAILSAFE", "forge-tests")))
                .isEqualTo(new RefusalMatcherDto.ItTestRequired("forge-tests"));
        }

        @Test
        void manifestView_roundTrips() {
            var view = new ManifestView(true, true, List.of(ENTRY), List.of(OPTIONAL_ENTRY), List.of("org.example.Gone"), List.of(), List.of("aether/ember"));

            assertThat(roundTrip(view, ManifestView.class)).isEqualTo(view);
        }

        @Test
        void manifestDto_capsSortedListsAt500() {
            var many = IntStream.range(0, 600)
                                                 .mapToObj(index -> "org.example.C%04d".formatted(index))
                                                 .toList();
            var manifest = ResultRecordDto.ManifestDto.manifestDto(true, many.reversed(), List.of(), List.of(), 3);

                        assertThat(manifest.missing()).hasSize(ResultRecordDto.ManifestDto.MAX_LISTED)
                                          .startsWith("org.example.C0000")
                                          .endsWith("org.example.C0499");
            assertThat(ResultRecordDto.ManifestDto.manifestDto(true, List.of(), List.of(), many.reversed(), 0)
                                                  .notReached()).as("modules: sorted, never capped")
                                                                .hasSize(600)
                                                                .isSorted();
        }

        @Test
        void evidenceRequest_buildsTheWorkerLaunchVerb() {
            var ref = UnitRef.unitRef(ok(JobId.jobId(4711)), UnitName.GATE);

            assertThat(EvidenceRequest.evidenceRequest(ref, EvidenceKind.SMALL)
                                      .verbArgs()).containsExactly("evidence", "4711", "gate", "small");
            assertThat(EvidenceRequest.evidenceRequest(ref, EvidenceKind.FULL)
                                      .verbArgs()).containsExactly("evidence", "4711", "gate", "full");
        }

        @Test
        void unitRefDto_validatesOnTheWayIn() {
            assertThat(ok(new UnitRefDto(4711, "main", 1).toDomain()).containerName()).isEqualTo("temper-4711-main");
            assertThat(failure(new UnitRefDto(0, "main", 1).toDomain())).isNotNull();
        }

        @Test
        void hostFacts_mapBothWays() {
            assertThat(HostFactsDto.hostFactsDto(ok(FACTS.toDomain()))).isEqualTo(FACTS);
            assertThat(failure(new HostFactsDto(0, 0, 1, 0, 0, Option.none(), Option.none(), 0, 0, 0, null, List.of()).toDomain())).isNotNull();
            assertThat(failure(new UnitEndFactsDto(null, Option.none(), AT, Map.of(), LEFTOVERS, Option.none()).toDomain())).isNotNull();
            assertThat(ok(new UnitEndFactsDto(FACTS, Option.some(1L), AT, Map.of("usage_usec", 1L), LEFTOVERS, Option.none()).toDomain()).cpuStat())
                .containsEntry("usage_usec", 1L);
            var withMin = new UnitEndFactsDto(FACTS, Option.some(1L), AT, Map.of(), LEFTOVERS, Option.some(4096L));

            assertThat(UnitEndFactsDto.unitEndFactsDto(ok(withMin.toDomain()))).isEqualTo(withMin);
        }

        @Test
        void unitEndFacts_withoutTcLeftovers_readAsAnUnverifiedReap() {
            var json = ok(MAPPER.writeAsString(new UnitEndFactsDto(FACTS, Option.none(), AT, Map.of(), null, Option.none())));
            var facts = ok(ok(MAPPER.readString(json.replace(",\"tcLeftovers\":null", ""), UnitEndFactsDto.class)).toDomain());

            assertThat(facts.tcLeftovers()).isEqualTo(TcLeftovers.tcLeftovers(List.of(), Option.some(TcLeftoversDto.NOT_REPORTED)));
            assertThat(failure(new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto(" ", "s")), Option.none()).toDomain()))
                .isNotNull();
            var badLeftover = new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto(" ", "s")), Option.none());
            var kept = ok(new UnitEndFactsDto(FACTS, Option.some(7L), AT, Map.of("usage_usec", 1L), badLeftover, Option.none()).toDomain());

            assertThat(kept.memoryPeakBytes()).as("a bad leftover entry does not cost the other end facts")
                                              .isEqualTo(Option.some(7L));
            assertThat(kept.tcLeftovers().containers()).isEmpty();
            assertThat(kept.tcLeftovers().unverified().or("")).startsWith(TcLeftoversDto.UNREADABLE);
        }

        @Test
        void hostFacts_absentNotes_readAsEmpty() {
            var json = ok(MAPPER.writeAsString(FACTS)).replace(",\"notes\":[]", "");

            assertThat(ok(ok(MAPPER.readString(json, HostFactsDto.class)).toDomain()).notes()).isEmpty();
        }
    }

    /// m1-design §2.2 / §2.5: the manifest travels whole, in chunks the controller folds back.
    @Nested
    class Manifests {
        private static ManifestOutcome.Computed computed(int entries) {
            return ManifestOutcome.Computed.computed(IntStream.range(0, entries)
                                                                               .mapToObj(index -> ManifestEntry.manifestEntry("core",
                                                                                                                              "org.example.T" + index,
                                                                                                                              Runner.SUREFIRE))
                                                                               .collect(Collectors.toSet()),
                                                     Set.of(ManifestEntry.manifestEntry("core", "org.example.FactoryTest", Runner.SUREFIRE)),
                                                     List.of("aether/ember"),
                                                     Set.of("org.example.Slow"));
        }

        private static ManifestOutcomeDto folded(List<Message.UnitManifest> chunks) {
            return chunks.stream()
                         .map(Message.UnitManifest::outcome)
                         .reduce(ManifestOutcomeDto::merge)
                         .orElseThrow();
        }

        @Test
        void unitManifests_splitAtTheChunkSize_andFoldBackToTheOutcome() {
            var outcome = computed(Message.MAX_ROWS_PER_CHUNK * 2 + 1);
            var chunks = Message.UnitManifest.unitManifests(4713, "gate", 1, ManifestOutcomeDto.manifestOutcomeDto(outcome));

            assertThat(chunks).hasSize(3);
            assertThat(chunks.stream()
                             .map(Message.UnitManifest::chunk)).containsExactly(0, 1, 2);
            assertThat(chunks.stream()
                             .map(Message.UnitManifest::last)).containsExactly(false, false, true);
            assertThat(chunks.getFirst()
                             .outcome()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                                first -> assertThat(first.notReached()).isEmpty());
            assertThat(chunks.getFirst()
                             .outcome()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                                first -> assertThat(first.optional()).isEmpty());
            assertThat(ok(folded(chunks).toDomain())).isEqualTo(outcome);
            chunks.forEach(chunk -> assertThat(roundTrip(chunk, Message.class)).isEqualTo(chunk));
        }

        @Test
        void unitManifests_emptyAndUnsupported_areOneChunk() {
            var empty = ManifestOutcome.Computed.computed(Set.of(), Set.of(), List.of(), Set.of());
            var unsupported = new ManifestOutcomeDto.Unsupported("includesFile in core");

            assertThat(Message.UnitManifest.unitManifests(1, "main", 1, ManifestOutcomeDto.manifestOutcomeDto(empty))).hasSize(1);
            assertThat(Message.UnitManifest.unitManifests(1, "main", 1, unsupported)).containsExactly(new Message.UnitManifest(1, "main", 1, 0, true,
                                                                                                                             unsupported));
            assertThat(ok(unsupported.toDomain())).isInstanceOfSatisfying(ManifestOutcome.Unsupported.class,
                                                                          refused -> assertThat(refused.reason()).isEqualTo("includesFile in core"));
        }

        @Test
        void merge_unsupportedWins_inEitherOrder() {
            var computed = ManifestOutcomeDto.manifestOutcomeDto(computed(2));
            var unsupported = new ManifestOutcomeDto.Unsupported("tag expression");

            assertThat(computed.merge(unsupported)).isEqualTo(unsupported);
            assertThat(unsupported.merge(computed)).isEqualTo(unsupported);
        }

        @Test
        void manifestSpecDto_mapsBothWays_andValidates() {
            var spec = ok(SPEC.toDomain());

            assertThat(spec.reach()).isEqualTo(Reach.INTEGRATION_TEST);
            assertThat(ManifestSpecDto.manifestSpecDto(spec)).isEqualTo(SPEC);
            assertThat(failure(new ManifestSpecDto(List.of(), "test", Map.of(), Option.none(), Option.none()).toDomain())).isNotNull();
            assertThat(failure(new ManifestSpecDto(List.of("mvn"), "verify", Map.of(), Option.none(), Option.none()).toDomain())).isNotNull();
            assertThat(failure(new ManifestEntryDto("core", "X", "junit").toDomain())).isNotNull();
            assertThat(failure(new ManifestOutcomeDto.Unsupported(" ").toDomain())).isNotNull();
        }

                /// Each constant pinned by name in both directions: a WAITING/HELD swap would flip reconcile rules 3 and 4.
        @Test
        void holderStatusDto_mapsEachConstantByName() {
            assertThat(HolderStatusDto.WAITING.toDomain()).isEqualTo(HolderStatus.WAITING);
            assertThat(HolderStatusDto.HELD.toDomain()).isEqualTo(HolderStatus.HELD);
            assertThat(HolderStatusDto.DEAD.toDomain()).isEqualTo(HolderStatus.DEAD);
            assertThat(HolderStatusDto.from(HolderStatus.WAITING)).isEqualTo(HolderStatusDto.WAITING);
            assertThat(HolderStatusDto.from(HolderStatus.HELD)).isEqualTo(HolderStatusDto.HELD);
            assertThat(HolderStatusDto.from(HolderStatus.DEAD)).isEqualTo(HolderStatusDto.DEAD);
        }

        @Test
        void manifestWireConstants_mapByNameInBothDirections() {
            var spec = ManifestSpec.manifestSpec(List.of("mvn"), Reach.TEST, Map.of(), Option.none(), Option.none());

            assertThat(ManifestSpecDto.manifestSpecDto(spec).reach()).isEqualTo("test");
            assertThat(ok(new ManifestSpecDto(List.of("mvn"), "test", Map.of(), Option.none(), Option.none()).toDomain()).reach())
                .isEqualTo(Reach.TEST);
            assertThat(ManifestEntryDto.manifestEntryDto(ManifestEntry.manifestEntry("m", "C", Runner.FAILSAFE)).runner()).isEqualTo("failsafe");
            assertThat(ManifestEntryDto.manifestEntryDto(ManifestEntry.manifestEntry("m", "C", Runner.SUREFIRE)).runner()).isEqualTo("surefire");
            assertThat(ok(new ManifestEntryDto("m", "C", "failsafe").toDomain()).runner()).isEqualTo(Runner.FAILSAFE);
            assertThat(ok(new ManifestEntryDto("m", "C", "surefire").toDomain()).runner()).isEqualTo(Runner.SUREFIRE);
        }

        @Test
        void manifestSpecDto_refusesNullElements() {
            var argv = new ArrayList<String>(List.of("mvn"));
            var properties = new HashMap<String, String>();

            argv.add(null);
            properties.put("skipTests", null);
            assertThat(failure(new ManifestSpecDto(argv, "test", Map.of(), Option.none(), Option.none()).toDomain())).isNotNull();
            assertThat(failure(new ManifestSpecDto(List.of("mvn"), "test", properties, Option.none(), Option.none()).toDomain())).isNotNull();
        }

        @Test
        void manifestOutcomeDto_isDeterministic_whateverTheInputOrder() {
            var first = ManifestOutcome.Computed.computed(computed(30).entries(), computed(4).entries(), List.of("b", "a"),
                                                          Set.of("Y", "X"));
            var second = ManifestOutcome.Computed.computed(Set.copyOf(new ArrayList<>(computed(30).entries()).reversed()),
                                                           Set.copyOf(new ArrayList<>(computed(4).entries()).reversed()),
                                                           List.of("a", "b"),
                                                           Set.of("X", "Y"));

            assertThat(ManifestOutcomeDto.manifestOutcomeDto(first)).isEqualTo(ManifestOutcomeDto.manifestOutcomeDto(second));
        }
    }

    /// Wire names pinned against m0-design §4.2 (messages) and §4.3 (WS DTOs) by a hand-written table, not
    /// derived from the annotations: the round-trip tests only prove self-consistency, so swapping two
    /// `@JsonSubTypes` names would stay green there but turns exactly two rows here red.
    @Nested
    class WireNames {
        private static final Map<Class<?>, String> MESSAGE_NAMES = Map.ofEntries(Map.entry(Message.Hello.class, "hello"),
                                                                                 Map.entry(Message.Welcome.class, "welcome"),
                                                                                 Map.entry(Message.Incompatible.class, "incompatible"),
                                                                                 Map.entry(Message.PrepareJob.class, "prepareJob"),
                                                                                 Map.entry(Message.JobPrepared.class, "jobPrepared"),
                                                                                 Map.entry(Message.StartUnit.class, "startUnit"),
                                                                                 Map.entry(Message.UnitStarted.class, "unitStarted"),
                                                                                 Map.entry(Message.UnitStartFailed.class, "unitStartFailed"),
                                                                                 Map.entry(Message.Reattach.class, "reattach"),
                                                                                 Map.entry(Message.Collect.class, "collect"),
                                                                                 Map.entry(Message.CancelUnit.class, "cancelUnit"),
                                                                                 Map.entry(Message.Ack.class, "ack"),
                                                                                 Map.entry(Message.UnitReportAck.class, "unitReportAck"),
                                                                                 Map.entry(Message.LogTail.class, "logTail"),
                                                                                 Map.entry(Message.LogChunk.class, "logChunk"),
                                                                                 Map.entry(Message.FactsRequest.class, "factsRequest"),
                                                                                 Map.entry(Message.Facts.class, "facts"),
                                                                                 Map.entry(Message.Shutdown.class, "shutdown"),
                                                                                 Map.entry(Message.Heartbeat.class, "heartbeat"),
                                                                                 Map.entry(Message.UnitExited.class, "unitExited"),
                                                                                 Map.entry(Message.UnitReport.class, "unitReport"),
                                                                                 Map.entry(Message.Failure.class, "failure"),
                                                                                 Map.entry(Message.UnitHolding.class, "unitHolding"),
                                                                                 Map.entry(Message.UnitManifest.class, "unitManifest"),
                                                                                 Map.entry(Message.GcJobs.class, "gcJobs"),
                                                                                 Map.entry(Message.GcDone.class, "gcDone"),
                                                                                 Map.entry(Message.DiscardUnit.class, "discardUnit"));

        static Stream<Arguments> everyMessage() {
            return Frames.EVERY_MESSAGE.stream()
                                       .map(message -> Arguments.of(MESSAGE_NAMES.get(message.getClass()), message));
        }

        static Stream<Arguments> everyWsMessage() {
            return Stream.of(Arguments.of("subscribe", new WsClientMessage.WsSubscribe("token", "cto", true), WsClientMessage.class),
                             Arguments.of("ack", new WsClientMessage.WsAck(88), WsClientMessage.class),
                             Arguments.of("event",
                                          new WsServerMessage.WsEvent(88, AT, Option.none(), EventKind.UNIT_FINISHED, Option.none(), "x"),
                                          WsServerMessage.class),
                             Arguments.of("gap", new WsServerMessage.WsGap(3, 9), WsServerMessage.class),
                             Arguments.of("error", new WsServerMessage.WsError("unauthorized"), WsServerMessage.class));
        }

        @Test
        void table_coversEveryPermittedMessageSubtype() {
            assertThat(MESSAGE_NAMES).hasSize(27);
            assertThat(MESSAGE_NAMES.keySet()).containsExactlyInAnyOrder(Message.class.getPermittedSubclasses());
            assertThat(Frames.EVERY_MESSAGE.stream()
                                           .map(Object::getClass)
                                           .distinct()).hasSize(27);
        }

        /// Protocol v1 additions (m0-design §12.7), field names pinned by hand: the unit on `failure`, the
        /// Testcontainers reap in the end facts and the result's units, and the facts `notes`.
        @Test
        void v1Additions_haveTheirPinnedFieldNames() {
            assertThat(ok(MAPPER.writeAsString(Message.Failure.failure("lost", "gone", new UnitRefDto(4711, "main", 1)))))
                .isEqualTo("{\"failure\":{\"code\":\"lost\",\"detail\":\"gone\",\"unit\":{\"job\":4711,\"unit\":\"main\",\"attempt\":1}}}");
            assertThat(ok(MAPPER.writeAsString(Message.Failure.failure("bad-frame", "x"))))
                .isEqualTo("{\"failure\":{\"code\":\"bad-frame\",\"detail\":\"x\",\"unit\":null}}");
            assertThat(ok(MAPPER.writeAsString(new UnitEndFactsDto(FACTS, Option.none(), AT, Map.of(), LEFTOVERS, Option.none()))))
                .contains("\"tcLeftovers\":{\"containers\":[{\"id\":\"c0ffee\",\"session\":\"s-1\"}],\"unverified\":null}");
            assertThat(ok(MAPPER.writeAsString(ResultRecordDto.UnitResultDto.unitResultDto("main", Option.none(), Option.none(), Option.none(),
                                                                                 Option.none(), Option.none(), Option.some(LEFTOVERS),
                                                                                 Option.none()))))
                .contains("\"tcLeftovers\":{\"containers\":[{\"id\":\"c0ffee\"");
            assertThat(ok(MAPPER.writeAsString(new HostFactsDto(0, 0, 1, 0, 0, Option.some(2), Option.some(3), 0, 0, 0, AT,
                                                                List.of("bb-queue: unparsable")))))
                .contains("\"bbRunning\":2,\"bbWaiting\":3,")
                .endsWith("\"notes\":[\"bb-queue: unparsable\"]}");
        }

        /// Protocol v2 additions (m1-design §1.3, §2.5, §4.3, §4.5), field names pinned by hand.
        @Test
        void v2Additions_haveTheirPinnedFieldNames() {
            assertThat(ok(MAPPER.writeAsString(new Message.UnitHolding(4713, "gate", 1, 7, FACTS))))
                .startsWith("{\"unitHolding\":{\"job\":4713,\"unit\":\"gate\",\"attempt\":1,\"markerEpochNs\":7,\"facts\":{");
            assertThat(ok(MAPPER.writeAsString(new Message.UnitStarted(4713, "gate", 1, "d00d", "sha256:abc", 7, AT, FACTS,
                                                                       Option.some("20261013-1")))))
                .startsWith("{\"unitStarted\":{\"job\":4713,\"unit\":\"gate\",\"attempt\":1,\"containerId\":\"d00d\",\"imageId\":\"sha256:abc\","
                            + "\"markerEpochNs\":7,\"startedAt\":\"2026-10-13T10:00:00Z\",\"facts\":{")
                .endsWith("},\"ticket\":\"20261013-1\"}}");
            assertThat(ok(MAPPER.writeAsString(new Message.UnitStarted(4711, "main", 1, "c0ffee", "sha256:abc", 7, AT, FACTS,
                                                                       Option.none()))))
                .endsWith("},\"ticket\":null}}");
            assertThat(ok(MAPPER.readString("{\"unitStarted\":{\"job\":1,\"unit\":\"main\",\"attempt\":1,\"containerId\":\"c\",\"imageId\":\"i\","
                                            + "\"markerEpochNs\":7,\"startedAt\":\"2026-10-13T10:00:00Z\",\"facts\":"
                                            + ok(MAPPER.writeAsString(FACTS)) + "}}",
                                            Message.class)))
                .isInstanceOfSatisfying(Message.UnitStarted.class, started -> assertThat(started.ticket()).isEqualTo(Option.none()));
            assertThat(ok(MAPPER.writeAsString(new PortGroupHoldDto("temper", 5)))).isEqualTo("{\"owner\":\"temper\",\"priority\":5}");
            assertThat(ok(MAPPER.writeAsString(SPEC)))
                .isEqualTo("{\"pomArgv\":[\"mvn\",\"-B\",\"" + ManifestSpec.EFFECTIVE_POM_GOAL + "\","
                           + "\"-Doutput=${ws}/.home/.temper/effective-pom-gate.xml\",\"-Dmaven.repo.local=${ws}/.m2-local\"],"
                           + "\"reach\":\"integration-test\",\"userProperties\":{\"skipITs\":\"false\"},\"test\":null,\"itTest\":\"*IT\"}");
            assertThat(ok(MAPPER.writeAsString(new DiskUnitDto(1, "gate", true, Option.none(), ContainerStatus.ABSENT,
                                                               Option.some(HolderStatusDto.WAITING), Option.none(), Option.some("temper"), Option.none()))))
                .endsWith("\"container\":\"ABSENT\",\"holder\":\"WAITING\",\"ticket\":null,\"instance\":\"temper\",\"attempt\":null}");
            assertThat(ok(MAPPER.writeAsString(new ManifestOutcomeDto.Computed(List.of(ENTRY), List.of(OPTIONAL_ENTRY), List.of("m"),
                                                                               List.of("A")))))
                .isEqualTo("{\"computed\":{\"entries\":[{\"module\":\"core\",\"class\":\"org.example.FooTest\",\"runner\":\"surefire\"}],"
                           + "\"optional\":[{\"module\":\"core\",\"class\":\"org.example.FactoryTest\",\"runner\":\"surefire\"}],"
                           + "\"notReached\":[\"m\"],\"unresolvedAnnotations\":[\"A\"]}}");
            assertThat(ok(MAPPER.writeAsString(new ManifestOutcomeDto.Unsupported("x")))).isEqualTo("{\"unsupported\":{\"reason\":\"x\"}}");
            assertThat(ok(MAPPER.writeAsString(new Message.UnitManifest(1, "gate", 1, 0, true, new ManifestOutcomeDto.Unsupported("x")))))
                .isEqualTo("{\"unitManifest\":{\"job\":1,\"unit\":\"gate\",\"attempt\":1,\"chunk\":0,\"last\":true,\"outcome\":{\"unsupported\":{\"reason\":\"x\"}}}}");
            assertThat(ok(MAPPER.writeAsString(new Message.GcJobs(List.of(1L))))).isEqualTo("{\"gcJobs\":{\"jobs\":[1]}}");
            assertThat(ok(MAPPER.writeAsString(new Message.GcDone(List.of(1L), List.of(new Message.GcDone.Kept(2, "busy"))))))
                .isEqualTo("{\"gcDone\":{\"deleted\":[1],\"kept\":[{\"job\":2,\"reason\":\"busy\"}]}}");
            assertThat(ok(MAPPER.writeAsString(new UnitEndFactsDto(FACTS, Option.none(), AT, Map.of(), LEFTOVERS, Option.some(9L)))))
                .endsWith("\"memAvailableMinKb\":9}");
            assertThat(ok(MAPPER.writeAsString(VoidReason.DISK))).isEqualTo("\"disk\"");
        }

        /// Protocol v3 additions (m4-design §7), field names pinned by hand: the attempt on every unit frame both ways,
        /// on the heartbeat's and the failure's unit, on the disk scan (absent before M4), and `discardUnit`.
        @Test
        void v3Additions_haveTheirPinnedFieldNames() {
            assertThat(ok(MAPPER.writeAsString(new Message.DiscardUnit(4711, "main", 2))))
                .isEqualTo("{\"discardUnit\":{\"job\":4711,\"unit\":\"main\",\"attempt\":2}}");
            assertThat(ok(MAPPER.writeAsString(new Message.Collect(1, "main", 2)))).isEqualTo("{\"collect\":{\"job\":1,\"unit\":\"main\",\"attempt\":2}}");
            assertThat(ok(MAPPER.writeAsString(new Message.CancelUnit(1, "main", 2))))
                .isEqualTo("{\"cancelUnit\":{\"job\":1,\"unit\":\"main\",\"attempt\":2}}");
            assertThat(ok(MAPPER.writeAsString(new Message.UnitReportAck(1, "main", 2))))
                .isEqualTo("{\"unitReportAck\":{\"job\":1,\"unit\":\"main\",\"attempt\":2}}");
            assertThat(ok(MAPPER.writeAsString(new Message.LogTail(1, "main", 2, 200))))
                .isEqualTo("{\"logTail\":{\"job\":1,\"unit\":\"main\",\"attempt\":2,\"lines\":200}}");
            assertThat(ok(MAPPER.writeAsString(new Message.UnitReport(1, "main", 2, 0, true, List.of()))))
                .isEqualTo("{\"unitReport\":{\"job\":1,\"unit\":\"main\",\"attempt\":2,\"chunk\":0,\"last\":true,\"rows\":[]}}");
            assertThat(ok(MAPPER.writeAsString(new Message.UnitStartFailed(1, "main", 2, StartFailureKind.ERROR, "infra: stale-attempt",
                                                                           Option.none()))))
                .startsWith("{\"unitStartFailed\":{\"job\":1,\"unit\":\"main\",\"attempt\":2,");
            assertThat(ok(MAPPER.writeAsString(new Message.Heartbeat(List.of(new UnitRefDto(1, "main", 2)), FACTS))))
                .startsWith("{\"heartbeat\":{\"running\":[{\"job\":1,\"unit\":\"main\",\"attempt\":2}],");
            assertThat(ok(MAPPER.writeAsString(new DiskUnitDto(1, "main", true, Option.none(), ContainerStatus.RUNNING, Option.none(),
                                                               Option.none(), Option.none(), Option.some(2)))))
                .endsWith("\"instance\":null,\"attempt\":2}");
            assertThat(ok(MAPPER.writeAsString(firstStartUnit()))).contains("\"unit\":\"main\",\"attempt\":1,\"kind\":\"MVN\"");
        }

        private static Message firstStartUnit() {
            return Frames.EVERY_MESSAGE.stream()
                                       .filter(Message.StartUnit.class::isInstance)
                                       .findFirst()
                                       .orElseThrow();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("everyMessage")
        void message_isWrappedInItsSpecName(String name, Message message) {
            var json = ok(MAPPER.writeAsString(message));

            assertThat(json).startsWith("{\"" + name + "\":");
            assertThat(ok(MAPPER.readString(json, Message.class))).isEqualTo(message);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("everyWsMessage")
        <T> void wsMessage_isWrappedInItsSpecName(String name, T message, Class<T> family) {
            var json = ok(MAPPER.writeAsString(message));

            assertThat(json).startsWith("{\"" + name + "\":");
            assertThat(ok(MAPPER.readString(json, family))).isEqualTo(message);
        }
    }
}
