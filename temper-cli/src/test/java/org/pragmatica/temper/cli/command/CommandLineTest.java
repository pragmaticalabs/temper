package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class CommandLineTest {
    private static CliCommand parsed(String line) {
        return parsedWords(line.split(" "));
    }

    private static CliCommand parsedWords(String... words) {
        return CommandLine.parse(words)
                          .fold(cause -> fail(cause.message()), command -> command);
    }

    private static Cause refused(String line) {
        return CommandLine.parse(line.isEmpty()
                                 ? new String[0]
                                 : line.split(" "))
                          .fold(cause -> cause, command -> fail("parsed: " + command));
    }

    @Test
    void submit_moduleTest_parsesEveryOption() {
        var command = parsed("submit module-test --pl a,b/c --test Foo* --it-test BarIT --ref main --prio -5 --keep --fresh --dedicated-cpu --unit-timeout PT90S --owner v2174 --json");

        assertThat(command).isEqualTo(new CliCommand.Submit(new SourceDto.Pushed(some("main"), none()),
                                                            new RecipeDto.ModuleTest(List.of("a", "b/c"),
                                                                                     some("Foo*"),
                                                                                     some("BarIT")),
                                                            some(-5),
                                                            true,
                                                            true,
                                                            some("PT90S"),
                                                            some("v2174"),
                                                            true, none(), true));
    }

    @Test
    void submit_mvn_takesArgumentsAfterDoubleDash() {
        var command = parsed("submit mvn --sha abc -- -pl core --also-make verify");

        assertThat(command).isEqualTo(new CliCommand.Submit(new SourceDto.Pushed(none(), some("abc")),
                                                            new RecipeDto.Mvn(List.of("-pl", "core", "--also-make", "verify")),
                                                            none(),
                                                            false,
                                                            false,
                                                            none(),
                                                            none(),
                                                            false, none(), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"submit mutate --ref main",
                            "submit mvn --bundle b.bundle -- verify",
                            "submit mvn --patch p.diff --base abc -- verify",
                            "evidence 1",
                            "known list --ledger",
                            "known list --ledger --all"})
    void laterForms_areNotInM0(String line) {
        assertThat(parsed(line)).isInstanceOf(CliCommand.NotInM0.class);
    }

    /// m2-design §1.5: `gate` runs since M2; a SHA or a 7-hex prefix, lowercased, `--wait` with an optional `--timeout`.
    @Test
    void gate_parsesTheShaWaitTimeoutAndJson() {
        assertThat(parsed("gate 0cf5631")).isEqualTo(new CliCommand.Gate(sha("0cf5631"), false, CommandLine.DEFAULT_WAIT, false));
        assertThat(parsed("gate 0CF5631 --json")).isEqualTo(new CliCommand.Gate(sha("0cf5631"), false, CommandLine.DEFAULT_WAIT, true));
        assertThat(parsed("gate " + "a".repeat(40) + " --wait --timeout 30m"))
            .isEqualTo(new CliCommand.Gate(sha("a".repeat(40)), true, Duration.ofMinutes(30), false));
        assertThat(parsed("gate 0cf5631 --wait")).isEqualTo(new CliCommand.Gate(sha("0cf5631"), true, CommandLine.DEFAULT_WAIT, false));
    }

    /// m2-design §5.5: `rerun <job> [--prio N] [--fresh] [--unit-timeout PT90M] [--owner O] [--json]`; m4-design §9.2
    /// `--dedicated-cpu`.
    @Test
    void rerun_parsesTheJobAndItsOptions() {
        assertThat(parsed("rerun 4711")).isEqualTo(new CliCommand.Rerun(4711, none(), false, none(), none(), false, false));
        assertThat(parsed("rerun 4711 --prio -5 --fresh --dedicated-cpu --unit-timeout PT90M --owner cto --json")).isEqualTo(new CliCommand.Rerun(4711,
                                                                                                                                  some(-5),
                                                                                                                                  true,
                                                                                                                                  some("PT90M"),
                                                                                                                                  some("cto"),
                                                                                                                                  true,
                                                                                                                                  true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rerun", "rerun 0", "rerun x", "rerun 1 2", "rerun 1 --keep", "rerun 1 --prio x", "rerun 1 --ref main"})
    void rerun_refusesWithUsage(String line) {
        assertThat(CliError.exitCodeOf(refused(line))).as(line).isEqualTo(ExitCode.USAGE);
    }

    /// m2-design §3.4: `diff <a> <b>` puts `a` on side A; `diff <job> --base <sha>` puts the job on side B, the SHA
    /// judged as `gate`'s (7 to 40 hex, lowercased).
    @Test
    void diff_parsesBothForms() {
        assertThat(parsed("diff 4690 4711")).isEqualTo(new CliCommand.Diff(new CliCommand.Diff.Against.Job(4690), 4711, false));
        assertThat(parsed("diff 4711 --base 0CF5631 --json")).isEqualTo(new CliCommand.Diff(new CliCommand.Diff.Against.Base(sha("0cf5631")),
                                                                                           4711,
                                                                                           true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"diff", "diff 4690", "diff 4690 4711 4712", "diff 4690 4711 --base 0cf5631", "diff 4711 --base 0cf563",
                            "diff 4711 --base", "diff x 4711", "diff 4690 -3", "diff 4690 4711 --wait"})
    void diff_refusesWithUsage(String line) {
        assertThat(CliError.exitCodeOf(refused(line))).as(line).isEqualTo(ExitCode.USAGE);
    }

    private static ShaPrefix sha(String raw) {
        return ShaPrefix.shaPrefix(raw).unwrap();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gate", "gate 0cf563", "gate xyz5631", "gate 0cf5631 0cf5632", "gate 0cf5631 --timeout 1h",
                            "gate 0cf5631 --wait --timeout soon", "gate 0cf5631 --keep", "gate 00000000000000000000000000000000000000000"})
    void gate_refusesWithUsage(String line) {
        assertThat(CliError.exitCodeOf(refused(line))).as(line).isEqualTo(ExitCode.USAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"",
                            "frobnicate",
                            "submit module-test --ref main",
                            "submit module-test --pl a --ref main --sha abc",
                            "submit module-test --pl a",
                            "submit module-test --pl a --ref main -- verify",
                            "submit mvn --ref main --pl a -- verify",
                            "submit nonsense --ref main",
                            "submit mvn --ref main --prio high -- verify",
                            "submit mvn --ref main --ref other -- verify",
                            "submit mvn --ref",
                            "wait",
                            "wait abc",
                            "wait 0",
                            "wait 41 --timeout soon",
                            "wait 41 --keep",
                            "result 41 42",
                            "watch --terminal",
                            "log 41 --tail 0",
                            "cancel 41 --bogus",
                            "submit gate --ref main --pl a",
                            "submit gate --ref main --test Foo",
                            "submit gate --ref main -- verify",
                            "submit gate",
                            "result 41 --all",
                            "refusal",
                            "refusal frob",
                            "refusal list extra",
                            "refusal list --json",
                            "refusal list --matcher m.json",
                            "refusal add --matcher m.json --reason why",
                            "refusal add R-9 --reason why",
                            "refusal add R-9 --matcher m.json",
                            "refusal add R-9 R-10 --matcher m.json --reason why",
                            "refusal add R-9 --matcher m.json --reason why --issue nine",
                            "refusal add R-9 --matcher m.json --reason why --all",
                            "refusal expire",
                            "refusal expire R-9 R-10",
                            "refusal expire R-9 --all",
                            "known",
                            "known frob",
                            "known list extra",
                            "known add --issue 1 --reason r --until u",
                            "known add a.B --reason r --until u",
                            "known add a.B --issue 1 --until u",
                            "known add a.B --issue 1 --reason r",
                            "known add a.B a.C --issue 1 --reason r --until u",
                            "known add a.B --issue one --reason r --until u",
                            "known add a.B --issue -5 --reason r --until u",
                            "known add a.B --issue 0 --reason r --until u",
                            "known add a.B --issue 1 --reason r --until u --all",
                            "known add a.B --issue 1 --reason r --until u --ledger",
                            "known expire",
                            "known expire 12 13",
                            "known expire twelve",
                            "known expire 0",
                            "known expire 12 --all"})
    void malformed_isUsageError(String line) {
        assertThat(CliError.exitCodeOf(refused(line))).isEqualTo(ExitCode.USAGE);
    }

    @Test
    void submit_gate_parsesToTheGateRecipe() {
        assertThat(parsed("submit gate --sha abc --fresh --prio 5")).isEqualTo(new CliCommand.Submit(new SourceDto.Pushed(none(),
                                                                                                                       some("abc")),
                                                                                                  new RecipeDto.Gate(),
                                                                                                  some(5),
                                                                                                  false,
                                                                                                  true,
                                                                                                  none(),
                                                                                                  none(),
                                                                                                  false, none(), false));
    }

    @Test
    void refusal_parsesListAddAndExpire() {
        assertThat(parsed("refusal list")).isEqualTo(new CliCommand.RefusalList(false));
        assertThat(parsed("refusal list --all")).isEqualTo(new CliCommand.RefusalList(true));
        assertThat(parsed("refusal expire R-9")).isEqualTo(new CliCommand.RefusalExpire("R-9"));
        assertThat(parsedWords("refusal", "add", "R-9", "--matcher", "m.json", "--reason", "flaky until #9", "--issue", "9", "--until", "#9 closed"))
                  .isEqualTo(new CliCommand.RefusalAdd("R-9", "m.json", some(9), "flaky until #9", some("#9 closed")));
        assertThat(parsed("refusal add R-9 --reason why --matcher m.json")).isEqualTo(new CliCommand.RefusalAdd("R-9",
                                                                                                                "m.json",
                                                                                                                none(),
                                                                                                                "why",
                                                                                                                none()));
    }

    /// m2-design §4.4: `known` is a group like `refusal`; `--until` is required, the owner optional here.
    @Test
    void known_parsesListAddAndExpire() {
        assertThat(parsed("known list")).isEqualTo(new CliCommand.KnownList(false, false));
        assertThat(parsed("known list --all")).isEqualTo(new CliCommand.KnownList(true, false));
        assertThat(parsed("known list --all --json")).isEqualTo(new CliCommand.KnownList(true, true));
        assertThat(parsed("known expire 12")).isEqualTo(new CliCommand.KnownExpire(12));
        assertThat(parsedWords("known", "add", "org.x.FooTest#one", "--issue", "1679", "--reason", "flaky until #1679", "--until",
                               "#1679 closes", "--owner", "cto"))
                  .isEqualTo(new CliCommand.KnownAdd("org.x.FooTest#one", 1679, "flaky until #1679", "#1679 closes", some("cto")));
        assertThat(parsed("known add a.B --until u --reason r --issue 7")).isEqualTo(new CliCommand.KnownAdd("a.B", 7, "r", "u", none()));
    }

    @Test
    void known_ledger_isTheLaterFormOfKnown() {
        assertThat(parsed("known list --ledger")).isEqualTo(new CliCommand.NotInM0("known"));
    }

    @Test
    void wait_defaultsToSixHours() {
        assertThat(parsed("wait 41")).isEqualTo(new CliCommand.Wait(41, Duration.ofHours(6), false));
        assertThat(parsed("wait 41 --timeout 90m --json")).isEqualTo(new CliCommand.Wait(41, Duration.ofMinutes(90), true));
    }

    @Test
    void jobCommands_parse() {
        assertThat(parsed("result 41 --json")).isEqualTo(new CliCommand.ShowResult(41, true));
        assertThat(parsed("cancel 41")).isEqualTo(new CliCommand.Cancel(41, false));
        assertThat(parsed("log 41 --unit build --tail 200")).isEqualTo(new CliCommand.Log(41, some("build"), some(200)));
        assertThat(parsed("watch --terminal --subscriber cto")).isEqualTo(new CliCommand.Watch("cto", true));
        assertThat(parsed("watch --subscriber cto")).isEqualTo(new CliCommand.Watch("cto", false));
    }

    /// m2-design §2.4: `subscribe` and `unsubscribe` are run now; the bare argument is classified (`Selector.classified`).
    @Test
    void selectorCommands_parse_withTheArgumentClassified() {
        assertThat(parsed("subscribe 4711 --subscriber cto")).isEqualTo(new CliCommand.Subscribe("cto", Selector.selector("job:4711").unwrap()));
        assertThat(parsed("subscribe ref:4711 --subscriber cto")).isEqualTo(new CliCommand.Subscribe("cto", Selector.selector("ref:4711").unwrap()));
        assertThat(parsed("unsubscribe main --subscriber cto")).isEqualTo(new CliCommand.Unsubscribe("cto", Selector.selector("ref:main").unwrap()));
    }

    @Test
    void jobsCommand_parses_withExactlyOneFilter() {
        assertThat(parsed("jobs --ref main")).isEqualTo(new CliCommand.Jobs(none(), some("main"), false));
        assertThat(parsed("jobs --sha abc --json")).isEqualTo(new CliCommand.Jobs(some("abc"), none(), true));
        assertThat(CliError.exitCodeOf(refused("jobs"))).isEqualTo(ExitCode.USAGE);
        assertThat(CliError.exitCodeOf(refused("jobs --ref main --sha abc"))).isEqualTo(ExitCode.USAGE);
        assertThat(CliError.exitCodeOf(refused("jobs main"))).isEqualTo(ExitCode.USAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"subscribe 0cf5631 --subscriber cto",
                            "subscribe 4711",
                            "subscribe --subscriber cto",
                            "subscribe 4711 4712 --subscriber cto",
                            "subscribe 4711 --subscriber cto --terminal",
                            "subscribe job:0 --subscriber cto",
                            "unsubscribe 0cf563192a --subscriber cto",
                            "unsubscribe 4711"})
    void selectorCommands_malformed_isUsageError(String line) {
        assertThat(CliError.exitCodeOf(refused(line))).isEqualTo(ExitCode.USAGE);
    }
}
