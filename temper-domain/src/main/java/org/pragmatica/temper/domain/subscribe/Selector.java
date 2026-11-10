package org.pragmatica.temper.domain.subscribe;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.Values;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A subscriber selector (m2-design §2.2), typed so that a ref named `4711` never matches job 4711 and a short SHA is
/// refused instead of matching nothing (F3). Stored and sent as [#wire]: `job:<id>`, `sha:<40 hex>` or `ref:<name>`.
/// A ref cannot contain `:` ([Ref]), so the prefix is never part of a ref's name.
///
/// Matching an event: `job:` against its job id, `sha:` against its payload's SHA, `ref:` against its payload's
/// requested ref. A job submitted by SHA has no ref and never matches a `ref:` selector. An empty selector set means
/// every job (spec §8.3, F4; ruled R-M2-9).
public sealed interface Selector {
    String JOB = "job:";
    String SHA = "sha:";
    String REF = "ref:";
    String FIELD = "selector";
    /// The bare forms the CLI classifies, in this order: a job id (as [JobId] takes it), a full SHA, a short SHA. Hex is
    /// matched in either case, so a SHA pasted in upper case is a SHA (stored lower case, as [Sha] keeps it) or refused
    /// as short, never silently a ref that matches nothing; the design's `[0-9a-f]` is lower case only [G].
    Pattern JOB_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    Pattern FULL_SHA = Pattern.compile("^[0-9a-fA-F]{40}$");
    Pattern SHORT_SHA = Pattern.compile("^[0-9a-fA-F]{7,39}$");
    String MAX_JOB_ID = Long.toString(Long.MAX_VALUE);
    Pattern PREFIXED = Pattern.compile("^(job|sha|ref):.*$", Pattern.DOTALL);
    /// The stored and wire form.
    String wire();
    /// Whether an event with these facts is selected by this selector.
    boolean matches(Option<JobId> eventJob, Option<String> eventSha, Option<String> eventRef);

    /// `job:<id>`.
    record JobSelector(JobId job) implements Selector {
        @Override
        public String wire() {
            return JOB + job.value();
        }

        @Override
        public boolean matches(Option<JobId> eventJob, Option<String> eventSha, Option<String> eventRef) {
            return eventJob.filter(job::equals)
                           .isPresent();
        }
    }

    /// `sha:<40 hex>`.
    record ShaSelector(Sha sha) implements Selector {
        @Override
        public String wire() {
            return SHA + sha.value();
        }

        @Override
        public boolean matches(Option<JobId> eventJob, Option<String> eventSha, Option<String> eventRef) {
            return eventSha.filter(sha.value()::equals)
                           .isPresent();
        }
    }

    /// `ref:<name>`, compared with the ref as it was submitted (`main` is not `refs/heads/main`).
    record RefSelector(Ref ref) implements Selector {
        @Override
        public String wire() {
            return REF + ref.value();
        }

        @Override
        public boolean matches(Option<JobId> eventJob, Option<String> eventSha, Option<String> eventRef) {
            return eventRef.filter(ref.value()::equals)
                           .isPresent();
        }
    }

    /// Every job when `selectors` is empty, otherwise the events at least one selector matches.
    static boolean selects(List<Selector> selectors,
                           Option<JobId> eventJob,
                           Option<String> eventSha,
                           Option<String> eventRef) {
        return selectors.isEmpty() || selectors.stream()
                                               .anyMatch(selector -> selector.matches(eventJob, eventSha, eventRef));
    }

    /// The wire and stored form: the prefix is required; a null or blank input fails as well.
    static Result<Selector> selector(String raw) {
        return Values.matching(raw, PREFIXED, FIELD).flatMap(Selector::prefixed);
    }

    /// The CLI's bare argument (m2-design §2.2): `job:N`, `sha:S` and `ref:R` are taken as written; otherwise a job id
    /// is a job, 40 hex is a SHA, 7 to 39 hex is refused as ambiguous, and anything else is a ref.
    static Result<Selector> classified(String raw) {
        return Verify.ensure(raw, Verify.Is::present, invalid(FIELD)).flatMap(Selector::bare);
    }

    private static Result<Selector> bare(String raw) {
        return switch (raw) {
            case String prefixed when PREFIXED.matcher(prefixed).matches() -> selector(prefixed);
            case String id when JOB_ID.matcher(id).matches() -> jobSelector(id);
            case String full when FULL_SHA.matcher(full).matches() -> shaSelector(full);
            case String shortSha when SHORT_SHA.matcher(shortSha).matches() -> SelectorError.AmbiguousShortSha.FACTORY.apply(shortSha).result();
            default -> refSelector(raw);
        };
    }

    private static Result<Selector> prefixed(String raw) {
        var value = raw.substring(JOB.length());

        return switch (raw.substring(0, JOB.length())) {
            case JOB -> jobSelector(value);
            case SHA -> shaSelector(value);
            default -> refSelector(value);
        };
    }

    private static Result<Selector> jobSelector(String digits) {
        return Verify.ensure(digits,
                             Verify.Is::matches,
                             JOB_ID,
                             invalid("job id"))
                     .flatMap(Selector::inRange)
                     .flatMap(Selector::jobNumber)
                     .flatMap(JobId::jobId)
                     .map(JobSelector::new);
    }

    /// Up to 19 digits can still exceed `Long.MAX_VALUE`.
    /// 19 digits can exceed `Long.MAX_VALUE`; such an id is refused here as invalid, so the user never reads the JDK's
    /// parse failure.
    private static Result<String> inRange(String digits) {
        return Verify.ensure(digits, Selector::fitsLong, invalid("job id (at most " + Long.MAX_VALUE + ")"));
    }

    private static boolean fitsLong(String digits) {
        return digits.length() < MAX_JOB_ID.length() || digits.compareTo(MAX_JOB_ID) <= 0;
    }

    /// Cannot fail once [#inRange] passed; the parse stays a `Result` because the JDK's can throw.
    private static Result<Long> jobNumber(String digits) {
        return Number.parseLong(digits).mapError(DomainError.unparseable("job id", digits));
    }

    private static Result<Selector> shaSelector(String raw) {
        return Sha.sha(raw).map(ShaSelector::new);
    }

    private static Result<Selector> refSelector(String raw) {
        return Ref.ref(raw).map(RefSelector::new);
    }
}
