package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.CheckoutView;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassView;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.PrView.LinkedCheckout;
import org.pragmatica.kiln.service.store.ViewStore;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Transaction;

import static org.pragmatica.kiln.core.model.Observed.observed;
import static org.pragmatica.lang.Option.option;


/// [ViewStore]: every count and page is a query over the tables, never over "the last pass" (§3.4), and every fact is
/// shown with its own observation time (D-4). A read of several statements runs in one read-only snapshot, so its parts
/// cannot disagree.
record PgViewStore(Db db) implements ViewStore {
    static final int PAGE_SIZE = 100;

    /// The open PRs, newest activity first, with the facts of their **current** head only: a mirror or gate fact about
    /// an older head is not shown as this head's, and a gate answer only when the sha temper named is the head or a
    /// prefix of it of 7 to 40 lowercase hex characters (`answer_sha`): an answer for another SHA shows no gate.
    private static final String OPEN = "SELECT " + PrRows.COLUMNS
                                     + ", p.mergeable, p.mergeable_state, p.head_committed_at, p.detail_at, "
                                     + "m.behind_by, m.behind_tree_empty, m.fetched_at, r.kind, r.enforcement, r.why, r.status, r.stacked, "
                                     + "r.checks::text AS checks, r.observed_at AS required_at, g.answer, g.answer_sha, g.job, g.judge, "
                                     + "g.schema_field, g.observed_at AS gate_at FROM pr p "
                                     + "LEFT JOIN pr_mirror m ON m.pr = p.number AND m.head_sha = p.head_sha "
                                     + "LEFT JOIN required_checks r ON r.base_ref = p.base_ref "
                                     + "LEFT JOIN temper_gate g ON g.pr = p.number AND g.head_sha = p.head_sha "
                                     + "AND (g.answer IS NULL OR (g.answer_sha ~ '^[0-9a-f]{7,40}$' AND starts_with(p.head_sha::text, g.answer_sha))) "
                                     + "WHERE p.state = 'open' ORDER BY p.updated_at DESC, p.number DESC";

    private static final String CELLS = "SELECT k.pr, k.context, k.app_id, k.value, k.foreign_runs, k.shown::text AS shown, k.observed_at "
                                      + "FROM check_cell k JOIN pr p ON p.number = k.pr AND p.head_sha = k.head_sha "
                                      + "WHERE p.state = 'open' ORDER BY k.pr, k.context, k.app_id NULLS FIRST";

    private static final String LINKED = "SELECT l.pr, d.host, d.path, d.status, d.derived_at FROM checkout_derived d "
                                       + "CROSS JOIN LATERAL unnest(d.linked_prs) AS l(pr) JOIN pr p ON p.number = l.pr "
                                       + "WHERE p.state = 'open' ORDER BY l.pr, d.host, d.path";

    private static final String COUNTS = "SELECT host, jsonb_object_agg(status, n)::text AS counts, sum(kb)::bigint AS kb, "
                                       + "sum(measured)::int AS measured, min(oldest) AS oldest "
                                       + "FROM (SELECT c.host, " + CheckoutRows.STATUS
                                       + " AS status, count(*) AS n, "
                                       + "coalesce(sum(c.size_kb), 0) AS kb, count(c.size_kb) AS measured, min(c.facts_at) AS oldest FROM checkout c" + CheckoutRows.DERIVED_JOIN
                                       + " GROUP BY 1, 2) g GROUP BY host ORDER BY host";

    private static final String FILTER = " WHERE ($1::text IS NULL OR " + CheckoutRows.STATUS
                                       + " = $1) AND ($2::text IS NULL OR c.host = $2)";

    private static final String COUNT = "SELECT count(*) AS n" + CheckoutRows.FROM + CheckoutRows.DERIVED_JOIN + FILTER;

    /// Sorted by idle time, the longest idle first [guess: the design says only "sorted by idle time"].
    private static final String PAGE = "SELECT " + CheckoutRows.COLUMNS
                                     + ", " + CheckoutRows.DERIVED_COLUMNS + CheckoutRows.FROM + CheckoutRows.DERIVED_JOIN + FILTER
                                     + " ORDER BY c.activity_at, c.host, c.path LIMIT " + PAGE_SIZE
                                     + " OFFSET $3";

