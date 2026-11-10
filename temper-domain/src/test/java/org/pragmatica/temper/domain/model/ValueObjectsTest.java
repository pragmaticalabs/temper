package org.pragmatica.temper.domain.model;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.DomainError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m0-design §3.1 value-object rules: success and failure for each.
class ValueObjectsTest {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";

    @Test
    void jobId_acceptsPositive_refusesZeroAndNegative() {
        assertThat(ok(JobId.jobId(4711)).value()).isEqualTo(4711);
        assertThat(failure(JobId.jobId(0))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(JobId.jobId(-1))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "ember-1", "a", "0abc", "a2345678901234567890123456789012"})
    void unitName_accepts(String raw) {
        assertThat(ok(UnitName.unitName(raw)).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Main", "-main", "ma in", "a23456789012345678901234567890123", "x_y"})
    void unitName_refuses(String raw) {
        assertThat(failure(UnitName.unitName(raw))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void unitRef_namesContainerAndNetwork() {
        var ref = UnitRef.unitRef(ok(JobId.jobId(4711)), UnitName.MAIN);

        assertThat(ref.containerName()).isEqualTo("temper-4711-main");
        assertThat(ref.networkName()).isEqualTo("temper-4711-main");
    }

    @Test
    void sha_lowercasesAndTrims_refusesShortOrNonHex() {
        assertThat(ok(Sha.sha(" " + SHA.toUpperCase() + " ")).value()).isEqualTo(SHA);
        assertThat(ok(Sha.sha(SHA)).shortForm()).isEqualTo("0cf5631");
        assertThat(failure(Sha.sha("0cf5631"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(Sha.sha(SHA.substring(1) + "g"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(Sha.sha(null))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "rewrite/deployment-fsm", "release-1.0.0-rc4", "v1.2_3"})
    void ref_accepts(String raw) {
        assertThat(ok(Ref.ref(raw)).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-main", "--upload-pack=x", "a..b", "a//b", "a/", "main.lock", "a b", "a;b", "a$b"})
    void ref_refuses(String raw) {
        assertThat(failure(Ref.ref(raw))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void ref_refusesOverlong() {
        assertThat(failure(Ref.ref("a".repeat(201)))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(ok(Ref.ref("a".repeat(200))).value()).hasSize(200);
    }

    @Test
    void ownerTag_acceptsBbOwnerAlphabet_refusesOthers() {
        assertThat(ok(OwnerTag.ownerTag("v2174")).value()).isEqualTo("v2174");
        assertThat(failure(OwnerTag.ownerTag("v-2174"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(OwnerTag.ownerTag("a".repeat(65)))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(OwnerTag.ownerTag(""))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void instanceId_isALabelSafeValue() {
        assertThat(ok(InstanceId.instanceId("acceptance-1.x_y")).value()).isEqualTo("acceptance-1.x_y");
        assertThat(InstanceId.DEFAULT.value()).isEqualTo("temper");
        assertThat(failure(InstanceId.instanceId("a=b"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(InstanceId.instanceId("a".repeat(65)))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(InstanceId.instanceId(""))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void priority_defaultsToMinus20_andIsBounded() {
        assertThat(ok(Priority.priority(Option.none())).value()).isEqualTo(-20);
        assertThat(ok(Priority.priority(Option.some(1000))).value()).isEqualTo(1000);
        assertThat(failure(Priority.priority(Option.some(1001)))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(Priority.priority(Option.some(-1001)))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "aether/ember", ":forge-tests", "aether/forge/forge-tests"})
    void modulePath_accepts(String raw) {
        assertThat(ok(ModulePath.modulePath(raw)).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-am", "../outside", "a/../b", "a b", "a,b"})
    void modulePath_refuses(String raw) {
        assertThat(failure(ModulePath.modulePath(raw))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Foo*,Bar", "!StreamPublishReshuffleTest", "**/Foo*", "Foo#bar", "Outer$Inner", "?ooTest"})
    void testSelection_accepts(String raw) {
        assertThat(ok(TestSelection.testSelection(raw)).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-Dfoo", "Foo Bar", "%regex[.*]", "a;b"})
    void testSelection_refuses(String raw) {
        assertThat(failure(TestSelection.testSelection(raw))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void mvnArg_acceptsAnyContent_refusesBlankOverlongAndNul() {
        assertThat(ok(MvnArg.mvnArg("-Dmaven.repo.local=/x")).value()).isEqualTo("-Dmaven.repo.local=/x");
        assertThat(ok(MvnArg.mvnArg("a".repeat(4096))).value()).hasSize(4096);
        assertThat(failure(MvnArg.mvnArg("a".repeat(4097)))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(MvnArg.mvnArg(" "))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(MvnArg.mvnArg("a\u0000b"))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void unitTimeout_isIsoAndBounded_andCarriesParserCause() {
        assertThat(ok(UnitTimeout.unitTimeout("PT60S")).value()).isEqualTo(Duration.ofMinutes(1));
        assertThat(ok(UnitTimeout.unitTimeout("PT6H")).value()).isEqualTo(Duration.ofHours(6));
        assertThat(failure(UnitTimeout.unitTimeout("PT59S"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(UnitTimeout.unitTimeout("PT6H1S"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(UnitTimeout.unitTimeout("90m"))).isInstanceOfSatisfying(DomainError.Unparseable.class,
                                                                                    unparseable -> assertThat(unparseable.source()
                                                                                                                         .isPresent()).isTrue());
    }

    @Test
    void markerEpoch_readsTrimmedText_refusesNonDigitsZeroAndOverflow() {
        assertThat(ok(MarkerEpoch.markerEpoch(" 1760000000000000000\n")).nanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(failure(MarkerEpoch.markerEpoch("0"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(MarkerEpoch.markerEpoch("-5"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(MarkerEpoch.markerEpoch("12345678901234567890"))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(MarkerEpoch.markerEpoch("9999999999999999999"))).isInstanceOf(DomainError.Unparseable.class);
        assertThat(failure(MarkerEpoch.markerEpoch(""))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void memoryMb_isBounded() {
        assertThat(ok(MemoryMb.memoryMb(512)).value()).isEqualTo(512);
        assertThat(failure(MemoryMb.memoryMb(511))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(MemoryMb.memoryMb(65537))).isInstanceOf(DomainError.InvalidValue.class);
    }

    @Test
    void sourceRequest_parsesRefAndSha() {
        assertThat(ok(SourceRequest.byRef("main"))).isEqualTo(new SourceRequest.ByRef(ok(Ref.ref("main"))));
        assertThat(ok(SourceRequest.bySha(SHA))).isEqualTo(new SourceRequest.BySha(ok(Sha.sha(SHA))));
        assertThat(PushedSource.pushedSource(Option.none(), ok(Sha.sha(SHA))).identity()).isEqualTo(SHA);
    }

    @Test
    void recipe_parsesAndRefusesEmptyLists() {
        assertThat(ok(Recipe.mvn(List.of("-pl", "core", "verify"))).kind()).isEqualTo("mvn");
        assertThat(ok(Recipe.moduleTest(List.of("aether/ember"), Option.some("Foo*"), Option.none())).kind()).isEqualTo("module-test");
        assertThat(failure(Recipe.mvn(List.of()))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(Recipe.mvn(null))).isInstanceOf(DomainError.InvalidValue.class);
        assertThat(failure(Recipe.moduleTest(List.of(), Option.none(), Option.none())).message()).contains("Invalid pl");
        assertThat(failure(Recipe.moduleTest(List.of("core"), Option.some("bad selection"), Option.none())).message()).contains("Invalid test selection");
        assertThat(Recipe.unsupported("gate")
                         .kind()).isEqualTo("gate");
    }
}
