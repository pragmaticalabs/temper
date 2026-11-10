package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.task.TaskId;


/// Store failures. Every failure reported by Postgres or the driver is a [Database] carrying the driver's cause
/// (its SQLSTATE included), so a write-once rejection (SQLSTATE 23000) stays visible through `source()`.
public sealed interface StoreError extends Cause {
    /// Fixed-text failures: a [NewJob] that [JobStore#submit] cannot accept (a caller bug), a statement whose
    /// `RETURNING` produced no row, and a merge the detector planned as new that the insert found recorded or a GO it
    /// planned as outstanding that the update found otherwise (a writer that skipped the T2 lock). None is retryable.
    enum General implements StoreError, Cause.Terminal {
        SOURCE_REQUIRED("A submitted job needs a resolved source"),
        DEDUP_KEY_REQUIRED("A submitted job needs a dedup key"),
        UNITS_REQUIRED("A submitted job needs at least one unit"),
        NO_ROW_RETURNED("A statement returned no row"),
        MERGE_ALREADY_RECORDED("A merge planned as new is recorded already"),
        GO_NOT_OUTSTANDING("A GO planned as outstanding is consumed or invalidated already");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// The database closes idle sessions sooner than a per-PR review lock may sit idle: a lock
    /// session waiting on a slow POST would be terminated mid-work. The controller refuses to start (exit 78).
    record IdleSessionTimeout(String value, String message) implements StoreError, Cause.Terminal {
        /// The shortest idle_session_timeout (seconds) that is safe: the POST (30 s) plus the carry proof (120 s).
        public static final long MINIMUM_SECONDS = 150;

        public static final Fn2<IdleSessionTimeout, String, String> FACTORY = (value, bound) -> new IdleSessionTimeout(value,
                                                                                                                       "idle_session_timeout is " + value
                                                                                                                      + ", below the " + bound
                                                                                                                      + " s a review lock session may sit idle");
    }

    /// The per-PR review lock could not serve the call. `BUSY`: every dedicated lock session is in
    /// use, so the call is refused at once rather than queued. `LOST`: the session that held the lock did not release
    /// it (the session died during the work, or the UNLOCK failed); that session is closed, so the lock ended with it,
    /// but another caller may have held it meanwhile and the work's outcome must be re-read.
    enum PrLock implements StoreError {
        BUSY("Every review lock session is in use"),
        LOST("The review lock session was lost before the lock was released");
        private final String message;
        PrLock(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
        @Override
        public boolean isTransient() {
            return true;
        }
    }

    /// A statement failed in Postgres or in the driver; retry classification follows the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Database(Cause origin, String message) implements StoreError, Cause.Wrapped {
        public static final Fn1<Database, Cause> FACTORY = Causes.forOneValue("Database operation failed", Database::new);

        @Override
        public boolean isTerminal() {
            return origin.isTerminal();
        }

        @Override
        public boolean isTransient() {
            return origin.isTransient();
        }
    }

    /// A stored row that no longer parses into its domain type.
    @SuppressWarnings("JBCT-CAUSE-04")
    record CorruptRow(String table, Cause origin, String message) implements StoreError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<CorruptRow, String, Cause> FACTORY = Causes.forTwoValues("Unreadable %s row",
                                                                                         CorruptRow::new);
    }

    /// A listed migration resource that is not on the classpath.
    record MigrationMissing(String resource, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<MigrationMissing, String> FACTORY = Causes.forOneValue("Migration %s is not on the classpath",
                                                                                       MigrationMissing::new);
    }

