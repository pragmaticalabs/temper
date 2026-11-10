package org.pragmatica.temper.ghapp;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.lang.Result;


/// The mint request (F5): `POST {api}/app/installations/{id}/access_tokens`, JWT as bearer, the body
/// narrowing the token to the configured repositories and permissions.
sealed interface MintRequest {
    /// The JSON body, `{"repositories":[...],"permissions":{...}}`.
    static Result<String> body(AppAuthConfig config) {
        Map<String, Object> body = new LinkedHashMap<>();

        body.put("repositories", config.repositories());
        body.put("permissions", config.permissions());

        return GitHubAppAuth.MAPPER.writeAsString(body);
    }

    static HttpRequest request(AppAuthConfig config, String jwt, String body) {
        return HttpRequest.newBuilder(uri(config))
                          .timeout(GitHubAppAuth.TIMEOUT)
                          .header("Authorization", "Bearer " + jwt)
                          .header("Accept", "application/vnd.github+json")
                          .header("X-GitHub-Api-Version", "2022-11-28")
                          .header("User-Agent", "temper-github-app-auth")
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString(body))
                          .build();
    }

    private static URI uri(AppAuthConfig config) {
        var base = config.apiBase().toString();
        var root = base.endsWith("/")
                   ? base.substring(0, base.length() - 1)
                   : base;

        return URI.create(root + "/app/installations/" + config.installationId() + "/access_tokens");
    }

    record unused() implements MintRequest {}
}
