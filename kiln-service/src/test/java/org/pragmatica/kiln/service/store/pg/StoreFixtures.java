package org.pragmatica.kiln.service.store.pg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.scan.ScanLine;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.core.summary.PrSnapshot;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Values for the store ITs, and [#DIFF]: a change rule of the ITs' own (production uses WP2's `Changes.between`), so
/// the store's transaction is pinned whatever the production rule decides.
final class StoreFixtures {
    static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    static final Instant T1 = T0.plusSeconds(60);
    static final Instant T2 = T0.plusSeconds(120);
    /// Where the fixture store's clock starts ([KilnPgFixture#ticking]): a day after the observations, so a change's
    /// `recordedAt` (the store's stamp) never equals its `observedAt` (the caller's) by accident.
    static final Instant RECORDED = T0.plusSeconds(86_400);
    /// The repository the store ITs record changes about (`[github] repo`).
    static final String REPO = "pragmaticalabs/pragmatica";

    /// A high-water file of its own (in a fresh temporary directory, not yet written), for one store.
    static Path highWater() {
        try {
            return Files.createTempDirectory("kiln-hwm").resolve("change-hwm");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    static final HostName BIGBOY = HostName.hostName("bigboy").unwrap();
    static final HostName CLOUD = HostName.hostName("cloudbb-3").unwrap();

    /// A `PR_STATE` change for every PR whose state differs from the stored one (absent reads ""), and a `CHECK_CELL`
    /// change for every cell whose value differs.
    static final ChangeDiff DIFF = StoreFixtures::diff;

    private StoreFixtures() {}

    /// A change as the rule produces it: `recordedAt` = `observedAt`, before the store stamps it.
    static Change change(Instant observedAt, long number, ChangeKind kind, String subject, String before, String after) {
        return new Change(observedAt, pr(number), kind, subject, before, after, observedAt);
    }

    /// `changes` with the store's `recordedAt` set back to `observedAt`, to compare with [#change]; the stamps are
    /// checked apart.
    static List<Change> observed(List<Change> changes) {
        return changes.stream().map(change -> change.recorded(change.observedAt())).toList();
    }

    static <T> T await(Promise<T> promise) {
        return promise.await(KilnPgFixture.WAIT).unwrap();
    }

    static Sha sha(char digit) {
        return Sha.sha(String.valueOf(digit).repeat(40)).unwrap();
    }

    static PrNumber pr(long number) {
        return PrNumber.prNumber(number).unwrap();
    }

    static BranchName branch(String name) {
        return BranchName.branchName(name).unwrap();
    }

    static CheckoutPath path(String raw) {
        return CheckoutPath.checkoutPath(raw).unwrap();
    }

    static PullRequest pullRequest(long number, PrState state, char head) {
        return new PullRequest(pr(number),
                               "PR " + number,
                               state,
                               false,
                               branch("main"),
                               branch("feature-" + number),
                               some("pragmaticalabs/pragmatica"),
                               sha(head),
                               state == PrState.MERGED ? some(T0) : none(),
                               state == PrState.OPEN ? none() : some(T0),
                               T0.plusSeconds(number),
                               List.of("kiln", "f1"),
                               List.of(pr(number + 100)),
                               List.of());
    }

    static CheckoutFacts facts(String path, String commonDir, int stash) {
        return new CheckoutFacts(path(path),
                                 some(sha('a')),
                                 some(branch("feature-1")),
                                 some("git@github.com:pragmaticalabs/pragmatica.git"),
                                 List.of(new BranchTip(branch("feature-1"), sha('a')), new BranchTip(branch("main"), sha('b'))),
                                 2,
                                 1,
                                 some("rebase"),
                                 T0,
                                 List.of(101L, 202L),
                                 true,
                                 commonDir,
                                 stash,
                                 some(3));
    }

    static ScanLine.CheckoutLine checkout(String path) {
        return new ScanLine.CheckoutLine(facts(path, path + "/.git", 0));
    }

    static ScanLine.StubLine stub(String path, Instant activityAt) {
        return new ScanLine.StubLine(path(path), activityAt, path + "/.git");
    }

    static ScanLine.NonGitLine nonGit(String root, String path) {
        return new ScanLine.NonGitLine(root, path(path));
    }

    /// Every row of `table` as JSON text, in a stable order: two dumps are equal only if no row was written.
    static List<String> dump(KilnStore store, String table) {
        var db = ((PgKilnStore) store).db();

        return Sql.rows(await(Sql.query(db.executor(), "SELECT to_jsonb(t)::text AS row FROM " + table + " t ORDER BY 1")))
                  .stream()
                  .map(row -> row.getString("row"))
                  .toList();
    }

    static long count(KilnStore store, String sql, Object... params) {
        var db = ((PgKilnStore) store).db();

        return await(Sql.query(db.executor(), sql, params)).index(0).getLong("n");
    }

    static void execute(KilnStore store, String sql) {
        await(Sql.execute(((PgKilnStore) store).db().executor(), sql));
    }

    /// Makes Postgres refuse every row of `table` whose `column` reads `value`: a write failure the store cannot avoid
    /// (a NUL byte no longer fails a write, [Sql#text]).
    static void refuse(KilnStore store, String table, String column, String value) {
        var executor = ((PgKilnStore) store).db().executor();

        await(executor.completeScript("CREATE FUNCTION refuse_" + table + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW."
                                      + column + "::text = '" + value + "' THEN RAISE EXCEPTION 'refused by the test'; END IF; "
                                      + "RETURN NEW; END $$; CREATE TRIGGER refuse_" + table + " BEFORE INSERT OR UPDATE ON " + table
                                      + " FOR EACH ROW EXECUTE FUNCTION refuse_" + table + "()").mapToUnit());
    }

    private static List<Change> diff(PrSnapshot before, PrSnapshot after, Instant observedAt) {
        var changes = new ArrayList<Change>();

        after.states().forEach((number, state) -> {
            var old = Option.option(before.states().get(number)).map(Enum::name).or("");
            if (!old.equals(state.name())) {
                changes.add(new Change(observedAt, number, ChangeKind.PR_STATE, number.toString(), old, state.name(), observedAt));
            }
        });
        after.cells().forEach((number, cells) -> cells.forEach((context, value) -> {
            var old = before.cells().getOrDefault(number, Map.of()).getOrDefault(context, "");
            if (!Objects.equals(old, value)) {
                changes.add(new Change(observedAt, number, ChangeKind.CHECK_CELL, context, old, value, observedAt));
            }
        }));
        return changes;
    }
}
