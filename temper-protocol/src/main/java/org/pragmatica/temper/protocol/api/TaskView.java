package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.state.JobState;


/// One task in `GET /api/v1/tasks` and `GET /tasks/{id}` (m3-design §11.1): its facts, its stage and status, its
/// counters and lease (never the token or its hash), and, on `GET /tasks/{id}` and in the create answer, the `detail`:
/// checklist items, rulings, accepted evidence ids and linked jobs. The spot-audit flag is never shown: it is invisible
/// to workers (§7.7), and they hold a read token.
public record TaskView(long id,
                       String slug,
                       String title,
                       List<Integer> issues,
                       TaskBriefDto brief,
                       String repo,
                       String baseRef,
                       String branch,
                       Option<Integer> pr,
                       boolean critical,
                       int priority,
                       List<Long> after,
                       String stage,
                       String status,
                       Option<String> raisedFrom,
                       int round,
                       int roundCap,
                       int ciRounds,
                       int visit,
                       int visitExpiries,
                       int visitFailures,
                       long epoch,
                       Option<String> leaseHolder,
                       Option<Instant> leaseUntil,
                       Option<Instant> stepDeadline,
                       Option<String> readPoint,
                       Option<String> head,
                       Option<Instant> readySince,
                       List<Long> waitJobs,
                       boolean waitCi,
                       String createdBy,
                       Instant createdAt,
                       Option<Instant> closedAt,
                       Option<Detail> detail) {
    /// The checklist (§7.6), every ruling, the accepted evidence ids and the linked jobs.
    public record Detail(List<Item> items, List<RulingDto> rulings, List<Long> evidence, List<LinkedJob> jobs) {}

    public record Item(String item, int raisedRound, String status) {}

    /// A ruling as raised and, once given, as ruled (§5.5).
    public record RulingDto(long id,
                            String raisedFrom,
                            int raisedVisit,
                            String kind,
                            ParkRequestDto request,
                            Instant raisedAt,
                            String raisedBy,
                            Option<Instant> ruledAt,
                            Option<String> ruledBy,
                            Option<String> option,
                            Option<String> text,
                            Option<String> know) {}

    /// A job linked to the task, the epoch it was submitted under and its state.
    public record LinkedJob(long job, long epoch, JobState state) {}
}
