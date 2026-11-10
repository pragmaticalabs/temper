package org.pragmatica.kiln.service.api;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.service.http.ApiError;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.store.WorkPhase;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// The body of `POST /api/v1/ingest/work`, read strictly into a [WorkEvent]: one JSON object
/// `{"item","title"?,"phase","round","coder"?,"verifier"?,"note"?,"at"?}`, every field checked, every refusal a 400
/// naming the field. A field outside that list, or one given twice, refuses the whole body, so a misspelt `verifer`
/// is never silently dropped. An optional field may be absent or `null`; `at` absent is kiln's clock.
///
/// - `item`: a work package id, `T1-WP5` or `T2-WP-0T`: letters and digits in `-`-separated segments, at most 64 characters,
///   upper-cased (`t1-wp5` is `T1-WP5`, one card); its first segment is the item (`T1`).
/// - `phase`: a [WorkPhase#label]; `round`: an integer 0 or more.
/// - `coder`, `verifier`: an agent name, a letter or digit then letters, digits, `.`, `_` or `-`, at most 64 characters.
/// - `title` at most 200 characters and not blank; `note` at most 300. Characters are code points, as Postgres'
///   `char_length` counts them (the V5 CHECKs), not UTF-16 units. No text holds a C0 or C1 control character (a newline
///   included) or a lone surrogate ([#CONTROL]): the store would alter NUL and a lone surrogate silently, and this route,
///   not `kiln-work`'s locale-dependent check, is the gate.
/// - `at`: an ISO-8601 instant at most [#SKEW] ahead of kiln's clock and at most [#MAX_AGE] behind it, truncated to
///   microseconds (Postgres' precision, so the answer echoes what is stored). A future time would hold the card's phase
///   and the ledger's freshness until the clock caught up; a mistyped past one would move the item's submission back for
///   good, since the ledger is append-only. The default, kiln's clock, is truncated too.
public sealed interface WorkRequest {
    Set<String> FIELDS = Set.of("item", "title", "phase", "round", "coder", "verifier", "note", "at");
    Pattern ID = Pattern.compile("[A-Za-z0-9]+(-[A-Za-z0-9]+)*");
    Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    int MAX_ID = 64;
    int MAX_NAME = 64;
    int MAX_TITLE = 200;
    int MAX_NOTE = 300;
    Duration SKEW = Duration.ofSeconds(60);
    Duration MAX_AGE = Duration.ofDays(30);
    /// A C0 or C1 control character (NUL, a newline, DEL and U+0080-U+009F included) or a lone surrogate (`\p{Cs}`
    /// matches only an unpaired one: a valid pair is one code point outside the category).
    Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cs}]");

    JsonMapper MAPPER = JsonMapper.jsonMapper()
                                  .withPragmaticaTypes()
                                  .configure(builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
                                  .build();

    /// The event `body` describes, `at` defaulting to `now`.
    static Result<WorkEvent> workEvent(String body, Instant now) {
        return object(body).flatMap(node -> event(node, now));
    }

    private static Result<JsonNode> object(String body) {
        return MAPPER.readTree(body)
                     .mapError(_ -> ApiError.badRequest("body", "not JSON, or a field given twice"))
                     .filter(ApiError.badRequest("body", "not a JSON object"),
                             JsonNode::isObject)
                     .filter(ApiError.badRequest("body",
                                                 "a field outside " + FIELDS.stream().sorted().toList()),
                             node -> FIELDS.containsAll(node.propertyNames()));
    }

    private static Result<WorkEvent> event(JsonNode node, Instant now) {
        return Result.all(id(node.path("item")),
                          title(node.path("title")),
                          phase(node.path("phase")),
                          round(node.path("round")),
                          name(node.path("coder"),
                               "coder"),
                          name(node.path("verifier"),
                               "verifier"),
                          note(node.path("note")),
                          at(node.path("at"),
                             now))
                     .map((id, title, phase, round, coder, verifier, note, at) -> new WorkEvent(id,
                                                                                                item(id),
                                                                                                title,
                                                                                                phase,
                                                                                                round,
                                                                                                coder,
                                                                                                verifier,
                                                                                                note,
                                                                                                at));
    }

    /// The id's first segment: `T1` of `T1-WP5`.
    static String item(String id) {
        return id.split("-", 2) [0];
    }

    static Result<String> id(JsonNode value) {
        return string(value, "item").filter(ApiError.badRequest("item", "not a work package id like T1-WP5"),
                                            WorkRequest::isId)
                     .map(id -> id.toUpperCase(Locale.ROOT));
    }

    private static boolean isId(String id) {
        return id.length() <= MAX_ID && ID.matcher(id).matches();
    }

    static Result<WorkPhase> phase(JsonNode value) {
        return string(value, "phase").flatMap(label -> WorkPhase.workPhase(label).toResult(ApiError.badRequest("phase",
                                                                                                               "not a phase")));
    }

    static Result<Integer> round(JsonNode value) {
        return Verify.ensure(value,
                             WorkRequest::isRound,
                             ApiError.badRequest("round", "required, an integer 0 or more"))
                     .map(JsonNode::intValue);
    }

    private static boolean isRound(JsonNode value) {
        return value.isIntegralNumber()
               && value.canConvertToInt()
               && value.intValue() >= 0;
    }

    static Result<Option<String>> title(JsonNode value) {
        return optional(value, "title").flatMap(title -> Verify.ensureOption(title,
                                                                             text -> Verify.Is.notBlank(text)
                                                                                     && codePoints(text) <= MAX_TITLE
                                                                                     && plain(text),
                                                                             ApiError.badRequest("title",
                                                                                                 "blank, a control character, or over " + MAX_TITLE
                                                                                                + " characters")));
    }

    static Result<Option<String>> note(JsonNode value) {
        return optional(value, "note").flatMap(note -> Verify.ensureOption(note,
                                                                           text -> codePoints(text) <= MAX_NOTE && plain(text),
                                                                           ApiError.badRequest("note",
                                                                                               "a control character, or over " + MAX_NOTE
                                                                                              + " characters")));
    }

    static Result<Option<String>> name(JsonNode value, String field) {
        return optional(value, field).flatMap(name -> Verify.ensureOption(name,
                                                                          WorkRequest::isName,
                                                                          ApiError.badRequest(field, "not an agent name")));
    }

    private static boolean isName(String name) {
        return name.length() <= MAX_NAME && NAME.matcher(name).matches();
    }

    static Result<Instant> at(JsonNode value, Instant now) {
        return optional(value, "at").flatMap(raw -> instantOr(raw, now));
    }

    private static Result<Instant> instantOr(Option<String> raw, Instant now) {
        return raw.map(text -> instant(text, now))
                  .or(success(now))
                  .map(at -> at.truncatedTo(ChronoUnit.MICROS));
    }

    private static Result<Instant> instant(String raw, Instant now) {
        return DateTime.parseInstant(raw)
                       .mapError(_ -> ApiError.badRequest("at", "not an ISO-8601 instant"))
                       .filter(ApiError.badRequest("at",
                                                   "more than " + SKEW.toSeconds() + "s ahead of kiln's clock"),
                               at -> !at.isAfter(now.plus(SKEW)))
                       .filter(ApiError.badRequest("at",
                                                   "more than " + MAX_AGE.toDays() + " days before kiln's clock"),
                               at -> !at.isBefore(now.minus(MAX_AGE)));
    }

    private static Result<String> string(JsonNode value, String field) {
        return Verify.ensure(value,
                             JsonNode::isString,
                             ApiError.badRequest(field, "required, a string"))
                     .map(JsonNode::stringValue);
    }

    /// Absent and `null` are none; any other value must be a string.
    private static Result<Option<String>> optional(JsonNode value, String field) {
        return Option.some(value)
                     .filter(WorkRequest::given)
                     .map(given -> present(given, field))
                     .or(success(none()));
    }

    private static Result<Option<String>> present(JsonNode value, String field) {
        return string(value, field).map(Option::some);
    }

    private static boolean given(JsonNode value) {
        return ! value.isMissingNode() && !value.isNull();
    }

    /// No [#CONTROL] character.
    private static boolean plain(String text) {
        return ! CONTROL.matcher(text).find();
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    record unused() implements WorkRequest {}
}
