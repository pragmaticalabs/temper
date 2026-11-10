package org.pragmatica.temper.cli.transport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// D-6: the comparison table, and the decorator's once-per-invocation warning and refusal before the body is used.
class VersionCheckTest {
    private static final String JUDGED = "0.1.0-SNAPSHOT+111111111111";
    private static final String OTHER = "0.1.0-SNAPSHOT+222222222222";
    private static final String UNJUDGED = "0.1.0-SNAPSHOT+unjudged";

    @Test
    void verdict_equal_passesWithoutWarning() {
        assertThat(VersionCheck.verdict(JUDGED, some(JUDGED))
                               .unwrap()).isEqualTo(none());
        assertThat(VersionCheck.verdict(UNJUDGED, some(UNJUDGED))
                               .unwrap()).isEqualTo(none());
    }

    @Test
    void verdict_judgedMismatch_isVersionSkew_namingBoth() {
        var verdict = VersionCheck.verdict(JUDGED, some(OTHER));

        assertThat(verdict.isFailure()).isTrue();
        verdict.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(CliError.VersionSkew.class, skew -> {
                        assertThat(skew.cliVersion()).isEqualTo(JUDGED);
                        assertThat(skew.controllerVersion()).isEqualTo(OTHER);
                        assertThat(skew.exitCode()
                                       .code()).isEqualTo(13);
                    }));
    }

    @Test
    void verdict_dirtyIsJudged() {
        assertThat(VersionCheck.verdict(JUDGED + "-dirty", some(OTHER))
                               .isFailure()).isTrue();
        assertThat(VersionCheck.verdict(JUDGED, some(OTHER + "-dirty"))
                               .isFailure()).isTrue();
    }

    @Test
    void verdict_anUnjudgedSideOrNoHeader_warns() {
        assertThat(VersionCheck.verdict(JUDGED, some(UNJUDGED))
                               .unwrap()
                               .isPresent()).isTrue();
        assertThat(VersionCheck.verdict(UNJUDGED, some(OTHER))
                               .unwrap()
                               .isPresent()).isTrue();
    }

    /// G1/G2, transition rules: no header, or a version without `+`, warns with exactly the ruled text.
    @Test
    void verdict_absentOrPlusLessControllerVersion_warnsPredates() {
        assertThat(VersionCheck.verdict(JUDGED, none())
                               .unwrap()).isEqualTo(some("controller predates build identity; skew not checked"));
        assertThat(VersionCheck.verdict(JUDGED, some("0.1.0-SNAPSHOT"))
                               .unwrap()).isEqualTo(some("controller predates build identity; skew not checked"));
    }

    /// M1: every call names the CLI's version (the header the controller's write gate reads).
    @Test
    void versionChecked_namesTheCliVersionOnEveryCall() {
        var sent = new CopyOnWriteArrayList<HttpCall>();
        HttpTransport recording = call -> {
            sent.add(call);
            return Promise.success(HttpReply.httpReply(200, "[]", some(JUDGED)));
        };
        var console = Console.console(new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        var checked = VersionCheck.versionChecked(recording, Result.success(JUDGED), console);

        checked.exchange(HttpCall.httpCall("GET", "/refusals", Option.none()))
               .await();
        checked.exchange(HttpCall.httpCall("POST", "/refusals/R-9/expire", Option.none())
                                 .withLease("lease"))
               .await();

        assertThat(sent).extracting(HttpCall::version)
                        .containsExactly(some(JUDGED), some(JUDGED));
        assertThat(sent.getLast()
                       .lease()).isEqualTo(some("lease"));
    }

    /// M1: a 412 is the controller's refusal of a skewed write: VersionSkew (exit 13), whatever the header says.
    @Test
    void versionChecked_412_isVersionSkew() {
        var console = Console.console(new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        HttpTransport refusing = _ -> Promise.success(HttpReply.httpReply(412, "{}", some(OTHER)));
        HttpTransport refusingWithoutHeader = _ -> Promise.success(HttpReply.httpReply(412, "{}", none()));
        var call = HttpCall.httpCall("POST", "/jobs", Option.none());

        VersionCheck.versionChecked(refusing, Result.success(JUDGED), console)
                    .exchange(call)
                    .await()
                    .onSuccess(reply -> Assertions.fail("412 passed: " + reply))
                    .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(CliError.VersionSkew.class,
                                                                                 skew -> assertThat(skew.controllerVersion()).isEqualTo(OTHER)));
        VersionCheck.versionChecked(refusingWithoutHeader, Result.success(JUDGED), console)
                    .exchange(call)
                    .await()
                    .onSuccess(reply -> Assertions.fail("412 passed: " + reply))
                    .onFailure(cause -> assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.VERSION_SKEW));
    }

    @Test
    void versionChecked_warnsOncePerInvocation() {
        var err = new ByteArrayOutputStream();
        var console = Console.console(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));
        HttpTransport unjudged = _ -> Promise.success(HttpReply.httpReply(200, "[]", some(UNJUDGED)));
        var checked = VersionCheck.versionChecked(unjudged, Result.success(JUDGED), console);
        var call = HttpCall.httpCall("GET", "/refusals", Option.none());

        assertThat(checked.exchange(call)
                          .await()
                          .isSuccess()).isTrue();
        assertThat(checked.exchange(call)
                          .await()
                          .isSuccess()).isTrue();
        assertThat(err.toString(StandardCharsets.UTF_8)
                      .lines()
                      .count()).isEqualTo(1);
    }

    /// A CLI whose own version cannot be read sends nothing.
    @Test
    void versionChecked_unreadableOwnVersion_failsBeforeSending() {
        var sent = new boolean[1];
        HttpTransport recording = _ -> {
            sent[0] = true;
            return Promise.success(HttpReply.httpReply(200, "[]", some(JUDGED)));
        };
        var console = Console.console(new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        var checked = VersionCheck.versionChecked(recording,
                                                  CliError.NoVersion.FACTORY.apply(Causes.cause("missing")).result(),
                                                  console);

        assertThat(checked.exchange(HttpCall.httpCall("GET", "/refusals", Option.none()))
                          .await()
                          .isFailure()).isTrue();
        assertThat(sent[0]).isFalse();
        assertThat(CliError.exitCodeOf(CliError.NoVersion.FACTORY.apply(Causes.cause("missing")))).isEqualTo(ExitCode.ERROR);
    }
}
