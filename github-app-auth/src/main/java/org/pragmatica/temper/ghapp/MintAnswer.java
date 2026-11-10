package org.pragmatica.temper.ghapp;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;


/// GitHub's answer to a mint: 201 with `token`, `expires_at` and the `permissions` granted, or a refusal whose
/// `message` is kept (it names the cause: a bad JWT, a clock, a permission the App lacks).
sealed interface MintAnswer {
    int CREATED = 201;
    int MESSAGE_CAP = 300;
    Pattern CLOCK = Pattern.compile("\\b(iat|exp)\\b");

    /// A minted token. `toString` never shows the token.
    record Minted(String token, Instant expiresAt, String permissions) implements MintAnswer {
        @Override
        public String toString() {
            return "Minted[expiresAt=" + expiresAt + "]";
        }
    }

    static Result<Minted> parse(HttpResponse<String> response) {
        return response.statusCode() == CREATED
               ? minted(response.body())
               : AppAuthError.MintRefused.FACTORY.apply(response.statusCode(),
                                                        message(response.body()))
                                                 .result();
    }

    /// A 401 whose message names `iat` or `exp` is a clock problem.
    static boolean clockSkew(AppAuthError.MintRefused refused) {
        return refused.status() == 401 && CLOCK.matcher(refused.reason().toLowerCase()).find();
    }

    private static Result<Minted> minted(String body) {
        return GitHubAppAuth.MAPPER.readTree(body)
                                   .mapError(_ -> AppAuthError.MintMalformed.FACTORY.apply("body is not JSON"))
                                   .flatMap(MintAnswer::minted);
    }

    private static Result<Minted> minted(JsonNode node) {
        return text(node, "token").flatMap(token -> text(node, "expires_at").flatMap(MintAnswer::instant)
                                                        .map(expires -> new Minted(token,
                                                                                   expires,
                                                                                   node.path("permissions").toString())));
    }

    private static Result<String> text(JsonNode node, String field) {
        return Option.from(node.path(field).stringValueOpt())
                     .filter(value -> !value.isBlank())
                     .toResult(AppAuthError.MintMalformed.FACTORY.apply("no " + field));
    }

    private static Result<Instant> instant(String text) {
        return Result.lift(_ -> AppAuthError.MintMalformed.FACTORY.apply("expires_at is not a timestamp"),
                           () -> Instant.parse(text));
    }

    private static String message(String body) {
        var text = GitHubAppAuth.MAPPER.readTree(body)
                                       .map(node -> Option.from(node.path("message").stringValueOpt()).or(""))
                                       .or("");

        return Redact.text(text, MESSAGE_CAP);
    }

    record unused() implements MintAnswer {}
}
