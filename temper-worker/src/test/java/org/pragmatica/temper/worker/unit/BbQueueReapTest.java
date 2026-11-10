package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.WorkerConfig;

import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `bb-queue reap` (maintainer ruling A11, m1-design §14): the listing's executable, environment and timeout with `reap`;
/// every output line logged; an output outside the `reap` contract (an older bb-queue, which prints its listing and
/// exits 0) or a non-zero exit, and any failure, a warning, never a failure of the caller.
class BbQueueReapTest {
    private static final List<String> REAP = List.of("/home/test/tools/bb-queue", "reap");
    private static final String OLDER = "bb-queue has no reap subcommand (older bb-queue); ticket released at the next queue client";
    /// The real stdout of the archived 2026-09-30 bb-queue given `reap` (run locally on an empty queue): `reap` is
    /// read as the listing's `N`, `head -n reap` fails on stderr inside the FINISHED loop, and the script exits 0.
    private static final String ARCHIVED_LISTING = """
            RUNNING (reactors on host: ; cap 3 unless a ticket overrides; port group held by: )
            TICKET                       OWNER    PRIO AGE           CHECKOUT  ARGS

            WAITING (grant order: priority desc, then FIFO; a [port-group] ticket is skipped while the group is held; \
            a ticket its own cap blocks is passed by backfill until blocked 1200s, then the host drains for it)
            POS  TICKET                       OWNER    PRIO WAITED        CHECKOUT  ARGS

            FINISHED (most recent reap)
            STATE      RC     TICKET                       OWNER    WALL ENDED         CHECKOUT  ARGS
            """;

    private final FakeRunner runner = new FakeRunner();
    private final UnitRef ref = Fixtures.ref(42, "gate");
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BbQueueReap.class);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private List<String> lines(Level level) {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == level)
                          .map(ILoggingEvent::getFormattedMessage)
                          .toList();
    }

    @Test
    void reaped_runsTheListingCommand_withReap() {
        var listing = ok(WorkerConfig.bbQueue(Map.of("PATH", "/usr/bin", "USER", "builder"), Path.of("/home/test")));

        ok(BbQueueReap.bbQueueReap(runner, listing)
                      .reaped(ref)
                      .await());

        assertThat(runner.calls()).containsExactly(REAP);
        assertThat(runner.commands()
                         .getFirst()).extracting(Command::env, Command::timeout, Command::outputLimit)
                                     .containsExactly(listing.env(), listing.timeout(), listing.outputLimit());
    }

    @Test
    void reaped_logsEveryLine() {
        runner.on(REAP, 0, "reaped 1760-77: orphaned\nreaped 1760-78: orphaned\n", "");

        ok(Fixtures.reap(runner)
                   .reaped(ref)
                   .await());

        assertThat(lines(Level.INFO)).containsExactly("unit temper-42-gate: bb-queue reap: reaped 1760-77: orphaned",
                                                      "unit temper-42-gate: bb-queue reap: reaped 1760-78: orphaned");
        assertThat(lines(Level.WARN)).isEmpty();
    }

    @Test
    void reaped_nothingToMove_isNotAWarning() {
        runner.on(REAP, 0, "reap: nothing to move\n", "");

        ok(Fixtures.reap(runner)
                   .reaped(ref)
                   .await());

        assertThat(lines(Level.INFO)).containsExactly("unit temper-42-gate: bb-queue reap: reap: nothing to move");
        assertThat(lines(Level.WARN)).isEmpty();
    }

    /// The 2026-09-30 bb-queue exits 0 with its listing: the output, not the exit, shows it has no `reap`.
    @Test
    void reaped_theArchivedBbQueuesListing_isTheOlderBbQueueWarning() {
        runner.on(REAP, 0, ARCHIVED_LISTING, "head: illegal line count -- reap\n");

        ok(Fixtures.reap(runner)
                   .reaped(ref)
                   .await());

        assertThat(lines(Level.WARN)).singleElement()
                                     .asString()
                                     .contains(OLDER)
                                     .contains("rc 0");
    }

    @Test
    void reaped_aNonZeroExit_isTheOlderBbQueueWarning() {
        runner.on(REAP, 2, "", "usage: bb-queue [N]\n");

        ok(Fixtures.reap(runner)
                   .reaped(ref)
                   .await());

        assertThat(lines(Level.WARN)).singleElement()
                                     .asString()
                                     .contains(OLDER)
                                     .contains("rc 2");
    }

    @Test
    void reaped_noOutput_isTheOlderBbQueueWarning() {
        runner.on(REAP, 0, "", "");

        ok(Fixtures.reap(runner)
                   .reaped(ref)
                   .await());

        assertThat(lines(Level.WARN)).singleElement()
                                     .asString()
                                     .contains(OLDER);
    }

    @Test
    void reaped_aToolThatCannotRun_isAWarning_notAFailure() {
        runner.failing(REAP, Causes.cause("timed out"));

        ok(Fixtures.reap(runner)
                   .reaped(ref)
                   .await());

        assertThat(lines(Level.WARN)).singleElement()
                                     .asString()
                                     .contains("timed out");
    }
}
