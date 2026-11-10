package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// The lenient reads every GitHub decoder shares (spec §4): a field is found by its dotted path and every other field
/// is never looked at, so an added field cannot break a decode. A required field that is missing, or holds the wrong
/// JSON type, fails as [GitHubError.BadField] naming its path. An optional one reads none when missing or `null`, and
/// still fails when it holds the wrong type: a mistyped `merged_at` must not read as "not merged".
public sealed interface GitHubJson {
    JsonMapper MAPPER = JsonMapper.jsonMapper().build();
    Pattern DOT = Pattern.compile("\\.");
    String ROOT = "$";
    /// GitHub's "any app" in a required check (`app_id: -1`); kiln reads it as no app id, as it reads `null`.
    long ANY_APP = -1;

    static Result<JsonNode> tree(String json) {
        return MAPPER.readTree(json).mapError(origin -> GitHubError.Unparseable.FACTORY.apply("answer", origin));
    }

    /// The node at `path` below `node`; a missing or `null` step reads as missing.
    static JsonNode at(JsonNode node, String path) {
        return DOT.splitAsStream(path).reduce(node, JsonNode::path, (first, second) -> second);
    }

    /// Every element of the array at `path`, each decoded; the first element that fails fails the whole list.
    static <T> Result<List<T>> list(JsonNode node, String path, Fn1<Result<T>, JsonNode> decoder) {
        return each(at(node, path), path, decoder);
    }

    /// As [#list], but an absent field is an empty list.
    static <T> Result<List<T>> optionalList(JsonNode node, String path, Fn1<Result<T>, JsonNode> decoder) {
        return absent(at(node, path))
               ? success(List.of())
               : list(node, path, decoder);
    }

    /// Every element of `array` itself (`path` names it for the failure), each decoded.
    static <T> Result<List<T>> each(JsonNode array, String path, Fn1<Result<T>, JsonNode> decoder) {
        return Verify.ensure(array, JsonNode::isArray, badField(path)).flatMap(found -> decoded(found, decoder));
    }

    static Result<String> text(JsonNode node, String path) {
        return Option.from(at(node, path).stringValueOpt()).toResult(badField(path));
    }

    static Result<Option<String>> optionalText(JsonNode node, String path) {
        return absent(at(node, path))
               ? success(none())
               : text(node, path).map(Option::some);
    }

    /// A full commit id at `path`; one that is not 40 lowercase hex fails the read.
    static Result<Sha> sha(JsonNode node, String path) {
        return text(node, path).flatMap(raw -> parsedSha(path, raw));
    }

    /// As [#sha], none when absent or `null`.
    static Result<Option<Sha>> optionalSha(JsonNode node, String path) {
        return optionalText(node, path).flatMap(raw -> optional(raw, value -> parsedSha(path, value)));
    }

    private static Result<Sha> parsedSha(String path, String raw) {
        return Sha.sha(raw).mapError(origin -> GitHubError.Unparseable.FACTORY.apply(path, origin));
    }

    static Result<Long> number(JsonNode node, String path) {
        return Verify.ensure(at(node, path), GitHubJson::isLong, badField(path)).map(JsonNode::longValue);
    }

    static Result<Option<Long>> optionalNumber(JsonNode node, String path) {
        return absent(at(node, path))
               ? success(none())
               : number(node, path).map(Option::some);
    }

    static Result<Boolean> bool(JsonNode node, String path) {
        return Verify.ensure(at(node, path), JsonNode::isBoolean, badField(path)).map(JsonNode::booleanValue);
    }

    static Result<Instant> instant(JsonNode node, String path) {
        return text(node, path).flatMap(raw -> parsedInstant(path, raw));
    }

    static Result<Option<Instant>> optionalInstant(JsonNode node, String path) {
        return optionalText(node, path).flatMap(raw -> optional(raw, value -> parsedInstant(path, value)));
    }

    /// An absent value stays absent; a present one must parse.
    static <T, R> Result<Option<R>> optional(Option<T> raw, Fn1<Result<R>, T> parser) {
        return raw.map(value -> present(parser, value))
                  .or(success(none()));
    }

    private static <T, R> Result<Option<R>> present(Fn1<Result<R>, T> parser, T value) {
        return parser.apply(value)
                     .map(Option::some);
    }

    /// One `{context, <appField>}` of a required list (`app_id` in a branch read, `integration_id` in a ruleset).
    static Result<RequiredCheck> requiredCheck(JsonNode node, String appField) {
        return Result.all(text(node, "context").flatMap(CheckContext::checkContext),
                          optionalNumber(node, appField).flatMap(GitHubJson::appId))
                     .map(RequiredCheck::new);
    }

    static GitHubError.BadField badField(String path) {
        return GitHubError.BadField.FACTORY.apply(path);
    }

    private static Result<Option<AppId>> appId(Option<Long> raw) {
        return optional(raw.filter(GitHubJson::pinned), AppId::appId);
    }

    private static boolean pinned(long appId) {
        return appId != ANY_APP;
    }

    private static <T> Result<List<T>> decoded(JsonNode array, Fn1<Result<T>, JsonNode> decoder) {
        return Result.allOf(array.valueStream().map(decoder::apply));
    }

    private static Result<Instant> parsedInstant(String path, String raw) {
        return DateTime.parseInstant(raw).mapError(origin -> GitHubError.Unparseable.FACTORY.apply(path, origin));
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static boolean isLong(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong();
    }

    record unused() implements GitHubJson {}
}
