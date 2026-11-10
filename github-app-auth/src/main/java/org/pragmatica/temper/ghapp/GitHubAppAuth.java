package org.pragmatica.temper.ghapp;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// The installation token of one GitHub App identity. [#token] answers the token to send now:
/// - more than ten minutes left: the held token;
/// - less: one mint first, single-flight (concurrent callers share the one in flight);
/// - a mint that fails keeps the held token serving until its `expires_at`, and the next mint waits 30 s, 1, 2, 4, 5,
///   5... minutes;
/// - past `expires_at` with no new token: [AppAuthError.CredentialUnavailable], and nothing is sent. No fallback to an
///   unauthenticated read or to another identity.
///
/// The key file is read at each mint, so a rotated key needs no restart. Nothing here logs a token, a JWT or a header.
public interface GitHubAppAuth {
    Duration TIMEOUT = Duration.ofSeconds(20);
    JsonMapper MAPPER = JsonMapper.jsonMapper().build();
    /// The token to send now, or why there is none.
    Promise<SecretToken> token();

    /// The auth for `config`. The key is read and its mode checked now: a group- or other-readable key fails the start
    /// as [AppAuthError.KeyFileTooOpen] (`exitCode()` 78); the mint body is rendered once.
    static Result<GitHubAppAuth> githubAppAuth(AppAuthConfig config, HttpClient client, Clock clock) {
        return AppKey.load(config.keyFile())
                     .flatMap(_ -> MintRequest.body(config))
                     .map(body -> new MintingAuth(config, client, clock, body));
    }

    /// A client for the mint: HTTP/1.1, no redirects, so a redirect cannot carry the JWT elsewhere.
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                         .version(HttpClient.Version.HTTP_1_1)
                         .connectTimeout(TIMEOUT)
                         .followRedirects(HttpClient.Redirect.NEVER)
                         .build();
    }
}
