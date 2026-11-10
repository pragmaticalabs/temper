package org.pragmatica.kiln.service;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.github.GhAnswer;
import org.pragmatica.kiln.service.github.GitHubHttp;
import org.pragmatica.kiln.service.github.PrFresh;
import org.pragmatica.kiln.service.http.ApiRequest;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// R2-7 at kiln's own wiring ([Kiln#fresh]): under a store spy, `GET /api/v1/pr-fresh` makes one store call, the
/// `base_tip` read (R2-7.a), and its GitHub port is never handed an ETag (R2-7.b).
class KilnFreshTest {
    private static final String SHA = "a".repeat(40);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<Option<String>> etags = new CopyOnWriteArrayList<>();

    /// Every call on a part of the store is recorded; the `base_tip` read answers none, anything else null.
    private KilnStore spy() {
        return (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                  new Class<?>[] {KilnStore.class},
                                                  (_, part, _) -> part.getReturnType().isInterface() && !part.getReturnType().equals(Promise.class)
                                                                  ? partSpy(part.getName(), part.getReturnType())
                                                                  : null);
    }

    private Object partSpy(String name, Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (_, method, args) -> recorded(name + "." + method.getName(), args));
    }

    private Object recorded(String call, Object[] args) {
        calls.add(call + "/" + (args == null ? 0 : args.length));

        return call.equals("feed.baseTip") && args.length == 2
               ? Promise.success(none())
               : null;
    }

    private Promise<GhAnswer> github(String path, Option<String> etag) {
        etags.add(etag);

        return Promise.success(GhAnswer.ghAnswer(200, Option.some("\"e\""), body(path), none(), none(), none(), none(), none()));
    }

    private static String body(String path) {
        if (path.endsWith("/pulls/2")) {
            return "{\"state\":\"open\",\"merged_at\":null,\"head\":{\"sha\":\"" + SHA + "\",\"ref\":\"f\",\"repo\":null},\"base\":{\"ref\":\"main\"},"
                   + "\"changed_files\":0}";
        }
        if (path.contains("/branches/main")) {
            return path.contains("/rules/") ? "[]" : "{\"protection\":{\"enabled\":false}}";
        }
        if (path.contains("check-runs")) {
            return "{\"total_count\":0,\"check_runs\":[]}";
        }
        if (path.contains("actions/runs")) {
            return "{\"total_count\":0,\"workflow_runs\":[]}";
        }
        return "[]";
    }

    @Test
    void prFresh_underAStoreSpy_readsOnlyTheBaseTip_andSendsNoEtag_r27ab() {
        GitHubHttp github = this::github;
        var ports = Kiln.Ports.ports(github, null, null, _ -> null, () -> Instant.parse("2026-11-04T10:00:00Z"));
        var kiln = Kiln.assemble(KilnWiringTest.config(List.of()), spy(), ports).unwrap();

        calls.clear();
        var response = kiln.fresh()
                           .answer(ApiRequest.apiRequest("GET", PrFresh.PATH, Map.of("repo", "o/r", "pr", "2", "sha", SHA), ""))
                           .await(TimeSpan.timeSpan(30).seconds())
                           .unwrap();

        assertThat(response.status()).isEqualTo(200);
        assertThat(calls).containsExactly("feed.baseTip/2");
        assertThat(etags).hasSize(9).allSatisfy(etag -> assertThat(etag.isEmpty()).isTrue());
    }
}
