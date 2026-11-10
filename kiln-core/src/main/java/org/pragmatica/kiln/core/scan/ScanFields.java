package org.pragmatica.kiln.core.scan;

import java.time.Instant;
import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.scan.ScanError.BadField;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Field readers of one scan line's JSON tree. Lenient only towards fields it does not know: a known field that is
/// absent, null or of the wrong type fails the line, never reads as a default (an absent `branches` read as no
/// branches would make an unpushed branch look preserved). A key given twice fails the line: last-wins would let a
/// second `count` overrule the first.
sealed interface ScanFields {
    JsonMapper MAPPER = JsonMapper.jsonMapper()
                                  .withPragmaticaTypes()
                                  .configure(builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
                                  .build();

    /// The latest epoch second an [Instant] holds.
    long MAX_EPOCH_SECOND = Instant.MAX.getEpochSecond();

    static Result<JsonNode> required(JsonNode line, String field) {
        return Verify.ensure(line.path(field), ScanFields::given, BadField.FACTORY.apply(field));
    }

    /// An optional field: absent or null reads as none; present, it must read as `read` requires.
    static <T> Result<Option<T>> optional(JsonNode line, String field, Fn2<Result<T>, JsonNode, String> read) {
        return given(line.path(field))
               ? read.apply(line, field)
                     .map(Option::some)
               : success(none());
    }

    static Result<String> text(JsonNode line, String field) {
        return required(line, field).filter(BadField.FACTORY.apply(field),
                                            JsonNode::isString)
                       .map(JsonNode::stringValue);
    }

    /// A count: a non-negative integer.
    static Result<Integer> count(JsonNode line, String field) {
        return required(line, field).filter(BadField.FACTORY.apply(field),
                                            ScanFields::isInt)
                       .map(JsonNode::intValue)
                       .filter(BadField.FACTORY.apply(field),
                               Verify.Is::nonNegative);
    }

    /// A non-negative long, such as a byte count or a PID.
    static Result<Long> size(JsonNode line, String field) {
        return required(line, field).flatMap2(ScanFields::sizeOf, field);
    }

    static Result<Boolean> flag(JsonNode line, String field) {
        return required(line, field).filter(BadField.FACTORY.apply(field),
                                            JsonNode::isBoolean)
                       .map(JsonNode::booleanValue);
    }

    /// An instant as whole epoch seconds, the form `stat` prints.
    static Result<Instant> instant(JsonNode line, String field) {
        return size(line, field).flatMap(seconds -> Verify.ensure(seconds,
                                                                  Verify.Is::lessThanOrEqualTo,
                                                                  MAX_EPOCH_SECOND,
                                                                  BadField.FACTORY.apply(field)))
                   .map(Instant::ofEpochSecond);
    }

    /// An array whose every element reads as `element` requires.
    static <T> Result<List<T>> list(JsonNode line, String field, Fn1<Result<T>, JsonNode> element) {
        return required(line, field).filter(BadField.FACTORY.apply(field),
                                            JsonNode::isArray)
                       .flatMap2(ScanFields::elements, element);
    }

    private static <T> Result<List<T>> elements(JsonNode array, Fn1<Result<T>, JsonNode> element) {
        return Result.allOf(array.values().stream().map(element::apply).toList());
    }

    static Result<Long> sizeOf(JsonNode node, String field) {
        return Verify.ensure(node,
                             ScanFields::isLong,
                             BadField.FACTORY.apply(field))
                     .map(JsonNode::longValue)
                     .filter(BadField.FACTORY.apply(field),
                             Verify.Is::nonNegative);
    }

    private static boolean given(JsonNode node) {
        return ! node.isMissingNode() && !node.isNull();
    }

    private static boolean isInt(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt();
    }

    private static boolean isLong(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong();
    }

    record unused() implements ScanFields {}
}