    /// A migration resource that exists but cannot be read.
    @SuppressWarnings("JBCT-CAUSE-04")
    record MigrationUnreadable(String resource, Cause origin, String message) implements StoreError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<MigrationUnreadable, String, Cause> FACTORY = Causes.forTwoValues("Cannot read migration %s",
                                                                                                  MigrationUnreadable::new);
    }

    /// An applied migration whose file changed afterwards; startup aborts.
    record MigrationTampered(int version, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<MigrationTampered, Integer> FACTORY = Causes.forOneValue("Migration V%d changed after it was applied",
                                                                                         MigrationTampered::new);
    }

    /// The build's migrations do not run V1, V2, … in list order: at 1-based `position` the list holds `found` instead of
    /// `expected` (a hole, a duplicate or a misorder); startup aborts before the database is touched.
    record MigrationGap(int expected, int position, int found, String message) implements StoreError, Cause.Terminal {
        public static final Fn3<MigrationGap, Integer, Integer, Integer> FACTORY = Causes.forThreeValues("Migration list: expected V%d at position %d, found V%d; the build's migration list is not contiguous; fix the build",
                                                                                                         MigrationGap::new);
    }

    /// An applied migration this build does not carry (the database is ahead of the build); startup aborts.
    record MigrationNotInBuild(int version, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<MigrationNotInBuild, Integer> FACTORY = Causes.forOneValue("Migration V%d is applied but not in this build; deploy a build containing it, or restore the pre-deploy dump (the deploy runbook, Rollback)",
                                                                                           MigrationNotInBuild::new);
    }

    /// A pending migration older than one already applied; applying it now would run the files out of order. The
    /// database came from another build line (it applied `highestApplied` without `version`).
    record MigrationOutOfOrder(int version, int highestApplied, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<MigrationOutOfOrder, Integer, Integer> FACTORY = Causes.forTwoValues("Migration V%d is pending but V%d is already applied; deploy a build whose migrations match the database, or restore the pre-deploy dump (the deploy runbook, Rollback)",
                                                                                                     MigrationOutOfOrder::new);
    }

    record JobNotFound(JobId job, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<JobNotFound, JobId> FACTORY = Causes.forOneValue("Job %s not found", JobNotFound::new);
    }

    record UnitNotFound(JobId job, UnitName unit, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<UnitNotFound, JobId, UnitName> FACTORY = Causes.forTwoValues("Unit %s/%s not found",
                                                                                             UnitNotFound::new);
    }

    record HostNotFound(HostName host, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<HostNotFound, HostName> FACTORY = Causes.forOneValue("Host %s not found",
                                                                                     HostNotFound::new);
    }

    /// A LOST-mark write that would contradict a newer one (m4-design §2.5): `HostStore.lost` for a mark recovery
    /// already cleared, or `HostStore.recovered` for a mark the host no longer holds.
    record StaleLoss(HostName host, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<StaleLoss, HostName> FACTORY = Causes.forOneValue("Host %s: stale LOST mark (recovery cleared it, or the host holds another)",
                                                                                  StaleLoss::new);
    }

    record SubscriberNotFound(String name, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<SubscriberNotFound, String> FACTORY = Causes.forOneValue("Subscriber %s not found",
                                                                                         SubscriberNotFound::new);
    }

    record EvidenceNotFound(long id, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<EvidenceNotFound, Long> FACTORY = Causes.forOneValue("Evidence %d not found",
                                                                                     EvidenceNotFound::new);
    }

    /// A refusal code that already exists, active or expired (the API's 409).
    record RefusalExists(String code, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<RefusalExists, String> FACTORY = Causes.forOneValue("Refusal %s already exists",
                                                                                    RefusalExists::new);
    }

    record RefusalNotFound(String code, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<RefusalNotFound, String> FACTORY = Causes.forOneValue("Refusal %s not found",
                                                                                      RefusalNotFound::new);
    }

    /// An active known-failure entry for this `testId` exists (the API's 409): one active entry per test, so a failure
    /// maps to one issue (m2-design §4.2).
    record KnownExists(String testId, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<KnownExists, String> FACTORY = Causes.forOneValue("An active known entry for %s already exists",
                                                                                  KnownExists::new);
    }

    record KnownNotFound(long id, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<KnownNotFound, Long> FACTORY = Causes.forOneValue("Known entry %d not found",
                                                                                  KnownNotFound::new);
    }

    /// A carry whose carried SHA has no latest verdict that is ACCEPT at the body's base.
    record CarryNoAccept(String repo, long pr, String carriedFrom, String message) implements StoreError, Cause.Terminal {
        public static final Fn3<CarryNoAccept, String, Long, String> FACTORY = Causes.forThreeValues("No ACCEPT to carry for %s#%d@%s",
                                                                                                     CarryNoAccept::new);
    }

    /// The PR is in the merge queue already, at `position` of `base` (a PR is queued at most once).
    record AlreadyQueued(String base, int position, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<AlreadyQueued, String, Integer> FACTORY = Causes.forTwoValues("Already queued at %s position %d",
                                                                                              AlreadyQueued::new);
    }

    /// The PR is not in the merge queue, or not at the base the edit named.
    record NotQueued(String repo, long pr, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<NotQueued, String, Long> FACTORY = Causes.forTwoValues("%s#%d is not queued",
                                                                                       NotQueued::new);
    }

    /// A queue move to a position outside 1..`size` of the PR's queue.
    record PositionOutOfRange(int to, int size, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<PositionOutOfRange, Integer, Integer> FACTORY = Causes.forTwoValues("Position %d is outside 1..%d",
                                                                                                    PositionOutOfRange::new);
    }

    /// The repo has this control-path glob already.
    record PathExists(String repo, String glob, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<PathExists, String, String> FACTORY = Causes.forTwoValues("%s has control path %s already",
                                                                                          PathExists::new);
    }

    /// The repo has no such control-path glob.
    record PathNotFound(String repo, String glob, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<PathNotFound, String, String> FACTORY = Causes.forTwoValues("%s has no control path %s",
                                                                                            PathNotFound::new);
    }

    /// No GO has this id.
    record GoNotFound(String id, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<GoNotFound, String> FACTORY = Causes.forOneValue("GO %s not found", GoNotFound::new);
    }

    /// The GO was consumed already (single use).
    record GoUsed(String id, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<GoUsed, String> FACTORY = Causes.forOneValue("GO %s was consumed already", GoUsed::new);
    }

    /// The GO was invalidated, for `reason`.
    record GoInvalidated(String id, String reason, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<GoInvalidated, String, String> FACTORY = Causes.forTwoValues("GO %s was invalidated: %s",
                                                                                             GoInvalidated::new);
    }

    /// The GO is older than 10 minutes on the database clock.
    record GoExpired(String id, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<GoExpired, String> FACTORY = Causes.forOneValue("GO %s expired", GoExpired::new);
    }

    /// A GO's creation under an epoch that is not temper's current one.
    record EpochChanged(String epoch, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<EpochChanged, String> FACTORY = Causes.forOneValue("Epoch %s is not the current epoch",
                                                                                   EpochChanged::new);
    }

    /// A GO's creation refused for `reason`: the latest verdict is another row (`verdict-changed`), or a fleet
    /// row after the gate's `fleetSeq` matches an invalidation rule (`checks-changed`, `base-moved`).
    record CreationInvalidated(String reason, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<CreationInvalidated, String> FACTORY = Causes.forOneValue("GO invalidated at creation: %s",
                                                                                          CreationInvalidated::new);
    }

    /// An outcome transition the GO lifecycle does not allow (`from` is `none` for a GO without an outcome).
    record BadTransition(String from, String to, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<BadTransition, String, String> FACTORY = Causes.forTwoValues("Outcome %s->%s is not allowed",
                                                                                             BadTransition::new);
    }

    record TaskNotFound(TaskId task, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<TaskNotFound, TaskId> FACTORY = Causes.forOneValue("Task %s not found",
                                                                                   TaskNotFound::new);
    }

    /// A task's `after` names a task that does not exist (m3-design §5.4; the API's 422 `after-unknown`).
    record AfterUnknown(TaskId task, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<AfterUnknown, TaskId> FACTORY = Causes.forOneValue("after-unknown: task %s does not exist",
                                                                                   AfterUnknown::new);
    }

    /// A `wait` names a job that is not linked to the task (m3-design §6.6; the API's 422).
    record JobNotLinked(JobId job, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<JobNotLinked, JobId> FACTORY = Causes.forOneValue("job-not-linked: job %s is not linked to this task",
                                                                                  JobNotLinked::new);
    }

    /// A ruling names an option the open request did not offer (m3-design §5.5; the API's 422).
    record OptionNotOffered(String option, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<OptionNotOffered, String> FACTORY = Causes.forOneValue("option-not-offered: %s",
                                                                                       OptionNotOffered::new);
    }

    /// A digest cursor above every `seq` this database has committed (`head`, the higher of the latest stored `seq`
    /// and the event floor): it came from another event log, or from this one before a restore. The API's 400.
    record CursorAhead(String cursor, long head, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<CursorAhead, String, Long> FACTORY = Causes.forTwoValues("Cursor %s is ahead of the event log (head %d)",
                                                                                         CursorAhead::new);
    }

    /// A digest cursor above the floor whose event is missing or is another event ([EventCursor]): it came from another
    /// event log, or from this one before a restore that has since committed new events. Read from there, the events
    /// the cursor's own log never had would be skipped silently, so the read is refused. The API's 409.
    record CursorForeign(String cursor, String message) implements StoreError, Cause.Terminal {
        public static final Fn1<CursorForeign, String> FACTORY = Causes.forOneValue("CURSOR_FOREIGN: cursor %s is not from this event log; restart without since",
                                                                                    CursorForeign::new);
    }

    /// A testcase row attributed to another unit than the one being completed.
    record ForeignTestcase(UnitName expected, UnitName actual, String message) implements StoreError, Cause.Terminal {
        public static final Fn2<ForeignTestcase, UnitName, UnitName> FACTORY = Causes.forTwoValues("Completing unit %s received a testcase of unit %s",
                                                                                                   ForeignTestcase::new);
    }
}
