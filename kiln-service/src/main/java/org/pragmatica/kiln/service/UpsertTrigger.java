package org.pragmatica.kiln.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.store.BaseStore;
import org.pragmatica.kiln.service.store.ChangeStore;
import org.pragmatica.kiln.service.store.CheckStore;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.EtagStore;
import org.pragmatica.kiln.service.store.FeedStore;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.kiln.service.store.TemperStore;
import org.pragmatica.kiln.service.store.ViewStore;
import org.pragmatica.kiln.service.store.PipelineStore;
import org.pragmatica.kiln.service.store.WorkStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The store as the GitHub poller sees it: every PR upsert that wrote at least one PR runs `upserted` with its
/// observation time once it committed, the derivation's trigger (kiln-f1-design §3.1: `DeriveJob` runs after every PR
/// upsert; WP9 open item 11). Everything else is the store itself.
record UpsertTrigger(KilnStore store, Consumer<Instant> upserted) implements KilnStore {
    static KilnStore upsertTrigger(KilnStore store, Consumer<Instant> upserted) {
        return new UpsertTrigger(store, upserted);
    }

    @Override
    public PrStore prs() {
        return new TriggeredPrs(store.prs(), upserted);
    }

    @Override
    public CheckStore checks() {
        return store.checks();
    }

    @Override
    public SourceStore sources() {
        return store.sources();
    }

    @Override
    public CheckoutStore checkouts() {
        return store.checkouts();
    }

    @Override
    public ChangeStore changes() {
        return store.changes();
    }

    @Override
    public EtagStore etags() {
        return store.etags();
    }

    @Override
    public TemperStore temper() {
        return store.temper();
    }

    @Override
    public ViewStore views() {
        return store.views();
    }

    @Override
    public BaseStore bases() {
        return store.bases();
    }

    @Override
    public FeedStore feed() {
        return store.feed();
    }

    @Override
    public WorkStore work() {
        return store.work();
    }

    @Override
    public PipelineStore pipeline() {
        return store.pipeline();
    }

    @Override
    public Promise<Unit> close() {
        return store.close();
    }

    record TriggeredPrs(PrStore prs, Consumer<Instant> upserted) implements PrStore {
        @Override
        public Promise<List<Change>> upsert(List<PullRequest> pulls,
                                            Map<PrNumber, String> mergedBy,
                                            Instant observedAt) {
            return prs.upsert(pulls, mergedBy, observedAt)
                      .withSuccess(_ -> triggered(pulls, observedAt));
        }

        private void triggered(List<PullRequest> pulls, Instant observedAt) {
            if (!pulls.isEmpty()) {
                upserted.accept(observedAt);
            }
        }

        @Override
        public Promise<Unit> openDetail(PrNumber pr, OpenDetail detail, Instant observedAt) {
            return prs.openDetail(pr, detail, observedAt);
        }

        @Override
        public Promise<Unit> behind(PrNumber pr, Sha head, BranchName base, BehindFacts facts, Instant fetchedAt) {
            return prs.behind(pr, head, base, facts, fetchedAt);
        }

        @Override
        public Promise<List<PullRequest>> all() {
            return prs.all();
        }

        @Override
        public Promise<Option<Instant>> highWater() {
            return prs.highWater();
        }

        @Override
        public Promise<Unit> highWater(Instant at) {
            return prs.highWater(at);
        }

        @Override
        public Promise<Option<Instant>> backfillDone() {
            return prs.backfillDone();
        }

        @Override
        public Promise<Unit> backfillDone(Instant at) {
            return prs.backfillDone(at);
        }
    }
}
