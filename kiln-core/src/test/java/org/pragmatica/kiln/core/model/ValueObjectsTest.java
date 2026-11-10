package org.pragmatica.kiln.core.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.failure;
import static org.pragmatica.kiln.core.Fixtures.ok;

/// kiln-f1-design §1.1 value-object rules: success and failure for each factory.
class ValueObjectsTest {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";

    @Test
    void sha_acceptsFortyLowercaseHex_abbreviatesToSeven() {
        var sha = ok(Sha.sha(SHA));

        assertThat(sha.value()).isEqualTo(SHA);
        assertThat(sha.abbreviated()).isEqualTo("0cf5631");
        assertThat(sha).hasToString(SHA);
    }

    /// Nothing is normalised: upper case, surrounding space, 39 or 41 characters and non-hex are refused.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0CF563192A0CF563192A0CF563192A0CF563192A", " 0cf563192a0cf563192a0cf563192a0cf563192a",
                            "0cf563192a0cf563192a0cf563192a0cf563192", "0cf563192a0cf563192a0cf563192a0cf563192a0",
                            "gcf563192a0cf563192a0cf563192a0cf563192a", "   "})
    void sha_refuses(String raw) {
        assertThat(failure(Sha.sha(raw))).isInstanceOf(ModelError.InvalidValue.class);
    }

    @Test
    void prNumber_acceptsOneAndMore_refusesZeroAndNegative() {
        assertThat(ok(PrNumber.prNumber(1)).value()).isEqualTo(1);
        assertThat(ok(PrNumber.prNumber(1301))).hasToString("#1301");
        assertThat(failure(PrNumber.prNumber(0))).isInstanceOf(ModelError.InvalidValue.class);
        assertThat(failure(PrNumber.prNumber(-1))).isInstanceOf(ModelError.InvalidValue.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "release-1.0.0-rc5", "kiln-wp1", "fix/x.y", "a", "a-", "a@b", "x.lockx", "a/b.c/d",
                            "ré", "HEADS", "head", "a/HEAD", "HEAD-x"})
    void branchName_accepts(String raw) {
        assertThat(ok(BranchName.branchName(raw))).hasToString(raw);
    }

    /// `git check-ref-format`'s rules plus refspec- and option-active starts (verification F-11, F-12).
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-main", "--upload-pack=x", "a..b", "..", "a\tb", "a\nb", "a\0b", "a\u007fb", "a\u0080b",
                            "a\u0085b", "a\u009bb", "a\u2028b", "a\u2029b", "a\u2028..b", "main:refs/heads/evil", "+main",
                            "^main", "main~1", "@{-1}", "a@{1}", "@", "a b", "x.lock", "a/x.lock/b", "a?", "a*", "a[b",
                            "a\\b", ".a", "a/.b", "/a", "a/", "a//b", "a.", "a/b.", "HEAD"})
    void branchName_refuses(String raw) {
        assertThat(failure(BranchName.branchName(raw))).isInstanceOf(ModelError.InvalidValue.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bigboy", "cloudbb-3", "0", "a2345678901234567890123456789012", "mac-mini", "macs"})
    void hostName_accepts(String raw) {
        assertThat(ok(HostName.hostName(raw))).hasToString(raw);
    }

    /// `mac` is reserved for the ingest: a configured host can never claim the Mac's rows.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"mac", "Bigboy", "-bigboy", "big_boy", "big.boy", "a23456789012345678901234567890123", " bigboy",
                            "bigboy\u0085", "big\u0080boy", "bigboy\u2028", "bigboy\n"})
    void hostName_refuses(String raw) {
        assertThat(failure(HostName.hostName(raw))).isInstanceOf(ModelError.InvalidValue.class);
    }

    @Test
    void hostName_mac_isTheIngestConstant() {
        assertThat(HostName.MAC.value()).isEqualTo("mac");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/home/builder/work/co/kiln-1", "/w/a..b", "/w/..a", "/w/a..", "/w/.git", "/w/a b", "/w/...", "/w/é"})
    void checkoutPath_accepts(String raw) {
        assertThat(ok(CheckoutPath.checkoutPath(raw))).hasToString(raw);
    }

    /// Verification F-3 (a line terminator hiding `..` from a lookahead), F-11 (C1 controls) and F-14 (aliases).
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "relative/x", "~/work/co", "/..", "/../etc", "/w/../x", "/w/x/..", "/w/a\nb", "/w/\0", "/",
                            "/a\u2028/../etc", "/a\u0085/../etc", "/a\u2029/../b", "/a/../b", "/a\u0080", "/a\u009b",
                            "/a\u2028", "/a//b", "/a/./b", "/a/b/", "/.", "/a/.", "//a"})
    void checkoutPath_refuses(String raw) {
        assertThat(failure(CheckoutPath.checkoutPath(raw))).isInstanceOf(ModelError.InvalidValue.class);
    }

    /// A context is matched by equality (spec §4.4), so it is kept verbatim, spaces included.
    @ParameterizedTest
    @ValueSource(strings = {"build", "Core tests (ubuntu)", " x "})
    void checkContext_accepts(String raw) {
        assertThat(ok(CheckContext.checkContext(raw))).hasToString(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "build\n", "build\u0085", "build\u2028", "a\u0000b"})
    void checkContext_refuses(String raw) {
        assertThat(failure(CheckContext.checkContext(raw))).isInstanceOf(ModelError.InvalidValue.class);
    }

    @Test
    void appId_acceptsOneAndMore_refusesZeroAndNegative() {
        assertThat(ok(AppId.appId(15368))).hasToString("15368");
        assertThat(ok(AppId.appId(1)).value()).isEqualTo(1);
        assertThat(failure(AppId.appId(0))).isInstanceOf(ModelError.InvalidValue.class);
        assertThat(failure(AppId.appId(-15368))).isInstanceOf(ModelError.InvalidValue.class);
    }

    @Test
    void invalidValue_namesFieldAndRawText() {
        var cause = (ModelError.InvalidValue) failure(BranchName.branchName("-x"));

        assertThat(cause.field()).isEqualTo("branch");
        assertThat(cause.raw()).isEqualTo("-x");
        assertThat(cause.message()).isEqualTo("Invalid branch: '-x'");
    }

    @Test
    void observed_carriesValueAndTime() {
        var at = Instant.parse("2026-10-30T10:00:00Z");
        var observed = Observed.observed("x", at);

        assertThat(observed.value()).isEqualTo("x");
        assertThat(observed.observedAt()).isEqualTo(at);
    }
}
