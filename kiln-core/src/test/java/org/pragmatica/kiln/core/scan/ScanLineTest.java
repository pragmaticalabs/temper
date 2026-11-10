package org.pragmatica.kiln.core.scan;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.ModelError;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.ScanError.BadField;
import org.pragmatica.kiln.core.scan.ScanError.NotJson;
import org.pragmatica.kiln.core.scan.ScanError.UnknownKind;
import org.pragmatica.kiln.core.scan.ScanLine.CheckoutLine;
import org.pragmatica.kiln.core.scan.ScanLine.NonGitLine;
import org.pragmatica.kiln.core.scan.ScanLine.StubLine;
import org.pragmatica.kiln.core.scan.ScanLine.Trailer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.failure;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The scan-line format of kiln-f1-design §3.2 as `ScanLine` documents it. Lenient towards unknown fields only: a known
/// field that is absent, null or mistyped fails the line, never reads as a default.
class ScanLineTest {
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    static final String CHECKOUT = """
        {"kind":"checkout","path":"/w/co/a","head":"%s","branch":"main","remote":"https://github.com/o/r.git",\
        "branches":[{"name":"main","tip":"%s"},{"name":"side","tip":"%s"}],"modified":1,"untracked":2,"stash":3,\
        "op":"rebase","activityAt":1761820000,"processes":[4242,77],"maven":true,"commonDir":"/w/co/a/.git",\
        "salvageRefs":5}""".formatted(A, A, B);
    private static final String MINIMAL = """
        {"kind":"checkout","path":"/w/co/a","branches":[],"modified":0,"untracked":0,"stash":0,\
        "activityAt":0,"processes":[],"maven":false,"commonDir":"/w/co/a/.git"}""";

    private static CheckoutFacts facts(String line) {
        return ((CheckoutLine) ok(ScanLine.decode(line))).facts();
    }

    private static String without(String line, String field) {
        return line.replaceFirst("\"" + field + "\":(\\[[^\\]]*\\]|\"[^\"]*\"|[^,}]*),?", "")
                   .replace(",}", "}");
    }

    /// `line` with `extra` fields appended to its outer object.
    private static String plus(String line, String extra) {
        return line.substring(0, line.length() - 1) + "," + extra + "}";
    }

    @Test
    void decode_readsEveryFactOfACheckoutLine() {
        var facts = facts(CHECKOUT);

        assertThat(facts.path()).isEqualTo(ok(CheckoutPath.checkoutPath("/w/co/a")));
        assertThat(facts.head()).isEqualTo(some(ok(Sha.sha(A))));
        assertThat(facts.branch()).isEqualTo(some(ok(BranchName.branchName("main"))));
        assertThat(facts.remoteUrl()).isEqualTo(some("https://github.com/o/r.git"));
        assertThat(facts.branches()).containsExactly(new BranchTip(ok(BranchName.branchName("main")), ok(Sha.sha(A))),
                                                     new BranchTip(ok(BranchName.branchName("side")), ok(Sha.sha(B))));
        assertThat(facts.modified()).isEqualTo(1);
        assertThat(facts.untracked()).isEqualTo(2);
        assertThat(facts.stash()).isEqualTo(3);
        assertThat(facts.op()).isEqualTo(some("rebase"));
        assertThat(facts.activityAt()).isEqualTo(Instant.ofEpochSecond(1761820000));
        assertThat(facts.processes()).containsExactly(4242L, 77L);
        assertThat(facts.maven()).isTrue();
        assertThat(facts.commonDir()).isEqualTo("/w/co/a/.git");
        assertThat(facts.salvageRefs()).isEqualTo(some(5));
    }

