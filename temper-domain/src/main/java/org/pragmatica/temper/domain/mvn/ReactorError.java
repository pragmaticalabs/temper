package org.pragmatica.temper.domain.mvn;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Why the reactor could not be read from the tree (m1-design §1.6). Every case makes the reactor unknown,
/// which keeps the conservative M0 judgement. They are terminal on the premise that the same tree gives the same
/// answer: that holds only while `pomAt` answers none for an absent file alone. A caller that maps a failed read
/// (a transient `git show` failure) to none turns it into a terminal [MissingPom]; the conservative fallback
/// still applies, so the cost is a refusal that a retry might have avoided, never a wrong admission.
public sealed interface ReactorError extends Cause {
    /// A module the reactor names has no `pom.xml` at the SHA (`""` is the root).
    record MissingPom(String module, String message) implements ReactorError, Cause.Terminal {
        public static final Fn1<MissingPom, String> FACTORY = Causes.forOneValue("No pom.xml for reactor module '%s'",
                                                                                 MissingPom::new);
    }

    /// A reactor pom is not well-formed XML; the parser's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record MalformedPom(String module, Cause origin, String message) implements ReactorError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<MalformedPom, String, Cause> FACTORY = Causes.forTwoValues("Malformed pom.xml of reactor module '%s'",
                                                                                           MalformedPom::new);
    }

    /// A `<module>` entry that leaves the repository, is absolute, or names a pom file instead of a directory.
    record UnsupportedModule(String module, String entry, String message) implements ReactorError, Cause.Terminal {
        public static final Fn2<UnsupportedModule, String, String> FACTORY = Causes.forTwoValues("Reactor module '%s' has an unsupported <module> entry '%s'",
                                                                                                 UnsupportedModule::new);
    }

    /// The reactor has more modules than the reader follows (a bound on the `git show` calls per submit).
    record TooManyModules(int limit, String message) implements ReactorError, Cause.Terminal {
        public static final Fn1<TooManyModules, Integer> FACTORY = Causes.forOneValue("Reactor has more than %d modules",
                                                                                      TooManyModules::new);
    }
}
