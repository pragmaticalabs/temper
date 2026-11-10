package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// m4-design F7, §2.1, §12.2 `SshdBlackholeIT`: the real ssh client against an `sshd` that stops answering.
/// - blackholed (sshd's netns drops every packet to port 22, [Sshd#blackhole]: nothing ever answers): each ssh ends
///   within `ConnectTimeout=15`, though the link's `Hello` timeout is 60 s here; the host is LOST (`link-down`) after
///   `hostLost`, not before;
/// - stopped (nothing listens): the same LOST, `link-down`.
///
/// Probe (F7): drop `ConnectTimeout=15` from [SshCommand] → the blackholed row's first ssh lives until the 60 s
/// `Hello` timeout (red).
class SshdBlackholeIT {
    private static final Duration HOST_LOST = Duration.ofSeconds(3);
    /// The link's `Hello` timeout, long enough that only `ConnectTimeout` ends a hanging ssh within the bound.
    private static final LinkTiming SLOW_HELLO = LinkTiming.linkTiming(timeSpan(60).seconds(), Duration.ofSeconds(90),
                                                                       timeSpan(100).millis(), LinkHarness.FAST.request(),
                                                                       LinkHarness.FAST.prepare(), LinkHarness.FAST.start(),
                                                                       LinkHarness.FAST.deploy(), LinkHarness.FAST.backoff(),
                                                                       LinkHarness.FAST.stableUp(), LinkHarness.FAST.startRetry(),
                                                                       LinkHarness.FAST.startAttemptTimeout(),
                                                                       LinkHarness.FAST.unrecordedAfter());
    private Sshd sshd;
    private LinkHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        sshd = new Sshd();
        var k1 = sshd.dir.resolve("k1");
        Sshd.keygen(k1);
        sshd.pin(k1.resolveSibling("k1.pub"));
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.link.stop();
        }
        if (sshd != null) {
            sshd.close();
        }
    }

    /// The ssh bound first, with `hostLost` out of reach (a LOST host's session is closed, which would end the ssh
    /// early); then the LOST row on a fresh link.
    @Test
    void blackholed_eachSshWithinConnectTimeout_lostLinkDown() throws Exception {
        sshd.blackhole();
        harness = new LinkHarness(SLOW_HELLO, Duration.ofMinutes(10));
        harness.through = sshd.runner();
        harness.link.start();
        waitFor(() -> !sshd.spawned.isEmpty() && sshd.spawned.getFirst().exited != null, 40, "the first ssh ended");

        assertThat(Sshd.took(sshd.spawned.getFirst())).as("ConnectTimeout=15 bounds a blackholed connect")
                                                      .isLessThan(Duration.ofSeconds(25))
                                                      .isGreaterThanOrEqualTo(Duration.ofSeconds(14));
        assertThat(harness.link.lost().isEmpty()).isTrue();
        harness.link.stop();

        lostLinkDown(Duration.ZERO);
    }

    /// The observer is held up past `hostLost` between its sleep and its look (what a starved CI runner does to the
    /// test thread, PR #94 flake 2026-11-06): a LOST declared meanwhile is on time, not early.
    @Test
    void stopped_observerStalledPastHostLost_lostOnTime() throws Exception {
        sshd.container.stop();

        lostLinkDown(HOST_LOST);
    }

    @Test
    void stopped_lostLinkDown() throws Exception {
        sshd.container.stop();

        lostLinkDown(Duration.ZERO);
        assertThat(sshd.spawned).as("refused at once, the link retries").hasSizeGreaterThan(1);
    }

    private void lostLinkDown(Duration observerStall) throws Exception {
        harness = new LinkHarness(SLOW_HELLO, HOST_LOST);
        harness.through = sshd.runner();
        var start = Instant.now();
        harness.link.start();

        Thread.sleep(HOST_LOST.toMillis() / 2 + observerStall.toMillis());
        notLostBeforeHostLost(start);
        waitFor(() -> harness.link.lost().isPresent(), "LOST");
        assertThat(harness.link.lost().unwrap().reason()).isEqualTo("link-down");
        assertThat(Duration.between(start, harness.link.lost().unwrap().at())).isGreaterThanOrEqualTo(HOST_LOST);
    }

    /// Binds only while the observation itself is inside `hostLost` from the start: read first, then the clock, so a
    /// LOST seen is one declared before that instant. The link's clock starts at or after `start`, so it cannot
    /// declare earlier; an observer that ran late is not evidence of an early LOST (the `at` check below is).
    private void notLostBeforeHostLost(Instant start) {
        var lost = harness.link.lost();
        var observedAt = Instant.now();

        if (Duration.between(start, observedAt).compareTo(HOST_LOST) < 0) {
            assertThat(lost.isEmpty()).as("not LOST before hostLost").isTrue();
        }
    }
}
