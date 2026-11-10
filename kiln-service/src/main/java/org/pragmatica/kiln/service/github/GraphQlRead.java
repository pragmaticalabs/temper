package org.pragmatica.kiln.service.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.kiln.core.github.GitHubJson;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import tools.jackson.databind.JsonNode;


/// The one POST kiln sends (#102, maintainer ruling): a GraphQL read. [#checked] passes a request only when its path is exactly
/// `/graphql` and its body is a JSON object whose `query` declares no `mutation` or `subscription` operation; anything else
/// is refused before a byte is sent. The query is scanned at the operation level (outside every `{ }` selection and every
/// `( )` variable list), skipping comments and string values, so a field or a string that happens to read "mutation" is
/// not mistaken for one, and an operation keyword is never missed for being inside a selection.
public sealed interface GraphQlRead {
    String PATH = "/graphql";
    Set<String> WRITES = Set.of("mutation", "subscription");
    Cause NOT_GRAPHQL = Causes.cause("a POST goes only to " + PATH);
    Cause NO_QUERY = Causes.cause("a GraphQL body must be a JSON object with a string `query`");

    /// `json` when the request is a GraphQL read; else a [GitHubReadError.BadPath] naming why.
    static Result<String> checked(String path, String json) {
        return PATH.equals(path)
               ? query(json).flatMap(GraphQlRead::operations)
                      .map(_ -> json)
                      .mapError(cause -> refused(path, cause))
               : refused(path, NOT_GRAPHQL).result();
    }

    private static Cause refused(String path, Cause cause) {
        return cause instanceof GitHubReadError.BadPath
               ? cause
               : GitHubReadError.BadPath.FACTORY.apply(path, cause);
    }

    private static Result<String> query(String json) {
        return GitHubJson.tree(json).flatMap(GraphQlRead::queryText);
    }

    private static Result<String> queryText(JsonNode root) {
        var query = root.path("query");

        return root.isObject() && query.isString()
               ? Result.success(query.asString())
               : NO_QUERY.result();
    }

    private static Result<String> operations(String query) {
        return operationWords(query).stream()
                             .filter(WRITES::contains)
                             .findFirst()
                             .map(word -> Causes.cause("not a read: the query declares a " + word).<String> result())
                             .orElse(Result.success(query));
    }

    /// The names written at the operation level, in order.
    static List<String> operationWords(String query) {
        var words = new ArrayList<String>();
        var braces = 0;
        var parens = 0;
        var index = 0;

        while (index < query.length()) {
            var at = query.charAt(index);

            if (at == '#') {
                index = lineEnd(query, index);
            } else if (at == '"') {
                index = stringEnd(query, index);
            } else if (at == '{') {
                braces++;
                index++;
            } else if (at == '}') {
                braces--;
                index++;
            } else if (at == '(') {
                parens++;
                index++;
            } else if (at == ')') {
                parens--;
                index++;
            } else if (Character.isLetter(at) || at == '_') {
                var end = nameEnd(query, index);

                if (braces == 0 && parens == 0) {
                    words.add(query.substring(index, end));
                }

                index = end;
            } else {
                index++;
            }
        }

        return List.copyOf(words);
    }

    private static int lineEnd(String query, int from) {
        var end = query.indexOf('\n', from);

        return end < 0
               ? query.length()
               : end + 1;
    }

    /// Past a `"..."` string (escapes honoured) or a `"""..."""` block string.
    private static int stringEnd(String query, int from) {
        if (query.startsWith("\"\"\"", from)) {
            var end = query.indexOf("\"\"\"", from + 3);

            return end < 0
                   ? query.length()
                   : end + 3;
        }

        var index = from + 1;

        while (index < query.length() && query.charAt(index) != '"') {
            index += query.charAt(index) == '\\'
                     ? 2
                     : 1;
        }

        return Math.min(query.length(), index + 1);
    }

    private static int nameEnd(String query, int from) {
        var index = from;

        while (index < query.length() && (Character.isLetterOrDigit(query.charAt(index)) || query.charAt(index) == '_')) {
            index++;
        }

        return index;
    }

    record unused() implements GraphQlRead {}
}
