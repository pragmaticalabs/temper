package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.task.ParkRequest;
import org.pragmatica.temper.domain.task.RulingKind;
import org.pragmatica.temper.domain.task.Stage;


/// What `GET /tasks/{id}` and a claim answer show besides the row (m3-design §6.1, §11.1): the checklist (`task_items`,
/// filled by WP-2), every ruling, open or ruled, the accepted evidence ids, and the jobs linked to the task.
public record TaskDetail(List<Item> items, List<Ruling> rulings, List<Long> evidence, List<LinkedJob> jobs) {
    public TaskDetail {
        items = List.copyOf(items);
        rulings = List.copyOf(rulings);
        evidence = List.copyOf(evidence);
        jobs = List.copyOf(jobs);
    }

    public static TaskDetail taskDetail(List<Item> items,
                                        List<Ruling> rulings,
                                        List<Long> evidence,
                                        List<LinkedJob> jobs) {
        return new TaskDetail(items, rulings, evidence, jobs);
    }

    /// A `task_items` row: the item id, the round it was raised in and its status (`open`, `fixed`, …).
    public record Item(String item, int raisedRound, String status) {
        public static Item item(String item, int raisedRound, String status) {
            return new Item(item, raisedRound, status);
        }
    }

    /// A `task_rulings` row (§5.5): the request as raised, and the ruling once given (none while open).
    public record Ruling(long id,
                         Stage raisedFrom,
                         int raisedVisit,
                         RulingKind kind,
                         ParkRequest request,
                         Instant raisedAt,
                         String raisedBy,
                         Option<Given> ruling) {
        public static Ruling ruling(long id,
                                    Stage raisedFrom,
                                    int raisedVisit,
                                    RulingKind kind,
                                    ParkRequest request,
                                    Instant raisedAt,
                                    String raisedBy,
                                    Option<Given> ruling) {
            return new Ruling(id, raisedFrom, raisedVisit, kind, request, raisedAt, raisedBy, ruling);
        }
    }

    /// A given ruling: when, by whom, the option, the text, the `know:` SHA (stored as text, ruling R-M3-28).
    public record Given(Instant at, String by, String option, String text, Option<Sha> know) {
        public static Given given(Instant at, String by, String option, String text, Option<Sha> know) {
            return new Given(at, by, option, text, know);
        }
    }

    /// A job linked to the task (`jobs.task_id`), the epoch it was submitted under and its state now.
    public record LinkedJob(JobId job, long epoch, JobState state) {
        public static LinkedJob linkedJob(JobId job, long epoch, JobState state) {
            return new LinkedJob(job, epoch, state);
        }
    }
}
