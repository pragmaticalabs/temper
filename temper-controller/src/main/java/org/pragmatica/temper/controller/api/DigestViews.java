package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.pragmatica.temper.controller.digest.Digest;
import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.controller.policy.Fit;
import org.pragmatica.temper.controller.policy.GrantExplain;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.protocol.api.HostLossDto;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;
import org.pragmatica.temper.protocol.api.UserPropertyDto;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.UnitRow;
import org.pragmatica.temper.store.pg.WireRecords;


/// [Digest.Response] → [DigestView] (dashboard design §2). An event's verdict, sha and ref are the ones its payload
/// recorded; a queued job's state is the one [Views#jobState] derives, so a QUEUED job one of whose units runs with no
/// recorded start reads UNKNOWN here as in `GET /jobs/{id}` (ruling R-13), taken at the response's `asOf`.
sealed interface DigestViews {
    static DigestView digestView(Digest.Response response, Duration startBound, Set<UnitAttempt> unrecorded) {
        var window = response.window();

        return DigestView.digestView(window.cursor().wire(),
                                     window.more(),
                                     window.gap().map(gap -> DigestView.Gap.gap(gap.from(), gap.to())),
                                     response.asOf(),
                                     response.finished().stream().map(DigestViews::finished).toList(),
                                     response.started().stream().map(DigestViews::started).toList(),
                                     response.other().stream().map(DigestViews::other).toList(),
                                     response.queue()
                                             .stream()
                                             .map(queued -> queued(queued,
                                                                   response.asOf(),
                                                                   startBound,
                                                                   unrecorded))
                                             .toList(),
                                     response.queued(),
                                     response.hosts().stream().map(host -> host(host, response.asOf())).toList(),
                                     ApiRoutes.maintenanceView(response.latestDump(), response.lastGoodDump()));
    }

    private static DigestView.Finished finished(Digest.JobEvent named) {
        var event = named.event();
        var payload = event.payload();

        return DigestView.Finished.finished(event.seq(),
                                            event.at(),
                                            event.job().map(JobId::value),
                                            WireRecords.eventKind(event.kind()),
                                            payload.verdict(),
                                            payload.reason(),
                                            payload.sha(),
                                            payload.ref(),
                                            named.job().map(DigestViews::owner),
                                            named.job().map(JobRow::priority),
                                            payload.counts().map(CountsDto::countsDto),
                                            payload.known(),
                                            named.job()
                                                 .map(job -> UserPropertyDto.of(RecipeDto.from(job.recipe())))
                                                 .or(List.of()));
    }

    private static DigestView.Started started(Digest.JobEvent named) {
        var event = named.event();
        var payload = event.payload();

        return DigestView.Started.started(event.seq(),
                                          event.at(),
                                          event.job().map(JobId::value),
                                          payload.unit(),
                                          payload.sha(),
                                          payload.ref(),
                                          named.job().map(DigestViews::owner),
                                          named.job().map(JobRow::priority));
    }

    private static DigestView.Other other(EventRow event) {
        var payload = event.payload();

        return DigestView.Other.other(event.seq(),
                                      event.at(),
                                      event.job().map(JobId::value),
                                      WireRecords.eventKind(event.kind()),
                                      payload.unit(),
                                      payload.verdict(),
                                      payload.supersededBy());
    }

    private static DigestView.Queued queued(Digest.Queued queued,
                                            Instant now,
                                            Duration startBound,
                                            Set<UnitAttempt> unrecorded) {
        var job = queued.job();

        return DigestView.Queued.queued(queued.position(),
                                        job.id().value(),
                                        Views.jobState(job, now, startBound, unrecorded),
                                        job.priority(),
                                        owner(job),
                                        job.sha().map(Sha::value),
                                        job.ref().map(Ref::value),
                                        job.submittedAt(),
                                        queued.hold().flatMap(UnitRow::portGroup).map(PortGroup::wire),
                                        queued.hold().flatMap(UnitRow::portGroupSince),
                                        queued.blocked().stream().map(DigestViews::blocked).toList());
    }

