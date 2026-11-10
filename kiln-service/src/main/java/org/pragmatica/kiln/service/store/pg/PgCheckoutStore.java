package org.pragmatica.kiln.service.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.scan.ScanLine;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassRecord;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Transaction;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [CheckoutStore] over `checkout`, `stash`, `checkout_derived`, `non_git` and `host_pass` (§3.4: no failure makes a
/// count read 0). [#applyPass] runs in one transaction: it upserts the pass's rows, then **only for a COMPLETE outcome**
/// deletes the stored rows absent from it, and only those its absolute roots cover ([RootPattern]: a `~` root covers
/// nothing); an INCOMPLETE pass deletes nothing. [#passFailed] touches no row of any checkout table. A failed statement rolls the whole pass back.
record PgCheckoutStore(Db db) implements CheckoutStore {
    private static final String FACTS = "INSERT INTO checkout (host, path, common_dir, head_sha, branch, detached, remote_url, branches, modified, "
                                      + "untracked, op, activity_at, processes, maven, salvage_refs, facts_pass, facts_at, listed_at) "
                                      + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9, $10, $11, $12, $13::text[]::bigint[], $14, $15, $16, "
                                      + "$17, $17) ON CONFLICT (host, path) DO UPDATE SET common_dir = EXCLUDED.common_dir, "
                                      + "head_sha = EXCLUDED.head_sha, branch = EXCLUDED.branch, detached = EXCLUDED.detached, "
                                      + "remote_url = EXCLUDED.remote_url, branches = EXCLUDED.branches, modified = EXCLUDED.modified, "
                                      + "untracked = EXCLUDED.untracked, op = EXCLUDED.op, activity_at = EXCLUDED.activity_at, "
                                      + "processes = EXCLUDED.processes, maven = EXCLUDED.maven, salvage_refs = EXCLUDED.salvage_refs, "
                                      + "facts_pass = EXCLUDED.facts_pass, facts_at = EXCLUDED.facts_at, listed_at = EXCLUDED.listed_at";

    /// A stub line lists a checkout: its facts, if any, stay as the last full-facts pass left them.
    private static final String STUB = "INSERT INTO checkout (host, path, common_dir, activity_at, listed_at) VALUES ($1, $2, $3, $4, $5) "
                                     + "ON CONFLICT (host, path) DO UPDATE SET common_dir = EXCLUDED.common_dir, "
                                     + "activity_at = EXCLUDED.activity_at, listed_at = EXCLUDED.listed_at";

    private static final String STASH = "INSERT INTO stash (host, common_dir, count, observed_at) VALUES ($1, $2, $3, $4) "
                                      + "ON CONFLICT (host, common_dir) DO UPDATE SET count = EXCLUDED.count, observed_at = EXCLUDED.observed_at";

    private static final String NON_GIT = "INSERT INTO non_git (host, root, path, listed_at) VALUES ($1, $2, $3, $4) "
                                        + "ON CONFLICT (host, root, path) DO UPDATE SET listed_at = EXCLUDED.listed_at";

    private static final String PASS = "INSERT INTO host_pass (host, kind, started_at, finished_at, outcome, rows, expected, non_git) "
                                     + "VALUES ($1, $2, $3, $3, $4, $5, $6, $7) ON CONFLICT (host, kind) DO UPDATE SET "
                                     + "started_at = EXCLUDED.started_at, finished_at = EXCLUDED.finished_at, outcome = EXCLUDED.outcome, "
                                     + "rows = EXCLUDED.rows, expected = EXCLUDED.expected, non_git = EXCLUDED.non_git, "
                                     + "progress_k = NULL, progress_n = NULL";

    private static final String DELETE_CHECKOUTS = "DELETE FROM checkout WHERE host = $1 AND path = ANY($2::text[])";

    private static final String DELETE_NON_GIT = "DELETE FROM non_git n USING unnest($2::text[], $3::text[]) AS x(root, path) "
                                               + "WHERE n.host = $1 AND n.root = x.root AND n.path = x.path";

    /// A stash count is per common dir; once no checkout of the host shares the dir, nothing shows it.
    private static final String ORPHAN_STASH = "DELETE FROM stash s WHERE s.host = $1 AND NOT EXISTS "
                                             + "(SELECT 1 FROM checkout c WHERE c.host = s.host AND c.common_dir = s.common_dir)";

    /// Q-12: the current derived status ACTIVE, IN_USE or UNOWNED; activity inside the window; every UNSCANNED row; and
    /// a row whose `activityAt` moved past its facts (§3.2).
    private static final String HOT_SET = "SELECT c.path FROM checkout c" + CheckoutRows.DERIVED_JOIN
                                        + " WHERE c.host = $1 AND (c.facts_at IS NULL OR c.activity_at > c.facts_at OR c.activity_at >= $2 "
                                        + "OR d.status IN ('ACTIVE', 'IN_USE', 'UNOWNED')) ORDER BY c.path";

    /// Written only for a checkout that still exists: a derivation racing a deletion never resurrects a row.
    private static final String DERIVED = "INSERT INTO checkout_derived (host, path, status, preservation, reasons, at_base, linked_prs, link_rule, "
                                        + "facts_at, mirror_at, prs_at, derived_at) SELECT $1::text, $2::text, $3::text, $4::text, $5::text[], "
                                        + "$6::boolean, $7::text[]::bigint[], $8::text, $9::timestamptz, $10::timestamptz, $11::timestamptz, "
                                        + "$12::timestamptz WHERE EXISTS (SELECT 1 FROM checkout WHERE host = $1 AND path = $2) "
                                        + "ON CONFLICT (host, path) DO UPDATE SET status = EXCLUDED.status, preservation = EXCLUDED.preservation, "
                                        + "reasons = EXCLUDED.reasons, at_base = EXCLUDED.at_base, linked_prs = EXCLUDED.linked_prs, "
                                        + "link_rule = EXCLUDED.link_rule, facts_at = EXCLUDED.facts_at, mirror_at = EXCLUDED.mirror_at, "
                                        + "prs_at = EXCLUDED.prs_at, derived_at = EXCLUDED.derived_at";

    private static final String NEXT_TO_SIZE = "SELECT path FROM checkout WHERE host = $1 ORDER BY size_at ASC NULLS FIRST, path LIMIT 1";

    /// One pass as the store applies it: the outcome's lines, and `nonGit` only for a COMPLETE outcome.
    record Pass(HostName host,
                PassKind kind,
                List<String> roots,
                List<ScanLine> lines,
                PassOutcome outcome,
                Option<Integer> nonGit,
                Instant at) {
        static Pass pass(HostName host, PassKind kind, List<String> roots, ScanOutcome outcome, Instant at) {
            return switch (outcome) {
                case ScanOutcome.Complete(var rows, var nonGit, var _) -> new Pass(host,
                                                                                   kind,
                                                                                   roots,
                                                                                   rows,
                                                                                   PassOutcome.COMPLETE,
                                                                                   some(nonGit),
                                                                                   at);
                case ScanOutcome.Incomplete(var rows, var _) -> new Pass(host,
                                                                         kind,
                                                                         roots,
                                                                         rows,
                                                                         PassOutcome.INCOMPLETE,
                                                                         none(),
                                                                         at);
            };
        }

        /// The checkout paths the pass lists, with or without facts.
        Set<String> listed() {
            return lines.stream()
                        .flatMap(line -> listedPath(line).stream())
                        .collect(Collectors.toSet());
        }

        Set<NonGit> listedNonGit() {
            return lines.stream()
                        .flatMap(line -> nonGitOf(line).stream())
                        .collect(Collectors.toSet());
        }

        int rows() {
            return listed().size();
        }

        /// A COMPLETE pass's trailer count equals its rows (`ScanOutcome.of`).
        Option<Integer> expected() {
            return nonGit.map(_ -> rows());
        }

        String hostKey() {
            return host.value();
        }
    }

    /// One `non_git` key.
    record NonGit(String root, String path) {}

    @Override
    public Promise<PassRecord> applyPass(HostName host,
                                         PassKind kind,
                                         List<String> roots,
                                         ScanOutcome outcome,
                                         Instant at) {
        return db.inTransaction(tx -> applied(tx, Pass.pass(host, kind, roots, outcome, at)));
    }

    /// `reason` is the source state's (`Unreachable(reason)`, written by the caller); `host_pass` keeps no reason.
    @Override
    public Promise<Unit> passFailed(HostName host, PassKind kind, String reason, Instant at) {
        return Sql.execute(db.executor(),
                           PASS,
                           host.value(),
                           Rows.lower(kind),
                           Sql.utc(at),
                           PassOutcome.UNREACHABLE.name(),
                           0,
                           Sql.nullable(none()),
                           Sql.nullable(none()));
    }

    @Override
    public Promise<Unit> progress(HostName host, PassKind kind, int k, int n) {
        return Sql.execute(db.executor(),
                           "UPDATE host_pass SET progress_k = $3, progress_n = $4 WHERE host = $1 AND kind = $2",
                           host.value(),
                           Rows.lower(kind),
                           k,
                           n);
    }

    @Override
    public Promise<List<CheckoutRow>> rows(HostName host) {
        return Sql.query(db.executor(),
                         "SELECT " + CheckoutRows.COLUMNS + CheckoutRows.FROM + " WHERE c.host = $1 ORDER BY c.path",
                         host.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, CheckoutRows::row));
    }

    @Override
    public Promise<List<CheckoutPath>> hotSet(HostName host, Instant now, Duration window) {
        return Sql.query(db.executor(),
                         HOT_SET,
                         host.value(),
                         Sql.utc(now.minus(window)))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgCheckoutStore::path));
    }

    @Override
    public Promise<Unit> derived(HostName host, List<DerivedRow> rows) {
        return db.inTransaction(tx -> Sql.sequence(rows,
                                                   row -> derivedOne(tx, host, row)))
                 .mapToUnit();
    }

    @Override
    public Promise<Unit> size(HostName host, CheckoutPath path, long kb, Instant at) {
        return Sql.execute(db.executor(),
                           "UPDATE checkout SET size_kb = $3, size_at = $4 WHERE host = $1 AND path = $2",
                           host.value(),
                           path.value(),
                           kb,
                           Sql.utc(at));
    }

    @Override
    public Promise<Option<CheckoutPath>> nextToSize(HostName host) {
        return Sql.query(db.executor(),
                         NEXT_TO_SIZE,
                         host.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgCheckoutStore::path));
    }

    private static Promise<PassRecord> applied(Transaction tx, Pass pass) {
        return Sql.sequence(pass.lines(),
                            line -> write(tx, pass, line))
                  .flatMap(_ -> pruned(tx, pass))
                  .flatMap(_ -> recorded(tx, pass));
    }

    /// §3.4, K-4: only a COMPLETE pass deletes.
    private static Promise<Unit> pruned(Transaction tx, Pass pass) {
        return pass.outcome() == PassOutcome.COMPLETE
               ? deleteAbsent(tx, pass)
               : Promise.unitPromise();
    }

    private static Promise<Unit> deleteAbsent(Transaction tx, Pass pass) {
        var roots = RootPattern.patterns(pass.roots());

        return deleteAbsentCheckouts(tx, pass, roots).flatMap(_ -> deleteAbsentNonGit(tx, pass, roots))
                                    .flatMap(_ -> Sql.execute(tx,
                                                              ORPHAN_STASH,
                                                              pass.hostKey()));
    }

    private static Promise<Unit> deleteAbsentCheckouts(Transaction tx, Pass pass, List<Pattern> roots) {
        return Sql.query(tx,
                         "SELECT path FROM checkout WHERE host = $1",
                         pass.hostKey())
                  .map(resultSet -> absent(roots,
                                           pass.listed(),
                                           storedPaths(resultSet)))
                  .flatMap(absent -> Sql.execute(tx,
                                                 DELETE_CHECKOUTS,
                                                 pass.hostKey(),
                                                 Sql.texts(absent.stream())));
    }

    private static Promise<Unit> deleteAbsentNonGit(Transaction tx, Pass pass, List<Pattern> roots) {
        return Sql.query(tx,
                         "SELECT root, path FROM non_git WHERE host = $1",
                         pass.hostKey())
                  .map(resultSet -> absentNonGit(roots,
                                                 pass.listedNonGit(),
                                                 storedNonGit(resultSet)))
                  .flatMap(absent -> deleteNonGit(tx, pass, absent));
    }

    private static Promise<Unit> deleteNonGit(Transaction tx, Pass pass, List<NonGit> absent) {
        var roots = Sql.texts(absent.stream().map(NonGit::root));
        var paths = Sql.texts(absent.stream().map(NonGit::path));

        return Sql.execute(tx, DELETE_NON_GIT, pass.hostKey(), roots, paths);
    }

    /// The stored paths the roots cover and the pass does not list.
    static List<String> absent(List<Pattern> roots, Set<String> listed, List<String> stored) {
        return stored.stream()
                     .filter(path -> RootPattern.covered(roots, path))
                     .filter(Predicate.not(listed::contains))
                     .toList();
    }

    static List<NonGit> absentNonGit(List<Pattern> roots, Set<NonGit> listed, List<NonGit> stored) {
        return stored.stream()
                     .filter(entry -> RootPattern.covered(roots,
                                                          entry.path()))
                     .filter(Predicate.not(listed::contains))
                     .toList();
    }

    private static List<String> storedPaths(PgResultSet resultSet) {
        return Sql.rows(resultSet)
                  .stream()
                  .map(row -> row.getString("path"))
                  .toList();
    }

    private static List<NonGit> storedNonGit(PgResultSet resultSet) {
        return Sql.rows(resultSet)
                  .stream()
                  .map(row -> new NonGit(row.getString("root"),
                                         row.getString("path")))
                  .toList();
    }

    private static Promise<PassRecord> recorded(Transaction tx, Pass pass) {
        return Sql.execute(tx,
                           PASS,
                           pass.hostKey(),
                           Rows.lower(pass.kind()),
                           Sql.utc(pass.at()),
                           pass.outcome().name(),
                           pass.rows(),
                           Sql.nullable(pass.expected()),
                           Sql.nullable(pass.nonGit()))
                  .map(_ -> new PassRecord(pass.outcome(),
                                           pass.rows()));
    }

    private static Promise<Unit> write(Transaction tx, Pass pass, ScanLine line) {
        return switch (line) {
            case ScanLine.CheckoutLine(var facts) -> writeFacts(tx, pass, facts).flatMap(_ -> writeStash(tx, pass, facts));
            case ScanLine.StubLine(var path, var activityAt, var commonDir) -> Sql.execute(tx,
                                                                                           STUB,
                                                                                           pass.hostKey(),
                                                                                           path.value(),
                                                                                           commonDir,
                                                                                           Sql.utc(activityAt),
                                                                                           Sql.utc(pass.at()));
            case ScanLine.NonGitLine(var root, var path) -> Sql.execute(tx,
                                                                        NON_GIT,
                                                                        pass.hostKey(),
                                                                        root,
                                                                        path.value(),
                                                                        Sql.utc(pass.at()));
            case ScanLine.Trailer _ -> Promise.unitPromise();
        };
    }

    private static Promise<Unit> writeFacts(Transaction tx, Pass pass, CheckoutFacts facts) {
        return Json.write(CheckoutRows.json(facts.branches()))
                   .async()
                   .flatMap(branches -> upsertFacts(tx, pass, facts, branches));
    }

    private static Promise<Unit> upsertFacts(Transaction tx, Pass pass, CheckoutFacts facts, String branches) {
        return Sql.execute(tx,
                           FACTS,
                           pass.hostKey(),
                           facts.path().value(),
                           facts.commonDir(),
                           Sql.nullable(facts.head().map(Sha::value)),
                           Sql.nullable(facts.branch().map(BranchName::value)),
                           facts.branch().isEmpty(),
                           Sql.nullable(facts.remoteUrl()),
                           branches,
                           facts.modified(),
                           facts.untracked(),
                           Sql.nullable(facts.op()),
                           Sql.utc(facts.activityAt()),
                           Rows.longs(facts.processes()),
                           facts.maven(),
                           Sql.nullable(facts.salvageRefs()),
                           Rows.lower(pass.kind()),
                           Sql.utc(pass.at()));
    }

    private static Promise<Unit> writeStash(Transaction tx, Pass pass, CheckoutFacts facts) {
        return Sql.execute(tx,
                           STASH,
                           pass.hostKey(),
                           facts.commonDir(),
                           facts.stash(),
                           Sql.utc(pass.at()));
    }

    private static Promise<Unit> derivedOne(Transaction tx, HostName host, DerivedRow row) {
        return Sql.execute(tx,
                           DERIVED,
                           host.value(),
                           row.path().value(),
                           row.derived().status().name(),
                           CheckoutRows.preservation(row.derived().preservation()),
                           Sql.texts(CheckoutRows.reasons(row.derived().preservation()).stream()),
                           Sql.nullable(row.derived().atBase()),
                           PrRows.texts(row.derived().link().prs()),
                           row.derived().link().rule().name(),
                           Sql.nullable(row.factsAt().map(Sql::utc)),
                           Sql.nullable(row.mirrorAt().map(Sql::utc)),
                           Sql.nullable(row.prsAt().map(Sql::utc)),
                           Sql.utc(row.derivedAt()));
    }

    private static Option<String> listedPath(ScanLine line) {
        return switch (line) {
            case ScanLine.CheckoutLine(var facts) -> some(facts.path().value());
            case ScanLine.StubLine(var path, var _, var _) -> some(path.value());
            case ScanLine.NonGitLine _, ScanLine.Trailer _ -> none();
        };
    }

    private static Option<NonGit> nonGitOf(ScanLine line) {
        return switch (line) {
            case ScanLine.NonGitLine(var root, var path) -> some(new NonGit(root, path.value()));
            case ScanLine.CheckoutLine _, ScanLine.StubLine _, ScanLine.Trailer _ -> none();
        };
    }

    private static Result<CheckoutPath> path(PgRow row) {
        return CheckoutPath.checkoutPath(row.getString("path")).mapError(Rows.corrupt(CheckoutRows.TABLE));
    }
}
