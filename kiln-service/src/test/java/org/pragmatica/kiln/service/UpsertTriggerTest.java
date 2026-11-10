package org.pragmatica.kiln.service;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// [UpsertTrigger] (WP9 open item 11): a PR upsert that wrote a PR triggers the derivation with its observation time,
/// once committed; an empty or failed upsert triggers nothing; every other store part and every other PR operation is
/// the store's own, the very object it answers.
class UpsertTriggerTest {
    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();

    private final List<Instant> triggered = new CopyOnWriteArrayList<>();
    private final Map<String, Object> answered = new ConcurrentHashMap<>();
    private volatile boolean fails;

    /// Each method answers its own sentinel, so a delegation is told apart from any other answer.
    private Object sentinel(Method method) {
        return answered.computeIfAbsent(method.getName() + method.getParameterCount(),
                                        _ -> Promise.class.isAssignableFrom(method.getReturnType())
                                             ? Promise.success(method.getName())
                                             : Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {method.getReturnType()}, (proxy, own, args) -> switch (own.getName()) {
                                                 case "equals" -> proxy == args[0];
                                                 case "hashCode" -> System.identityHashCode(proxy);
                                                 case "toString" -> method.getName();
                                                 default -> null;
                                             }));
    }

    private final PrStore prs = (PrStore) Proxy.newProxyInstance(PrStore.class.getClassLoader(), new Class<?>[] {PrStore.class},
                                                                 (_, method, _) -> method.getName().equals("upsert")
                                                                                   ? (fails ? Causes.cause("tx failed").promise() : Promise.success(List.of()))
                                                                                   : sentinel(method));
    private final KilnStore store = (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(), new Class<?>[] {KilnStore.class},
                                                                       (_, method, _) -> method.getName().equals("prs") ? prs : sentinel(method));

    private static PullRequest pr() {
        return new PullRequest(PrNumber.prNumber(1).unwrap(), "pr", PrState.OPEN, false, BranchName.branchName("main").unwrap(),
                               BranchName.branchName("b").unwrap(), none(), Sha.sha("a".repeat(40)).unwrap(), none(), none(), T0,
                               List.of(), List.of(), List.of());
    }

    @Test
    void upsert_ofAPr_triggersWithItsObservationTime_onlyOnSuccess() {
        var triggering = UpsertTrigger.upsertTrigger(store, triggered::add);

        triggering.prs().upsert(List.of(pr()), T0).await(WAIT).unwrap();
        triggering.prs().upsert(List.of(), T0.plusSeconds(1)).await(WAIT).unwrap();
        fails = true;
        assertThat(triggering.prs().upsert(List.of(pr()), T0.plusSeconds(2)).await(WAIT).isFailure()).isTrue();

        assertThat(triggered).containsExactly(T0);
    }

    @Test
    void everyOtherPart_isTheStoresOwn() throws Exception {
        var triggering = UpsertTrigger.upsertTrigger(store, triggered::add);

        assertThat(List.<Object>of(triggering.checks(), triggering.sources(), triggering.checkouts(), triggering.changes(),
                                   triggering.etags(), triggering.temper(), triggering.views(), triggering.bases(), triggering.close()))
            .containsExactly(store.checks(), store.sources(), store.checkouts(), store.changes(), store.etags(), store.temper(),
                             store.views(), store.bases(), store.close())
            .doesNotContainNull();
        assertThat(triggering.prs()).isNotSameAs(prs);
    }

    @Test
    void everyOtherPrOperation_isTheStoresOwn() {
        var triggered = UpsertTrigger.upsertTrigger(store, this.triggered::add).prs();
        var number = PrNumber.prNumber(1).unwrap();
        var head = Sha.sha("a".repeat(40)).unwrap();
        var main = BranchName.branchName("main").unwrap();

        assertThat(List.<Object>of(triggered.openDetail(number, null, T0), triggered.behind(number, head, main, null, T0), triggered.all(),
                                   triggered.highWater(), triggered.highWater(T0), triggered.backfillDone(), triggered.backfillDone(T0)))
            .containsExactly(prs.openDetail(number, null, T0), prs.behind(number, head, main, null, T0), prs.all(), prs.highWater(),
                             prs.highWater(T0), prs.backfillDone(), prs.backfillDone(T0))
            .doesNotContainNull();
        assertThat(this.triggered).isEmpty();
    }
}
