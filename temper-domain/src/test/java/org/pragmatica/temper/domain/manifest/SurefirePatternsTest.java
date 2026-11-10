package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.DEFAULT_EXCLUDES;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.FAILSAFE_DEFAULT_INCLUDES;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.SUREFIRE_DEFAULT_INCLUDES;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.hasMethodPatterns;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.isClassMatch;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.isClassSelected;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.selectedMethods;
import static org.pragmatica.temper.domain.manifest.SurefirePatterns.isSelected;

/// The surefire/failsafe selection alphabet (spec §5.2, m1-design §2.4 rule 2).
class SurefirePatternsTest {
    private static final Set<String> METHODS = Set.of("fastOne", "fastTwo", "slow");

    @Test
    void isClassMatch_bySimpleName_atAnyDepth() {
        assertThat(isClassMatch("FooTest", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("FooTest", "FooTest")).isTrue();
        assertThat(isClassMatch("FooTest", "org/acme/BarFooTest")).isFalse();
        assertThat(isClassMatch("FooTest.java", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("FooTest.class", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("Foo?est", "org/acme/FooTest")).isTrue();
    }

    @Test
    void isClassMatch_byFullyQualifiedName() {
        assertThat(isClassMatch("org.acme.FooTest", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("org.acme.FooTest", "org/other/FooTest")).isFalse();
    }

    @Test
    void isClassMatch_starNeverCrossesADirectory_doubleStarDoes() {
        assertThat(isClassMatch("org/acme/*Test", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("org/acme/*Test", "org/acme/deep/FooTest")).isFalse();
        assertThat(isClassMatch("org/*", "org/acme/FooTest")).isFalse();
        assertThat(isClassMatch("org/acme/**/*Test", "org/acme/deep/er/FooTest")).isTrue();
        assertThat(isClassMatch("org/acme/**/*Test", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("**/*Test.java", "FooTest")).isTrue();
        assertThat(isClassMatch("org/**", "org/acme/deep/FooTest")).isTrue();
    }

    @Test
    void isClassMatch_doubleStarInsideASegment_staysInThatSegment_asDoesQuestionMark() {
        assertThat(isClassMatch("org/**Test", "org/FooTest")).isTrue();
        assertThat(isClassMatch("org/**Test", "org/acme/FooTest")).isFalse();
        assertThat(isClassMatch("org?FooTest", "org/FooTest")).isFalse();
        assertThat(isClassMatch("org/?ooTest", "org/FooTest")).isTrue();
    }

    @Test
    void isClassMatch_regexAgainstTheClassFilePath() {
        assertThat(isClassMatch("%regex[.*Foo.*]", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("%regex[org/acme/.*Test.class]", "org/acme/FooTest")).isTrue();
        assertThat(isClassMatch("%regex[org/acme/.*Test]", "org/acme/FooTest")).isFalse();
        assertThat(isClassMatch("%regex[(unclosed]", "org/acme/FooTest")).isFalse();
    }

    @Test
    void isSelected_withTheDefaultIncludesAndExcludes() {
        assertThat(isSelected(SUREFIRE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/FooTest")).isTrue();
        assertThat(isSelected(SUREFIRE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/TestFoo")).isTrue();
        assertThat(isSelected(SUREFIRE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/FooTests")).isTrue();
        assertThat(isSelected(SUREFIRE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/FooTestCase")).isTrue();
        assertThat(isSelected(SUREFIRE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/FooIT")).isFalse();
        assertThat(isSelected(SUREFIRE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/Outer$InnerTest")).isFalse();
        assertThat(isSelected(FAILSAFE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/FooIT")).isTrue();
        assertThat(isSelected(FAILSAFE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/ITFoo")).isTrue();
        assertThat(isSelected(FAILSAFE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/FooITCase")).isTrue();
        assertThat(isSelected(FAILSAFE_DEFAULT_INCLUDES, DEFAULT_EXCLUDES, "a/FooTest")).isFalse();
    }

    @Test
    void isSelected_requiresAnIncludeAndNoExclude() {
        assertThat(isSelected(List.of("**/*Spec"), List.of(), "a/FooSpec")).isTrue();
        assertThat(isSelected(List.of("**/*Spec"), List.of("**/Slow*"), "a/SlowSpec")).isFalse();
        // no positive include, as surefire's TestListResolver reads it: every class (Selections never passes an
        // empty list, it substitutes the plugin defaults)
        assertThat(isSelected(List.of(), List.of(), "a/FooTest")).isTrue();
    }

    @Test
    void selectedMethods_positivePatternsChooseClassesAndMethods() {
        assertThat(selectedMethods("FooTest", "a/FooTest", METHODS)).isEqualTo(METHODS);
        assertThat(selectedMethods("BarTest", "a/FooTest", METHODS)).isEmpty();
        assertThat(selectedMethods("FooTest#fast*", "a/FooTest", METHODS))
            .containsExactlyInAnyOrder("fastOne", "fastTwo");
        assertThat(selectedMethods("FooTest#fastOne+slow", "a/FooTest", METHODS))
            .containsExactlyInAnyOrder("fastOne", "slow");
        assertThat(selectedMethods("FooTest#none", "a/FooTest", METHODS)).isEmpty();
        assertThat(selectedMethods("%regex[.*Foo.*#fast.*]", "a/FooTest", METHODS))
            .containsExactlyInAnyOrder("fastOne", "fastTwo");
        // a -Dtest pattern is not limited by the default includes
        assertThat(selectedMethods("FooSpec", "a/FooSpec", METHODS)).isEqualTo(METHODS);
    }

    @Test
    void selectedMethods_negationRemovesAClassOrItsNamedMethods() {
        assertThat(selectedMethods("*Test,!FooTest", "a/FooTest", METHODS)).isEmpty();
        assertThat(selectedMethods("*Test,!FooTest#slow", "a/FooTest", METHODS))
            .containsExactlyInAnyOrder("fastOne", "fastTwo");
        assertThat(selectedMethods("*Test,!BarTest", "a/FooTest", METHODS)).isEqualTo(METHODS);
        assertThat(selectedMethods("*Test,!%regex[.*Foo.*]", "a/FooTest", METHODS)).isEmpty();
        assertThat(selectedMethods("*Test,!%regex[.*Bar.*]", "a/FooTest", METHODS)).isEqualTo(METHODS);
    }

    /// Surefire 3.5.4 oracle: `-Dtest=!ATest` runs every other class, `CTest$Helper` and IT-named ones included.
    @Test
    void selectedMethods_withOnlyNegations_selectsEveryOtherClass_ignoringTheDefaults() {
        assertThat(selectedMethods("!BarTest", "a/FooTest", METHODS)).isEqualTo(METHODS);
        assertThat(selectedMethods("!BarTest", "a/FooSpec", METHODS)).isEqualTo(METHODS);
        assertThat(selectedMethods("!BarTest", "a/FooIT", METHODS)).isEqualTo(METHODS);
        assertThat(selectedMethods("!BarTest", "a/Outer$Helper", METHODS)).isEqualTo(METHODS);
        assertThat(selectedMethods("!FooTest", "a/FooTest", METHODS)).isEmpty();
        assertThat(selectedMethods("!FooTest#slow", "a/FooTest", METHODS)).containsExactlyInAnyOrder("fastOne", "fastTwo");
    }

    /// Surefire 3.5.4 oracle: the class regex must match the whole class-file path, `.class` included.
    @Test
    void selectedMethods_regexWithoutTheClassSuffix_matchesNothing() {
        assertThat(selectedMethods("%regex[.*FooTest]", "a/FooTest", METHODS)).isEmpty();
        assertThat(selectedMethods("%regex[.*FooTest#fast.*]", "a/FooTest", METHODS)).isEmpty();
        assertThat(selectedMethods("%regex[.*FooTest.class]", "a/FooTest", METHODS)).isEqualTo(METHODS);
    }

    @Test
    void hasMethodPatterns_whenAnyPositiveOrNegatedPatternNamesAMethod() {
        assertThat(hasMethodPatterns("FooTest,BarTest")).isFalse();
        assertThat(hasMethodPatterns("!FooTest,%regex[.*Bar.*]")).isFalse();
        assertThat(hasMethodPatterns("FooTest,BarTest#b")).isTrue();
        assertThat(hasMethodPatterns("FooTest,!BarTest#b")).isTrue();
        assertThat(hasMethodPatterns("%regex[.*Bar.*#b]")).isTrue();
    }

    /// Surefire's scan: positive class parts (method parts ignored), negations only without a method (oracle
    /// scenarios `j3filter`, `tnegm`, `j3negclass`).
    @Test
    void isClassSelected_byClassPartsAndClassNegations() {
        assertThat(isClassSelected("!BarTest", "a/FooSpec")).isTrue();
        assertThat(isClassSelected("FooTest#nothing", "a/FooTest")).isTrue();
        assertThat(isClassSelected("FooTest#nothing", "a/BarTest")).isFalse();
        assertThat(isClassSelected("!FooTest#slow", "a/FooTest")).isTrue();
        assertThat(isClassSelected("!FooTest", "a/FooTest")).isFalse();
        assertThat(isClassSelected("*Test,!%regex[.*Foo.*]", "a/FooTest")).isFalse();
        assertThat(isClassSelected("*Test,!%regex[.*Foo.*]", "a/BarTest")).isTrue();
    }

    /// Surefire 3.5.4 oracle: `<include>**/*Test, !KMixedTest</include>` runs every `*Test` but KMixedTest.
    @Test
    void isSelected_aNegatedIncludeIsAnExclude() {
        var includes = List.of("**/*Test", "!KMixedTest");

        assertThat(isSelected(includes, DEFAULT_EXCLUDES, "o/ATest")).isTrue();
        assertThat(isSelected(includes, DEFAULT_EXCLUDES, "o/KMixedTest")).isFalse();
        assertThat(isSelected(includes, DEFAULT_EXCLUDES, "o/KMixedSpec")).isFalse();
    }

    @Test
    void isSelected_onlyNegatedIncludes_selectEveryOtherClass_underTheExcludes() {
        var includes = List.of("!ATest");

        assertThat(isSelected(includes, DEFAULT_EXCLUDES, "o/FooSpec")).isTrue();
        assertThat(isSelected(includes, DEFAULT_EXCLUDES, "o/ATest")).isFalse();
        assertThat(isSelected(includes, DEFAULT_EXCLUDES, "o/Outer$Helper")).isFalse();
    }
}
