package org.pragmatica.kiln.service.scan;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.scan.ScanTransportError.BadAnswer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;


/// The `reach` and `size` answers of `kiln-scan` (kiln-f1-design §3.2), read strictly: a line that is not its verb's
/// contract fails the whole answer, so a garbled answer never reads as "in no bare" or as a size. A key given twice fails
/// the line, as in kiln-core's scan lines.
public sealed interface ScanAnswers {
    JsonMapper MAPPER = JsonMapper.jsonMapper()
                                  .withPragmaticaTypes()
                                  .configure(builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
                                  .build();

    /// `{"sha":…,"ref":<first ref of the host's bare or null>}` per SHA, then `{"end":true,"count":N}`: whether each
    /// asked SHA is in the host's own bare. The answer must name exactly the asked SHAs, each once.
    static Result<Map<Sha, Boolean>> reach(Set<Sha> asked, List<String> lines) {
        return Option.from(lines.stream().reduce((_, later) -> later))
                     .toResult(bad("reach", "no line"))
                     .flatMap(last -> trailer(last,
                                              lines.size() - 1))
                     .flatMap(_ -> Result.firstFailureOf(lines.stream()
                                                              .limit(lines.size() - 1)
                                                              .map(ScanAnswers::reachLine)
                                                              .toList()))
                     .flatMap(answers -> exactly(asked, answers));
    }

    /// `{"path":…,"kb":N}` naming `path`: its size in KiB.
    static Result<Long> size(CheckoutPath path, List<String> lines) {
        return Verify.ensure(lines,
                             list -> list.size() == 1,
                             bad("size", "not one line"))
                     .flatMap(one -> Option.from(one.stream().findFirst()).toResult(bad("size", "not one line")))
                     .flatMap(line -> json("size", line))
                     .flatMap(line -> Verify.ensure(line,
                                                    node -> names(node, path),
                                                    bad("size", "another path")))
                     .map(line -> line.path("kb"))
                     .flatMap(kb -> Verify.ensure(kb,
                                                  ScanAnswers::isKb,
                                                  bad("size", "kb")))
                     .map(JsonNode::longValue);
    }

    private static Result<JsonNode> trailer(String line, int count) {
        return json("reach", line).flatMap(node -> Verify.ensure(node,
                                                                 trailer -> isTrailer(trailer, count),
                                                                 bad("reach", "no trailer counting the lines")));
    }

    private static Result<Map.Entry<Sha, Boolean>> reachLine(String line) {
        return json("reach", line).flatMap(node -> Verify.ensure(node,
                                                                 ScanAnswers::isReachLine,
                                                                 bad("reach", "a line")))
                   .flatMap(ScanAnswers::inBare);
    }

    /// The ref names the first ref of the host's bare containing the SHA; null means none does.
    private static Result<Map.Entry<Sha, Boolean>> inBare(JsonNode line) {
        return Sha.sha(line.path("sha").stringValue())
                  .mapError(_ -> bad("reach", "a SHA"))
                  .map(sha -> Map.entry(sha,
                                        line.path("ref").isString()));
    }

    private static boolean isTrailer(JsonNode line, int count) {
        return line.path("end")
                   .isBoolean()
               && line.path("end")
                      .booleanValue()
               && line.path("count")
                      .isIntegralNumber()
               && line.path("count")
                      .longValue() == count;
    }

    private static boolean isReachLine(JsonNode line) {
        var ref = line.path("ref");

        return line.path("sha")
                   .isString() && (ref.isString() || ref.isNull());
    }

    private static boolean names(JsonNode line, CheckoutPath path) {
        var named = line.path("path");

        return named.isString() && named.stringValue()
                                        .equals(path.value());
    }

    private static boolean isKb(JsonNode kb) {
        return kb.isIntegralNumber()
               && kb.canConvertToLong()
               && kb.longValue() >= 0;
    }

    private static Result<Map<Sha, Boolean>> exactly(Set<Sha> asked, List<Map.Entry<Sha, Boolean>> answers) {
        var answered = answers.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        return Verify.ensure(answers,
                             list -> list.size() == asked.size() && answered.equals(asked),
                             bad("reach", "not the asked SHAs"))
                     .map(list -> list.stream()
                                      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private static Result<JsonNode> json(String verb, String line) {
        return MAPPER.readTree(line)
                     .mapError(_ -> bad(verb, "not JSON"))
                     .flatMap(node -> Verify.ensure(node,
                                                    JsonNode::isObject,
                                                    bad(verb, "not an object")));
    }

    private static BadAnswer bad(String verb, String detail) {
        return BadAnswer.FACTORY.apply(verb, detail);
    }

    record unused() implements ScanAnswers {}
}