    /// The wire names and details [DigestView.Blocked] documents. Both switches are exhaustive over the reasons, so a
    /// new reason does not compile until it has its wire form.
    private static DigestView.Blocked blocked(Digest.Blocked blocked) {
        return DigestView.Blocked.blocked(blocked.host().value(),
                                          wire(blocked.reason()),
                                          detail(blocked.reason()));
    }

    private static String wire(GrantExplain.Reason reason) {
        return switch (reason) {
            case GrantExplain.Capped _ -> "cap";
            case Fit.Memory _ -> "memory";
            case Fit.MemFloor _ -> "mem-floor";
            case Fit.DiskFloor _ -> "disk-floor";
            case Fit.PortGroup _ -> "port-group";
            case GrantExplain.StarvationHold _ -> "starvation-hold";
            case GrantExplain.NoLink _ -> "no-link";
            case GrantExplain.Busy _ -> "busy";
            case GrantExplain.Unknown _ -> "unknown";
            case GrantExplain.NotRead _ -> "not-read";
            case GrantExplain.Retired _ -> "retired";
            case GrantExplain.Lost _ -> "lost";
            case GrantExplain.SharedCpu _ -> "shared-cpu";
        };
    }

    private static String detail(GrantExplain.Reason reason) {
        return switch (reason) {
            case GrantExplain.Capped capped -> capped.reactors() + "/" + capped.cap() + " foreign=" + capped.foreign();
            case Fit.Memory memory -> memory.activeMb() + "+" + memory.unitMb() + ">" + memory.budgetMb() + "MB";
            case Fit.MemFloor floor -> memFloor(floor);
            case Fit.DiskFloor disk -> "free=" + disk.freeBytes() + " floor=" + disk.floorBytes();
            case Fit.PortGroup group -> group.units() + "/" + group.maxHolding();
            case GrantExplain.StarvationHold hold -> "head=" + hold.headUnitId() + " since=" + hold.since();
            case GrantExplain.NoLink noLink -> noLink(noLink);
            case GrantExplain.Busy _ -> "round-in-flight";
            case GrantExplain.Unknown _ -> "active-read-failed";
            case GrantExplain.NotRead notRead -> "limit=" + notRead.limit();
            case GrantExplain.Retired retired -> "since=" + retired.at();
            case GrantExplain.Lost lost -> lost.reason() + " since=" + lost.at();
            case GrantExplain.SharedCpu _ -> "dedicated-cpu";
        };
    }

    private static String memFloor(Fit.MemFloor floor) {
        return "free=%d pending=%d unit=%d floor=%dMB".formatted(floor.availableMb(),
                                                                 floor.pendingMb(),
                                                                 floor.unitMb(),
                                                                 floor.floorMb());
    }

    private static String noLink(GrantExplain.NoLink noLink) {
        return noLink.facts()
               ? "link=" + noLink.link()
               : "link=" + noLink.link() + " no-facts";
    }

    /// `retired` is judged at the digest's `asOf`, as `GET /hosts` judges it at its own `now` (m4-design §10).
    private static DigestView.Host host(Digest.Host host, Instant asOf) {
        var marks = host.marks();

        return DigestView.Host.host(host.name().value(),
                                    host.link(),
                                    host.stored(),
                                    host.cap(),
                                    host.reactors(),
                                    host.foreign(),
                                    host.active(),
                                    marks.lost().map(loss -> HostLossDto.hostLossDto(loss.at(), loss.reason())),
                                    marks.sharedCpu(),
                                    marks.retireAt(),
                                    marks.retiredSince(asOf).isPresent());
    }

    private static String owner(JobRow job) {
        return job.submittedBy()
                  .value();
    }

    record unused() implements DigestViews {}
}
