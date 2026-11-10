package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The T1 harness command line (m1-design §8.1): pairs, defaults, and usage errors.
class T1ArgsTest {
    private static final Map<String, String> ENV = Map.of("HOME", "/home/builder");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void parse_pairsShasWithCheckouts_andAppliesDefaults() {
        var args = T1Args.t1Args(List.of("--controller", "http://127.0.0.1:7070/api/v1", "--out", "docs/trust-gate", "--planted", "fx.includes.RedProbeTest",
                                         "--sha", "ea364e8952", "--bb-co", "/w/co/a", "--sha", "0cf563192a", "--bb-co", "/w/co/b"),
                                 ENV, TODAY)
                         .expect("acceptance setup");

        assertThat(args.targets()).containsExactly(new T1Args.Target("ea364e8952", Path.of("/w/co/a")),
                                                   new T1Args.Target("0cf563192a", Path.of("/w/co/b")));
        assertThat(args.priority()).isEqualTo(5);
        assertThat(args.date()).isEqualTo(TODAY);
        assertThat(args.temper()).containsExactly("temper");
        assertThat(args.bbReport()).isEqualTo(Path.of("/home/builder/tools/bb-report"));
        assertThat(args.tokenFile()).isEqualTo(Path.of("/home/builder/.config/temper/token"));
        assertThat(args.queueHours()).isEqualTo(24);
        assertThat(args.runHours()).as("above the Gate unit's own 3 h timeout").isEqualTo(4);
        assertThat(args.rerun()).isFalse();
        assertThat(args.run()).isEqualTo(1);
        assertThat(args.planted()).isEqualTo(Option.some("fx.includes.RedProbeTest"));
    }

