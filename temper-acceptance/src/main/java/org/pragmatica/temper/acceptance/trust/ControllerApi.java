package org.pragmatica.temper.acceptance.trust;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.pragmatica.lang.Result.success;


/// The controller's read routes the T1/T2 tooling needs (m1-design §6, §8.1): `GET /jobs/{id}`, `/result`,
/// `/testcases`, `/manifest` and `/log?tail=`, bearer token attached. Anything but `200` is
/// [TrustError.UnexpectedStatus]; a connection failure is [TrustError.Io] with the JDK's failure as its origin.
public record ControllerApi(HttpClient client, String api, String token, JsonMapper json) {
    public static final String API_PATH = "/api/v1";
    private static final Pattern TRAILING = Pattern.compile("(/api/v1)?/*$");
    private static final int OK = 200;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /// `controller` is the controller's root (`http://host:port`), as the CLI's `TEMPER_URL` names it; a trailing
    /// `/api/v1` is accepted, as the CLI accepts it.
    public static ControllerApi controllerApi(URI controller, String token) {
        return new ControllerApi(HttpClient.newBuilder()
                                           .version(HttpClient.Version.HTTP_1_1)
                                           .connectTimeout(Duration.ofSeconds(10))
                                           .build(),
                                 root(controller) + API_PATH,
                                 token,
                                 TemperJson.mapper());
    }

    /// `http://host:port` of `controller`, any trailing `/api/v1` and slashes dropped.
    public static String root(URI controller) {
        return TRAILING.matcher(controller.toString()).replaceFirst("");
    }

    public Promise<JobView> job(long job) {
        return decoded("/jobs/" + job, JobView.class);
    }

    public Promise<ResultRecordDto> result(long job) {
        return decoded("/jobs/" + job + "/result", ResultRecordDto.class);
    }

    public Promise<List<TestcaseDto>> testcases(long job) {
        return decoded("/jobs/" + job + "/testcases", TestcaseDto[].class).map(ControllerApi::listed);
    }

    public Promise<ManifestView> manifest(long job) {
        return decoded("/jobs/" + job + "/manifest", ManifestView.class);
    }

    /// The unit's log tail through the controller's host link (`503` while the link is down).
    public Promise<String> log(long job, int lines) {
        return body("/jobs/" + job + "/log?tail=" + lines);
    }

    private static List<TestcaseDto> listed(TestcaseDto[] rows) {
        return List.of(rows);
    }

    private <T> Promise<T> decoded(String path, Class<T> type) {
        return body(path).flatMap(text -> decode(path, text, type).async());
    }

    private <T> Result<T> decode(String path, String text, Class<T> type) {
        return json.readString(text, type)
                   .mapError(origin -> TrustError.Io.FACTORY.apply("decode the answer of " + path, origin));
    }

    private Promise<String> body(String path) {
        return Network.parseURI(api + path)
                      .async()
                      .flatMap(uri -> sent(path, uri))
                      .flatMap(response -> ok(path, response).async());
    }

    private Promise<HttpResponse<String>> sent(String path, URI uri) {
        return Promise.lift(thrown -> TrustError.Io.FACTORY.apply("call " + api + path, Causes.fromThrowable(thrown)),
                            () -> client.send(request(uri), BodyHandlers.ofString()));
    }

    private HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                          .timeout(REQUEST_TIMEOUT)
                          .header("Authorization", "Bearer " + token)
                          .GET()
                          .build();
    }

    private static Result<String> ok(String path, HttpResponse<String> response) {
        return response.statusCode() == OK
               ? success(response.body())
               : TrustError.UnexpectedStatus.FACTORY.apply(path,
                                                           response.statusCode())
                                                    .result();
    }
}
