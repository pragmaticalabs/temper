package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The shape of `ops/bb/bb-queue 0` that the temper worker parses (temper-worker `BbQueue.java`): the RUNNING header keeps the
/// legacy label `port group held by:`, each section header is followed directly by its column line (a gate line between them made
/// the worker report no counts on every host with the 2026-11-11 tools). The worker side is pinned against verbatim output in
/// temper-worker `BbQueueTest`; this test pins the tool, which is where the worker's input comes from.
///
/// Probe: print `group tickets running:` in the RUNNING header, or move the LOAD GATE line under the WAITING header ->
/// [#theListing_keepsTheWorkersHeaderAndColumnShape] red.
class BbQueueListingIT {
    @TempDir
    Path dir;

    @Test
    void theListing_keepsTheWorkersHeaderAndColumnShape() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-queue reads /proc and uses pgrep, flock");
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_TEST_QUEUE_ROOT", dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES", Files.writeString(dir.resolve("prio"), "").toString());
        env.put("BB_TEST_PORT_MODULES", Files.writeString(dir.resolve("pgm"), "").toString());
        env.put("BB_TEST_PG_SLOTS_FILE", Files.writeString(dir.resolve("slots"), "3\n").toString());
        env.put("BB_TEST_LOADAVG", Files.writeString(dir.resolve("loadavg"), "0.10 0.10 0.10 1/100 1\n").toString());
        env.put("BB_TEST_TICKET_LOG", dir.resolve("log.tsv").toString());
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-queue").toString(), "0"), env, dir, null);

        assertThat(run.rc()).as(run.stderr()).isZero();
        var lines = run.stdout().lines().toList();
        var running = indexStartingWith(lines, "RUNNING (");
        var waiting = indexStartingWith(lines, "WAITING (");

        assertThat(lines.get(running)).contains("port group held by:");
        assertThat(lines.get(running + 1)).startsWith("TICKET ");
        assertThat(lines.get(waiting + 1)).startsWith("POS ");
    }

    private static int indexStartingWith(List<String> lines, String prefix) throws IOException {
        for (var i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new IOException("no line starts with " + prefix);
    }
}