    @Test
    void parse_run_namesTheRunARerunJudges_andIsRefusedOnALiveRun() {
        var rerun = List.of("--rerun", "--controller", "http://h", "--out", "o", "--planted", "a.BTest", "--run", "3");

        assertThat(T1Args.t1Args(rerun, ENV, TODAY).expect("re-run of run 3").run()).isEqualTo(3);
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--planted", "a.BTest", "--run", "2"), ENV, TODAY)
                         .isFailure()).as("a live run never chooses its number").isTrue();
        assertThat(T1Args.t1Args(List.of("--rerun", "--controller", "http://h", "--out", "o", "--run", "0"), ENV, TODAY).isFailure())
            .as("runs count from 1").isTrue();
    }

    /// maintainer ruling "T1 red": the planted class is required on a live run and must be a class name. A re-run judges
    /// against the class the live run stored (F1), so there it may be left out; one given must still be a class name.
    @Test
    void parse_fails_withoutThePlantedClassOnALiveRun_orWithABadOne() {
        var live = List.of("--controller", "http://h", "--out", "o", "--sha", "abcdef1", "--bb-co", "c");

        assertThat(T1Args.t1Args(live, ENV, TODAY).isFailure()).as("--planted required on a live run").isTrue();
        assertThat(T1Args.t1Args(List.of("--rerun", "--controller", "http://h", "--out", "o"), ENV, TODAY).expect("re-run").planted())
            .as("--planted optional on a re-run: the stored class is judged").isEqualTo(Option.none());
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--planted", "not a class"), ENV, TODAY).isFailure())
            .isTrue();
        assertThat(T1Args.t1Args(List.of("--rerun", "--controller", "http://h", "--out", "o", "--planted", "not a class"), ENV, TODAY)
                         .isFailure()).as("a bad class on a re-run").isTrue();
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--planted", "a.b.CTest"), ENV, TODAY)
                         .expect("planted given").planted()).isEqualTo(Option.some("a.b.CTest"));
    }

    @Test
    void parse_rerunAndOverrides() {
        var args = T1Args.t1Args(List.of("--rerun", "--controller", "http://h/api/v1", "--out", "o", "--planted", "a.BTest", "--date", "2026-10-15",
                                         "--temper", "java -jar /x/cli.jar", "--prio", "7"),
                                 ENV, TODAY)
                         .expect("acceptance setup");

        assertThat(args.rerun()).isTrue();
        assertThat(args.date()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(args.temper()).containsExactly("java", "-jar", "/x/cli.jar");
        assertThat(args.priority()).isEqualTo(7);
    }

    /// Option A: the queue and the run each have their own bound.
    @Test
    void parse_queueAndRunHours_areTwoBounds() {
        var args = T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--planted", "a.BTest", "--queue-hours", "30", "--run-hours", "2"),
                                 ENV, TODAY)
                         .expect("both bounds");

        assertThat(args.queueHours()).isEqualTo(30);
        assertThat(args.runHours()).isEqualTo(2);
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--planted", "a.BTest", "--run-hours", "x"), ENV, TODAY)
                         .isFailure()).as("a bound that does not parse").isTrue();
    }

    /// A bound below 1 hour is a usage error (64): 0 would expire at once, a negative one is no `--timeout`.
    @Test
    void parse_refusesHoursBelowOne() {
        var base = List.of("--controller", "http://127.0.0.1:1", "--out", "o", "--planted", "a.BTest");

        for (var flag : List.of("--queue-hours", "--run-hours")) {
            for (var hours : List.of("0", "-1")) {
                var refused = T1Args.t1Args(with(base, flag, hours), ENV, TODAY);

                assertThat(refused.<String>fold(Cause::message, _ -> "accepted")).as(flag + " " + hours).contains(flag + " must be 1 or more");
                assertThat(T1Harness.run(with(base, flag, hours), ENV)).as(flag + " " + hours).isEqualTo(T1Harness.EXIT_USAGE);
            }
        }
        assertThat(T1Args.t1Args(with(base, "--queue-hours", "1", "--run-hours", "1"), ENV, TODAY).isSuccess()).as("1 is the least").isTrue();
    }

    /// Option A: `--wait-hours` covered queue and run alike, which failed a healthy head while RUNNING; it is refused
    /// with a message naming the two flags that replace it, never read as either of them.
    @Test
    void parse_refusesWaitHours_namingTheTwoFlagsThatReplaceIt() {
        var refused = T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--planted", "a.BTest", "--wait-hours", "6"), ENV, TODAY);

        assertThat(refused.<String>fold(cause -> cause instanceof TrustError.Usage ? cause.message() : "not a usage error: " + cause, _ -> "accepted"))
            .contains("--wait-hours").contains("--queue-hours").contains("--run-hours");
        assertThat(T1Harness.run(List.of("--controller", "http://127.0.0.1:1", "--out", "o", "--planted", "a.BTest", "--wait-hours", "6"), ENV))
            .as("refused as a usage error").isEqualTo(T1Harness.EXIT_USAGE);
    }

    @Test
    void parse_fails_onUnpairedOrBadInput() {
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--sha", "abcdef1"), ENV, TODAY).isFailure()).isTrue();
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--sha", "XYZ", "--bb-co", "c"), ENV, TODAY).isFailure()).isTrue();
        assertThat(T1Args.t1Args(List.of("--out", "o"), ENV, TODAY).isFailure()).as("--controller required").isTrue();
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "stray"), ENV, TODAY).isFailure()).isTrue();
        assertThat(T1Args.t1Args(List.of("--controller", "http://h", "--out", "o", "--prio", "x"), ENV, TODAY).isFailure()).isTrue();
    }

    /// F7: every usage error exits 64 as documented, although `Result.all` wraps a single failed flag in a composite.
    @Test
    void run_exits64_onEveryUsageError() {
        var base = List.of("--controller", "http://127.0.0.1:1", "--out", "o");

        assertThat(T1Harness.run(base, ENV)).as("no --planted on a live run").isEqualTo(T1Harness.EXIT_USAGE);
        assertThat(T1Harness.run(with(base, "--planted", "not a class"), ENV)).as("not a class name").isEqualTo(T1Harness.EXIT_USAGE);
        assertThat(T1Harness.run(with(base, "--planted", "a.BTest", "--run", "2"), ENV)).as("--run without --rerun")
                                                                                     .isEqualTo(T1Harness.EXIT_USAGE);
        assertThat(T1Harness.run(with(base, "--planted", "a.BTest", "--prio", "x"), ENV)).as("a number that does not parse")
                                                                                     .isEqualTo(T1Harness.EXIT_USAGE);
        assertThat(T1Harness.run(List.of("--out", "o", "--date", "never"), ENV)).as("several flags failed")
                                                                                 .isEqualTo(T1Harness.EXIT_USAGE);
    }

    private static List<String> with(List<String> base, String... more) {
        return Stream.concat(base.stream(), Arrays.stream(more)).toList();
    }
}
