package org.pragmatica.temper.merge.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.merge.engine.Job;
import org.pragmatica.temper.merge.engine.JobState;

import static org.assertj.core.api.Assertions.assertThat;


class StoreTest {
    private static final Instant AT = Instant.parse("2026-11-08T12:00:00Z");
    private static final String SHA = "584bc4e76775a13009d1bb870b81f7b6e093e9e2";

    @TempDir
    Path dir;

    private Job job() {
        return Job.submitted("mr-1-2849-abcd", "pragmaticalabs/pragmatica", 2849, SHA, true, AT);
    }

    @Test
    void aSavedJob_readsBackEqual_andATornTmpFileIsIgnored() throws Exception {
        var files = JobFiles.jobFiles(dir);
        var merged = job().merged("9f1c", List.of("#101"), AT.plusSeconds(5));
        assertThat(files.create(job())
                        .isSuccess()).isTrue();
        assertThat(files.save(merged)
                        .isSuccess()).isTrue();
        Files.writeString(dir.resolve("mr-2-1-ffff.json.tmp"), "{torn");
        assertThat(files.load("mr-1-2849-abcd")
                        .unwrap()
                        .unwrap()).isEqualTo(merged);
        assertThat(files.all()
                        .unwrap()).containsExactly(merged);
    }

    @Test
    void createRefusesAnExistingId() {
        var files = JobFiles.jobFiles(dir);
        assertThat(files.create(job())
                        .isSuccess()).isTrue();
        assertThat(files.create(job())
                        .fold(Cause::message, _ -> "created")).startsWith("cannot create job mr-1-2849-abcd: java.nio.file.FileAlreadyExistsException");
    }

    @Test
    void anIdThatIsNotAFileName_isRefused() {
        assertThat(JobFiles.jobFiles(dir)
                           .load("../etc/passwd")
                           .isFailure()).isTrue();
    }

    @Test
    void aStateChange_isOneLineWithTheRequiredFields_andAnObservationAloneWritesNone() throws Exception {
        var log = dir.resolve("events.log");
        var files = JobFiles.jobFiles(dir.resolve("jobs"));
        var ledger = FileLedger.fileLedger(files, EventsLog.eventsLog(log), () -> AT);
        var waiting = job().moved(JobState.WAITING_CHECKS, "head verified;\nwaiting", AT);
        ledger.record(job(), waiting)
              .await();
        ledger.record(waiting, waiting.observed("required checks pending: forge-tests"))
              .await();
        var removed = waiting.dropped("FAILED_CHECKS", AT)
                             .reported("4242 failure https://x", List.of("g3:failure"), List.of("A.b", "C.d"), AT);
        ledger.record(waiting, removed)
              .await();
        assertThat(Files.readAllLines(log)).containsExactly(
            "2026-11-08T12:00:00Z\tjob=mr-1-2849-abcd\tpr=2849\tsha=" + SHA + "\tstate=WAITING_CHECKS\treason=head verified; waiting",
            "2026-11-08T12:00:00Z\tjob=mr-1-2849-abcd\tpr=2849\tsha=" + SHA
            + "\tstate=REMOVED\treason=removed from the queue: FAILED_CHECKS\trun=4242 failure https://x\tfailedJobs=g3:failure\tfailedTests=A.b,C.d");
        assertThat(files.load("mr-1-2849-abcd")
                        .unwrap()
                        .unwrap()).isEqualTo(removed);
    }

    @Test
    void aMergedLine_carriesTheMergeShaAndTheClosedIssues() {
        var line = EventsLog.line(job().merged("9f1c", List.of("#101", "#99"), AT), AT);
        assertThat(line).endsWith("\tstate=MERGED\treason=merged 9f1c\tmerge=9f1c\tclosed=#101,#99");
    }

    @Test
    void anUnwritableLogOrAnUnreadableJob_failsNamingThePath() throws Exception {
        var log = dir.resolve("events.log");
        Files.createDirectories(log);
        assertThat(EventsLog.eventsLog(log)
                            .append(job(), AT)
                            .fold(Cause::message, _ -> "appended")).startsWith("cannot append to " + log + ": ");
        Files.writeString(dir.resolve("mr-9-9-0000.json"), "{not json");
        assertThat(JobFiles.jobFiles(dir)
                           .all()
                           .isFailure()).isTrue();
        assertThat(JobFiles.jobFiles(dir.resolve("mr-9-9-0000.json"))
                           .all()
                           .fold(Cause::message, _ -> "listed")).startsWith("cannot create " + dir.resolve("mr-9-9-0000.json"));
    }
}