    /// Every row under one of the statuses (`$1`, the names), on one host or all (`$2`), in the drill-down's order.
    private static final String LISTED = "SELECT " + CheckoutRows.COLUMNS
                                       + ", " + CheckoutRows.DERIVED_COLUMNS + CheckoutRows.FROM + CheckoutRows.DERIVED_JOIN
                                       + " WHERE " + CheckoutRows.STATUS
                                       + " = ANY($1::text[]) AND ($2::text IS NULL OR c.host = $2)"
                                       + " ORDER BY c.activity_at, c.host, c.path";

    private static final String PASSES = "SELECT host, kind, outcome, rows, expected, started_at, finished_at, progress_k, progress_n "
                                       + "FROM host_pass ORDER BY host, kind";

    private static final String RUNS = "SELECT scope, id, name, status, conclusion, head_sha, html_url, created_at, observed_at FROM base_run "
                                     + "ORDER BY scope, created_at DESC, id DESC";

    /// A value of one PR's list, keyed by its number.
    record Keyed<T>(long pr, T value) {}

    /// The cells and linked checkouts of the open PRs, by PR number.
    record Extras(Map<Long, List<Observed<Cell>>> cells, Map<Long, List<Observed<LinkedCheckout>>> linked) {
        List<Observed<Cell>> cellsOf(long pr) {
            return cells.getOrDefault(pr, List.of());
        }

        List<Observed<LinkedCheckout>> linkedOf(long pr) {
            return linked.getOrDefault(pr, List.of());
        }
    }

    @Override
    public Promise<List<PrView>> openPrs() {
        return inSnapshot(db, PgViewStore::openIn);
    }

