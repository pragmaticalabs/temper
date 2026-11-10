package org.pragmatica.temper.domain.task;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.Values;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// The maintainer's ruling on an open NEEDS-RULING request (m3-design §5.5, T11): the option chosen (one the request offered,
/// which the store checks against the stored request), the ruling text, the `know:` commit's SHA stored as text and
/// never resolved (ruling R-M3-28), and the extra rounds a round-cap ruling grants (ruling R-M3-10).
public record RulingDecision(String option, String text, Option<Sha> know, Option<Integer> extraRounds) {
    /// At most this many extra rounds per ruling [G]: a larger grant is a new task, not a longer loop.
    public static final int MAX_EXTRA_ROUNDS = 10;

    public static Result<RulingDecision> rulingDecision(String option,
                                                        String text,
                                                        Option<String> know,
                                                        Option<Integer> extraRounds) {
        return Result.all(Verify.ensure(option,
                                        Verify.Is::matches,
                                        ParkRequest.OPTION_ID,
                                        invalid("option")),
                          Verify.ensure(text,
                                        Verify.Is::present,
                                        invalid("text")),
                          Values.optional(know, Sha::sha),
                          Verify.ensureOption(extraRounds,
                                              Verify.Is::between,
                                              1,
                                              MAX_EXTRA_ROUNDS,
                                              invalid("extraRounds")))
                     .map(RulingDecision::new);
    }
}
