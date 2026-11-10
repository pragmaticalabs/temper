package org.pragmatica.temper.domain.mvn;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.mvn.UserProperties.Shown;
import org.pragmatica.temper.domain.mvn.UserProperties.TestEffect;

import static org.assertj.core.api.Assertions.assertThat;


/// maintainer rulings Q-M4-11c, Q-M4-11d: every `-D` property the user wrote is shown, in argv order, a skip key set true marked
/// SKIPPED and a selector key NARROWED, every reactor-narrowing option NARROWED and every option off the list UNKNOWN, so
/// a green run with tests skipped or narrowed never reads as tested.
class UserPropertiesTest {
    @Test
    void shown_marksSkipAndSelectorKeys_inArgvOrder() {
        assertThat(UserProperties.shown(List.of("-DskipTests", "-Dtest=FooTest", "-DfailIfNoSpecifiedTests=false", "install")))
            .containsExactly(Shown.define("skipTests", "true", Option.some(TestEffect.SKIPPED)),
                             Shown.define("test", "FooTest", Option.some(TestEffect.NARROWED)),
                             Shown.define("failIfNoSpecifiedTests", "false", Option.none()));
    }

    /// Every skip key and every selector key of the shipped list is marked; every define form is read.
    @Test
    void shown_marksEveryRuledKey() {
        assertThat(UserProperties.shown(List.of("-Dmaven.test.skip=true", "-D", "skipITs", "--define", "ember.skipTests=TRUE", "verify"))
                                 .stream()
                                 .map(Shown::effect))
            .containsExactly(Option.some(TestEffect.SKIPPED), Option.some(TestEffect.SKIPPED), Option.some(TestEffect.SKIPPED));
        assertThat(UserProperties.shown(List.of("-Dit.test=FooIT", "-Dgroups=slow", "-DexcludedGroups=fast", "-DtargetClasses=a.B",
                                                "-DtargetTests=a.BTest", "-Dtest="))
                                 .stream()
                                 .map(Shown::effect))
            .containsOnly(Option.some(TestEffect.NARROWED))
            .hasSize(6);
    }

    /// A skip key set false skips nothing; a boolean key and a key off the list (a job stored before the list) are shown
    /// unmarked.
    @Test
    void shown_leavesUnmarked_whatNeitherSkipsNorNarrows() {
        assertThat(UserProperties.shown(List.of("-DskipTests=false", "-Dsurefire.failIfNoSpecifiedTests=false", "-Djbct.skip", "verify")))
            .containsExactly(Shown.define("skipTests", "false", Option.none()),
                             Shown.define("surefire.failIfNoSpecifiedTests", "false", Option.none()),
                             Shown.define("jbct.skip", "true", Option.none()));
    }

    @Test
    void shown_isEmpty_withoutAPropertyOrANarrowingOption() {
        assertThat(UserProperties.shown(List.of("-B", "-am", "-T", "4", "-P", "acceptance", "install"))).isEmpty();
    }

    /// `-pl`, `-rf`, `-f` and `-N` (the options the list marks `narrows`), in their listed spellings, are shown narrowed
    /// with their values, in argv order among the properties; a value option's value is never read as one (`-T -N`).
    @Test
    void shown_marksNarrowingOptions_inArgvOrder() {
        assertThat(UserProperties.shown(List.of("-pl", "!core", "-DskipTests=false", "--projects", "api,cli", "-rf", ":store",
                                                "--resume-from", "x", "-f", "sub/pom.xml", "--file", "a.xml", "-N",
                                                "--non-recursive", "-T", "-N", "install")))
            .containsExactly(Shown.shown("-pl !core", Option.some(TestEffect.NARROWED)),
                             Shown.define("skipTests", "false", Option.none()),
                             Shown.shown("--projects api,cli", Option.some(TestEffect.NARROWED)),
                             Shown.shown("-rf :store", Option.some(TestEffect.NARROWED)),
                             Shown.shown("--resume-from x", Option.some(TestEffect.NARROWED)),
                             Shown.shown("-f sub/pom.xml", Option.some(TestEffect.NARROWED)),
                             Shown.shown("--file a.xml", Option.some(TestEffect.NARROWED)),
                             Shown.shown("-N", Option.some(TestEffect.NARROWED)),
                             Shown.shown("--non-recursive", Option.some(TestEffect.NARROWED)));
    }

    /// maintainer ruling Q-M4-11d: every element starting with `-` that is no listed spelling (a job stored before the option
    /// list) is shown as written, marked unknown, so it never reads as tested; the verifier's narrowing bypasses among them.
    @Test
    void shown_marksEveryOptionOffTheList_unknown() {
        assertThat(UserProperties.shown(List.of("-pl=a", "-fail-never", "--projects=b", "-T4", "-Pacceptance", "-fa/pom.xml",
                                                "-non-recursive", "-resume-from", "b", "--define=skipTests", "install")))
            .containsExactly(Shown.shown("-pl=a", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("-fail-never", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("--projects=b", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("-T4", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("-fa/pom.xml", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("-non-recursive", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("-resume-from", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("--define=skipTests", Option.some(TestEffect.UNKNOWN)));
    }

    /// A value option with nothing after it is shown as written.
    @Test
    void shown_showsATrailingNarrowingOption_alone() {
        assertThat(UserProperties.shown(List.of("install", "-pl")))
            .containsExactly(Shown.shown("-pl", Option.some(TestEffect.NARROWED)));
    }

    /// With a list that does not parse no option is listed, so every option is shown unknown.
    @Test
    void shown_marksEveryOptionUnknown_whenTheListDoesNotParse() {
        assertThat(UserProperties.shown(List.of("-DskipTests", "-pl", "core", "install"), SideEffectAllowlist.sideEffectAllowlist(List.of("bogus"))))
            .containsExactly(Shown.shown("-DskipTests", Option.some(TestEffect.UNKNOWN)),
                             Shown.shown("-pl", Option.some(TestEffect.UNKNOWN)));
    }

    @Test
    void define_isTheCanonicalForm() {
        assertThat(Shown.define("skipTests", "true", Option.none()).argument()).isEqualTo("-DskipTests=true");
        assertThat(TestEffect.SKIPPED.wire()).isEqualTo("skipped");
        assertThat(TestEffect.NARROWED.wire()).isEqualTo("narrowed");
        assertThat(TestEffect.UNKNOWN.wire()).isEqualTo("unknown");
    }
}