    @Override
    public Promise<List<HostCounts>> counts() {
        return Sql.query(db.executor(),
                         COUNTS)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgViewStore::hostCounts));
    }

    @Override
    public Promise<Page<CheckoutView>> checkouts(Option<CheckoutStatus> status, Option<HostName> host, int page) {
        var statusName = status.map(Enum::name);
        var hostName = host.map(HostName::value);
        var first = Math.max(page, 1);

        return inSnapshot(db, tx -> pageIn(tx, statusName, hostName, first));
    }

    @Override
    public Promise<BaseView> bases() {
        return inSnapshot(db, PgViewStore::basesIn);
    }

    @Override
    public Promise<List<NonGitView>> nonGit(HostName host) {
        return Sql.query(db.executor(),
                         "SELECT root, path, listed_at FROM non_git WHERE host = $1 ORDER BY root, path",
                         host.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgViewStore::nonGitView));
    }

    @Override
    public Promise<Option<Observed<String>>> digest() {
        return Sql.query(db.executor(),
                         "SELECT text, observed_at FROM temper_digest WHERE id = 1")
                  .map(resultSet -> Sql.readFirst(resultSet, PgViewStore::digestText));
    }

    @Override
    public Promise<List<PassView>> passes() {
        return Sql.query(db.executor(),
                         PASSES)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgViewStore::passView));
    }

    @Override
    public Promise<List<CheckoutView>> listed(Set<CheckoutStatus> statuses, Option<HostName> host) {
        return Sql.query(db.executor(),
                         LISTED,
                         Sql.texts(statuses.stream().map(Enum::name)),
                         Sql.nullable(host.map(HostName::value)))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgViewStore::checkoutView));
    }

    /// The reads of one view in a `REPEATABLE READ, READ ONLY` transaction ([Sql#snapshot]).
    private static <T> Promise<T> inSnapshot(Db db, Fn1<Promise<T>, Transaction> reads) {
        return db.inTransaction(tx -> snapshotThen(tx, reads));
    }

    private static <T> Promise<T> snapshotThen(Transaction tx, Fn1<Promise<T>, Transaction> reads) {
        return Sql.snapshot(tx).flatMap(_ -> reads.apply(tx));
    }

    // ---- open PRs ----------------------------------------------------------------------------------------------------
    private static Promise<List<PrView>> openIn(Transaction tx) {
        return Sql.query(tx, CELLS)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgViewStore::keyedCell))
                  .flatMap(cells -> withLinked(tx, cells))
                  .flatMap(extras -> views(tx, extras));
    }

    private static Promise<Extras> withLinked(Transaction tx, List<Keyed<Observed<Cell>>> cells) {
        return Sql.query(tx, LINKED)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgViewStore::keyedLinked))
                  .map(linked -> new Extras(byPr(cells),
                                            byPr(linked)));
    }

    private static Promise<List<PrView>> views(Transaction tx, Extras extras) {
        return Sql.query(tx, OPEN).flatMap(resultSet -> Sql.parseAll(resultSet, row -> prView(row, extras)));
    }

    private static <T> Map<Long, List<T>> byPr(List<Keyed<T>> keyed) {
        return keyed.stream()
                    .collect(Collectors.groupingBy(Keyed::pr,
                                                   Collectors.mapping(Keyed::value,
                                                                      Collectors.toList())));
    }

    private static Result<PrView> prView(PgRow row, Extras extras) {
        return Result.all(PrRows.pullRequest(row),
                          required(row),
                          gate(row))
                     .map((pr, required, gate) -> new PrView(observed(pr,
                                                                      row.getInstant("observed_at")),
                                                             detail(row),
                                                             behind(row),
                                                             required,
                                                             extras.cellsOf(pr.number().value()),
                                                             gate,
                                                             extras.linkedOf(pr.number().value())));
    }

    private static Option<Observed<OpenDetail>> detail(PgRow row) {
        return Rows.instant(row, "detail_at").map(at -> observed(new OpenDetail(option(row.getBoolean("mergeable")),
                                                                                Rows.text(row, "mergeable_state"),
                                                                                Rows.instant(row, "head_committed_at")),
                                                                 at));
    }

    /// A NULL fact reads absent (`UNKNOWN`), never 0 (K-8).
    private static Option<Observed<BehindFacts>> behind(PgRow row) {
        return Rows.instant(row, "fetched_at").map(at -> observed(new BehindFacts(option(row.getInt("behind_by")),
                                                                                  option(row.getBoolean("behind_tree_empty"))),
                                                                  at));
    }

    private static Result<Option<Observed<RequiredChecks>>> required(PgRow row) {
        return Rows.flip(Rows.instant(row, "required_at").map(at -> requiredAt(row, at))).mapError(Rows.corrupt(CheckRows.REQUIRED_TABLE));
    }

    private static Result<Observed<RequiredChecks>> requiredAt(PgRow row, Instant at) {
        return CheckRows.requiredChecks(row).map(list -> observed(list, at));
    }

    private static Result<Option<Observed<GateRead>>> gate(PgRow row) {
        return Rows.flip(Rows.instant(row, "gate_at").map(at -> gateAt(row, at))).mapError(Rows.corrupt("temper_gate"));
    }

    private static Result<Observed<GateRead>> gateAt(PgRow row, Instant at) {
        return Rows.text(row, "answer")
                   .map(answer -> answered(row, answer))
                   .or(() -> schemaBroken(row))
                   .map(read -> observed(read, at));
    }

    private static Result<GateRead> answered(PgRow row, String answer) {
        return Rows.required(row, "answer_sha").map(sha -> new GateRead.Answered(sha,
                                                                                 answer,
                                                                                 option(row.getLong("job")),
                                                                                 Rows.text(row, "judge")));
    }

    private static Result<GateRead> schemaBroken(PgRow row) {
        return Rows.required(row, "schema_field").map(GateRead.SchemaBroken::new);
    }

    private static Result<Keyed<Observed<Cell>>> keyedCell(PgRow row) {
        return CheckRows.cell(row).map(cell -> new Keyed<>(row.getLong("pr"),
                                                           observed(cell, row.getInstant("observed_at"))));
    }

    private static Result<Keyed<Observed<LinkedCheckout>>> keyedLinked(PgRow row) {
        return Result.all(Rows.host(row.getString("host")),
                          CheckoutPath.checkoutPath(row.getString("path")),
                          Rows.constant(CheckoutStatus.class,
                                        row.getString("status")))
                     .map((host, path, status) -> new Keyed<>(row.getLong("pr"),
                                                              observed(new LinkedCheckout(host, path, status),
                                                                       row.getInstant("derived_at"))))
                     .mapError(Rows.corrupt(CheckoutRows.DERIVED_TABLE));
    }

    // ---- counts and pages --------------------------------------------------------------------------------------------
    private static Result<HostCounts> hostCounts(PgRow row) {
        return Result.all(Rows.host(row.getString("host")),
                          Json.read(row.getString("counts"),
                                    Json.COUNTS).flatMap(PgViewStore::statusCounts))
                     .map((host, counts) -> new HostCounts(host,
                                                           counts,
                                                           row.getLong("kb"),
                                                           row.getInt("measured"),
                                                           Rows.instant(row, "oldest")))
                     .mapError(Rows.corrupt(CheckoutRows.TABLE));
    }

    private static Result<Map<CheckoutStatus, Integer>> statusCounts(Map<String, Integer> raw) {
        return Result.allOf(raw.entrySet().stream().map(PgViewStore::statusCount).toList()).map(PgViewStore::asMap);
    }

    private static Result<Map.Entry<CheckoutStatus, Integer>> statusCount(Map.Entry<String, Integer> entry) {
        return Rows.constant(CheckoutStatus.class,
                             entry.getKey())
                   .map(status -> Map.entry(status,
                                            entry.getValue()));
    }

    private static Map<CheckoutStatus, Integer> asMap(List<Map.Entry<CheckoutStatus, Integer>> entries) {
        return entries.stream()
                      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Promise<Page<CheckoutView>> pageIn(Transaction tx,
                                                      Option<String> status,
                                                      Option<String> host,
                                                      int page) {
        return Sql.query(tx,
                         COUNT,
                         Sql.nullable(status),
                         Sql.nullable(host))
                  .map(resultSet -> resultSet.index(0)
                                             .getLong("n"))
                  .flatMap(total -> rowsOfPage(tx, status, host, page, total));
    }

    private static Promise<Page<CheckoutView>> rowsOfPage(Transaction tx,
                                                          Option<String> status,
                                                          Option<String> host,
                                                          int page,
                                                          long total) {
        return Sql.query(tx,
                         PAGE,
                         Sql.nullable(status),
                         Sql.nullable(host),
                         (long)(page - 1) * PAGE_SIZE)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgViewStore::checkoutView))
                  .map(rows -> new Page<>(rows,
                                          page,
                                          pages(total)));
    }

    /// At least one page, so an empty filter still has a page 1.
    static int pages(long total) {
        return (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static Result<CheckoutView> checkoutView(PgRow row) {
        return Result.all(CheckoutRows.row(row),
                          CheckoutRows.derived(row))
                     .map(CheckoutView::new);
    }

    // ---- bases, non-git, digest --------------------------------------------------------------------------------------
    private static Promise<BaseView> basesIn(Transaction tx) {
        return Sql.query(tx, RUNS)
                  .map(resultSet -> Sql.readAll(resultSet, PgViewStore::run))
                  .flatMap(runs -> withIssues(tx, runs));
    }

    private static Promise<BaseView> withIssues(Transaction tx, List<BaseView.Run> runs) {
        return Sql.query(tx,
                         "SELECT number, title, html_url, created_at, observed_at FROM gh_issue ORDER BY created_at DESC, number DESC")
                  .map(resultSet -> Sql.readAll(resultSet, PgViewStore::issue))
                  .map(issues -> new BaseView(runs, issues));
    }

    private static BaseView.Run run(PgRow row) {
        return new BaseView.Run(row.getLong("id"),
                                row.getString("scope"),
                                row.getString("name"),
                                row.getString("status"),
                                Rows.text(row, "conclusion"),
                                row.getString("head_sha"),
                                row.getString("html_url"),
                                row.getInstant("created_at"),
                                row.getInstant("observed_at"));
    }

    private static BaseView.Issue issue(PgRow row) {
        return new BaseView.Issue(row.getLong("number"),
                                  row.getString("title"),
                                  row.getString("html_url"),
                                  row.getInstant("created_at"),
                                  row.getInstant("observed_at"));
    }

    private static Result<NonGitView> nonGitView(PgRow row) {
        return CheckoutPath.checkoutPath(row.getString("path"))
                           .map(path -> new NonGitView(row.getString("root"),
                                                       path,
                                                       row.getInstant("listed_at")))
                           .mapError(Rows.corrupt("non_git"));
    }

    private static Result<PassView> passView(PgRow row) {
        return Result.all(Rows.host(row.getString("host")),
                          Rows.constant(PassKind.class,
                                        row.getString("kind")),
                          Rows.constant(PassOutcome.class,
                                        row.getString("outcome")))
                     .map((host, kind, outcome) -> new PassView(host,
                                                                kind,
                                                                outcome,
                                                                row.getInt("rows"),
                                                                option(row.getInt("expected")),
                                                                row.getInstant("started_at"),
                                                                Rows.instant(row, "finished_at"),
                                                                option(row.getInt("progress_k")),
                                                                option(row.getInt("progress_n"))))
                     .mapError(Rows.corrupt("host_pass"));
    }

    private static Observed<String> digestText(PgRow row) {
        return observed(row.getString("text"), row.getInstant("observed_at"));
    }
}