    /// Absent optionals read as none: an unborn branch (no head), a detached HEAD (no branch), no remote, no op.
    @Test
    void decode_readsAbsentOptionalsAsNone() {
        var facts = facts(MINIMAL);

        assertThat(facts.head()).isEqualTo(none());
        assertThat(facts.branch()).isEqualTo(none());
        assertThat(facts.remoteUrl()).isEqualTo(none());
        assertThat(facts.op()).isEqualTo(none());
        assertThat(facts.salvageRefs()).isEqualTo(none());
        assertThat(facts.activityAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void decode_readsNullOptionalsAsNone() {
        var facts = facts(plus(MINIMAL, "\"head\":null,\"branch\":null,\"remote\":null,\"op\":null,\"salvageRefs\":null"));

        assertThat(facts.head()).isEqualTo(none());
        assertThat(facts.branch()).isEqualTo(none());
        assertThat(facts.op()).isEqualTo(none());
        assertThat(facts.salvageRefs()).isEqualTo(none());
    }

    @Test
    void decode_ignoresUnknownFields() {
        assertThat(facts(plus(CHECKOUT, "\"future\":{\"x\":[1]}"))).isEqualTo(facts(CHECKOUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "branches", "modified", "untracked", "stash", "activityAt", "processes", "maven",
                            "commonDir"})
    void decode_fails_whenARequiredCheckoutFieldIsAbsent(String field) {
        var line = without(CHECKOUT, field);

        assertThat(line).doesNotContain("\"" + field + "\"");
        assertThat(failure(ScanLine.decode(line))).isEqualTo(BadField.FACTORY.apply(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "branches", "modified", "untracked", "stash", "activityAt", "processes", "maven",
                            "commonDir"})
    void decode_fails_whenARequiredCheckoutFieldIsNull(String field) {
        var line = plus(without(CHECKOUT, field), "\"" + field + "\":null");

        assertThat(failure(ScanLine.decode(line))).isEqualTo(BadField.FACTORY.apply(field));
    }

    /// Without this guard an absent field could read as 0 or empty: the optional fields are the only defaults.
    @Test
    void decode_readsTheOptionalsOnlyAsOptional() {
        for (var field : List.of("head", "branch", "remote", "op", "salvageRefs")) {
            assertThat(ScanLine.decode(without(CHECKOUT, field)).isSuccess()).as(field)
                                                                             .isTrue();
        }
    }

    @Test
    void decode_fails_onMistypedFields() {
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"modified\":1", "\"modified\":\"1\""))))
            .isEqualTo(BadField.FACTORY.apply("modified"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"modified\":1", "\"modified\":1.5"))))
            .isEqualTo(BadField.FACTORY.apply("modified"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"untracked\":2", "\"untracked\":-1"))))
            .isEqualTo(BadField.FACTORY.apply("untracked"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"stash\":3", "\"stash\":4294967296"))))
            .isEqualTo(BadField.FACTORY.apply("stash"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"maven\":true", "\"maven\":\"true\""))))
            .isEqualTo(BadField.FACTORY.apply("maven"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"commonDir\":\"/w/co/a/.git\"", "\"commonDir\":7"))))
            .isEqualTo(BadField.FACTORY.apply("commonDir"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"op\":\"rebase\"", "\"op\":true"))))
            .isEqualTo(BadField.FACTORY.apply("op"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"salvageRefs\":5", "\"salvageRefs\":-5"))))
            .isEqualTo(BadField.FACTORY.apply("salvageRefs"));
    }

    @Test
    void decode_fails_onMistypedLists() {
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("[4242,77]", "{\"a\":4242}"))))
            .isEqualTo(BadField.FACTORY.apply("processes"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("[4242,77]", "[4242,\"77\"]"))))
            .isEqualTo(BadField.FACTORY.apply("processes"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("[4242,77]", "[-1]"))))
            .isEqualTo(BadField.FACTORY.apply("processes"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("{\"name\":\"side\",", "{")))
                       .message()).contains("name");
    }

    @Test
    void decode_fails_onAnInvalidValueObject() {
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"head\":\"" + A, "\"head\":\"" + A.substring(1)))))
            .isInstanceOf(ModelError.InvalidValue.class);
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"path\":\"/w/co/a\"", "\"path\":\"w/co/a\""))))
            .isInstanceOf(ModelError.InvalidValue.class);
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"tip\":\"" + B, "\"tip\":\"" + B.toUpperCase()))))
            .isInstanceOf(ModelError.InvalidValue.class);
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"branch\":\"main\"", "\"branch\":\"-x\""))))
            .isInstanceOf(ModelError.InvalidValue.class);
    }

    @Test
    void decode_readsAnActivityAt_upToTheLastInstant() {
        var last = Instant.MAX.getEpochSecond();

        assertThat(facts(MINIMAL.replace("\"activityAt\":0", "\"activityAt\":" + last)).activityAt())
            .isEqualTo(Instant.ofEpochSecond(last));
        assertThat(failure(ScanLine.decode(MINIMAL.replace("\"activityAt\":0", "\"activityAt\":" + (last + 1)))))
            .isEqualTo(BadField.FACTORY.apply("activityAt"));
        assertThat(failure(ScanLine.decode(MINIMAL.replace("\"activityAt\":0", "\"activityAt\":-1"))))
            .isEqualTo(BadField.FACTORY.apply("activityAt"));
    }

    @Test
    void decode_readsAStubLine() {
        assertThat(ok(ScanLine.decode("{\"kind\":\"stub\",\"path\":\"/w/co/b\",\"activityAt\":5,\"commonDir\":\"/c\"}")))
            .isEqualTo(new StubLine(ok(CheckoutPath.checkoutPath("/w/co/b")), Instant.ofEpochSecond(5), "/c"));
        assertThat(failure(ScanLine.decode("{\"kind\":\"stub\",\"path\":\"/w/co/b\",\"commonDir\":\"/c\"}")))
            .isEqualTo(BadField.FACTORY.apply("activityAt"));
    }

    @Test
    void decode_readsANonGitLine() {
        assertThat(ok(ScanLine.decode("{\"kind\":\"nonGit\",\"root\":\"~/work/co/*\",\"path\":\"/w/co/c\"}")))
            .isEqualTo(new NonGitLine("~/work/co/*", ok(CheckoutPath.checkoutPath("/w/co/c"))));
        assertThat(failure(ScanLine.decode("{\"kind\":\"nonGit\",\"path\":\"/w/co/c\"}")))
            .isEqualTo(BadField.FACTORY.apply("root"));
    }

    @Test
    void decode_readsTheTrailer() {
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":3,\"nonGit\":1,\"nonGitBytes\":null}")))
            .isEqualTo(new Trailer(3, 1, none(), none()));
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":0,\"nonGit\":0,\"nonGitBytes\":5000000000}")))
            .isEqualTo(new Trailer(0, 0, some(5_000_000_000L), none()));
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":0,\"nonGit\":0}"))).isEqualTo(new Trailer(0, 0, none(), none()));
    }

    /// `home` (the scanned host's canonical home) is read when given, JSON-unescaped; absent or null, as from a script
    /// that predates it, it is none and the trailer still decodes.
    @Test
    void decode_readsTheTrailersHome_presentOrAbsent() {
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":1,\"nonGit\":0,\"nonGitBytes\":null,\"home\":\"/home/a\\\"b\"}")))
            .isEqualTo(new Trailer(1, 0, none(), some("/home/a\"b")));
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":1,\"nonGit\":0,\"home\":null}"))).isEqualTo(new Trailer(1, 0, none(), none()));
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":1,\"nonGit\":0}"))).isEqualTo(new Trailer(1, 0, none(), none()));
    }

    @Test
    void decode_fails_onABrokenTrailer() {
        assertThat(failure(ScanLine.decode("{\"end\":false,\"count\":3,\"nonGit\":1}")))
            .isEqualTo(BadField.FACTORY.apply("end"));
        assertThat(failure(ScanLine.decode("{\"end\":null,\"count\":3,\"nonGit\":1}")))
            .isEqualTo(BadField.FACTORY.apply("end"));
        assertThat(failure(ScanLine.decode("{\"end\":true,\"nonGit\":1}"))).isEqualTo(BadField.FACTORY.apply("count"));
        assertThat(failure(ScanLine.decode("{\"end\":true,\"count\":-1,\"nonGit\":1}")))
            .isEqualTo(BadField.FACTORY.apply("count"));
        assertThat(failure(ScanLine.decode("{\"end\":true,\"count\":1}"))).isEqualTo(BadField.FACTORY.apply("nonGit"));
        assertThat(failure(ScanLine.decode("{\"end\":true,\"count\":1,\"nonGit\":0,\"nonGitBytes\":\"9\"}")))
            .isEqualTo(BadField.FACTORY.apply("nonGitBytes"));
        assertThat(failure(ScanLine.decode("{\"end\":true,\"count\":1,\"nonGit\":0,\"home\":5}"))).isEqualTo(BadField.FACTORY.apply("home"));
    }

    @Test
    void decode_fails_onAnUnknownOrMissingKind() {
        assertThat(failure(ScanLine.decode("{\"kind\":\"worktree\",\"path\":\"/w\"}")))
            .isEqualTo(UnknownKind.FACTORY.apply("worktree"));
        assertThat(failure(ScanLine.decode("{\"path\":\"/w\"}"))).isEqualTo(BadField.FACTORY.apply("kind"));
        assertThat(failure(ScanLine.decode("[1,2]"))).isEqualTo(BadField.FACTORY.apply("kind"));
    }

    /// A key given twice fails the line, in a trailer and in a checkout line: last-wins would let a second `count`
    /// overrule the first. The same lines without the repeat decode.
    @Test
    void decode_fails_onADuplicateKey() {
        assertThat(failure(ScanLine.decode("{\"end\":true,\"count\":5,\"count\":0,\"nonGit\":0}")))
            .isInstanceOf(NotJson.class);
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":0,\"nonGit\":0}"))).isEqualTo(new Trailer(0, 0, none(), none()));
        assertThat(failure(ScanLine.decode(plus(CHECKOUT, "\"path\":\"/w/co/b\"")))).isInstanceOf(NotJson.class);
        assertThat(failure(ScanLine.decode(plus(CHECKOUT, "\"x\":1,\"x\":2")))).isInstanceOf(NotJson.class);
        assertThat(ok(ScanLine.decode(plus(CHECKOUT, "\"x\":1,\"y\":2")))).isInstanceOf(CheckoutLine.class);
    }

    /// Counts and sizes are integral JSON numbers only: `3.0`, `3e0` and `3.5` are refused, though the first two hold
    /// an integral value.
    @ParameterizedTest
    @ValueSource(strings = {"3.0", "3e0", "3.5"})
    void decode_fails_onANonIntegralNumber(String number) {
        assertThat(failure(ScanLine.decode("{\"end\":true,\"count\":" + number + ",\"nonGit\":0}")))
            .isEqualTo(BadField.FACTORY.apply("count"));
        assertThat(failure(ScanLine.decode("{\"end\":true,\"count\":0,\"nonGit\":0,\"nonGitBytes\":" + number + "}")))
            .isEqualTo(BadField.FACTORY.apply("nonGitBytes"));
        assertThat(failure(ScanLine.decode(CHECKOUT.replace("\"processes\":[4242,77]", "\"processes\":[" + number + "]"))))
            .isEqualTo(BadField.FACTORY.apply("processes"));
    }

    @Test
    void decode_readsAnIntegralNumber_asTheNonIntegralTestsControl() {
        assertThat(ok(ScanLine.decode("{\"end\":true,\"count\":3,\"nonGit\":0,\"nonGitBytes\":3}")))
            .isEqualTo(new Trailer(3, 0, some(3L), none()));
        assertThat(facts(CHECKOUT.replace("\"processes\":[4242,77]", "\"processes\":[3]")).processes())
            .containsExactly(3L);
    }

    @Test
    void decode_fails_onNonJson() {
        assertThat(failure(ScanLine.decode("{\"kind\":\"stub\""))).isInstanceOf(NotJson.class);
        assertThat(failure(ScanLine.decode("ssh: connect to host bigboy port 22: Connection refused")))
            .isInstanceOf(NotJson.class);
    }
}
