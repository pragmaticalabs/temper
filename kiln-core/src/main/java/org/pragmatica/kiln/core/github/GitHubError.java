package org.pragmatica.kiln.core.github;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Failures of a GitHub read: the HTTP status GitHub answered, or an answer kiln cannot decode. A value object a field
/// does not parse into fails as [org.pragmatica.kiln.core.model.ModelError.InvalidValue], which names the field.
public sealed interface GitHubError extends Cause {
    /// GitHub answered a status other than 200 or 304. [RequiredChecks#resolve] shows the bare status (`UNKNOWN(403)`).
    record HttpStatus(int status, String message) implements GitHubError {
        public static final Fn1<HttpStatus, Integer> FACTORY = Causes.forOneValue("GitHub answered HTTP %d",
                                                                                  HttpStatus::new);
    }

    /// The answer, or one of its fields (`what`), does not parse; the parser's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unparseable(String what, Cause origin, String message) implements GitHubError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<Unparseable, String, Cause> FACTORY = Causes.forTwoValues("Unparseable GitHub %s",
                                                                                          Unparseable::new);
    }

    /// A field kiln requires is missing, or holds the wrong JSON type. `field` is its dotted path (`$` is the answer).
    record BadField(String field, String message) implements GitHubError, Cause.Terminal {
        public static final Fn1<BadField, String> FACTORY = Causes.forOneValue("Missing or mistyped GitHub field %s",
                                                                               BadField::new);
    }
}
