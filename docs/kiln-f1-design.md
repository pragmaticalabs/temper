# kiln F1: implementation design

_Status: draft 1, 2026-10-29. Implements the PR-tracker spec draft 2 with errata
E-1..E-3 (the "spec"; its section numbers are cited as §n). The spec is authoritative. Where this design found a gap
or a contradiction in it, the item is listed in §9 with a proposed answer, and the design is written to that proposal,
marked **[Q-n]**. A choice the inputs do not determine is marked **[guess]**. Rulings: the maintainer._

Settled inputs: the name is `kiln`. Its modules live in this repo and are built by this reactor, and kiln is deployed
as its own process under its own systemd user unit on bigboy. GitHub is the only durability authority (O-5). Reads are
unauthenticated on 127.0.0.1 (O-6). The GitHub token is read by path from `~/.config/kiln/github-token`; when it is
absent, kiln runs unauthenticated. F2–F4 are out of scope, and the schema must not block them (§7.3).

Probe facts used here:
- P-1: every §4 endpoint answers 200 unauthenticated.
- P-2: a 304 costs about 0 of the rate limit. Whether that holds unauthenticated is open: [Q-7].
- P-3 on bigboy:

  | Measured | Time |
  |---|---|
  | one checkout | 0.07 s |
  | `/proc` cwd sweep | 0.3 s |
  | hot set (181 checkouts) | 18.6 s |
  | full set (993 checkouts) | 124.5 s |

  The hot set fits the ≤ 60 s budget, so the 48 h window stays as it is. The build state during the measurement was
  not recorded: [Q-13].

## 1. Modules

| Module | Packaging | Depends on | Holds |
|---|---|---|---|
| `kiln-core` | jar | pragmatica `core`, `jackson` | Pure domain: value objects, the lenient decoders (GitHub, temper gate, scan lines), every §4.3/§4.4/§5.3 rule, the change diff, the summary text. No I/O, no clock, no threads |
| `kiln-service` | jar + shaded `-all` (runnable) | `kiln-core`; `temper-protocol` (only `protocol.exec`: `ProcessRunner`, `Command`, `Completed`) **[Q-9]**; pragmatica `toml`, `jackson`, `postgres-async`; `logback-classic` | Config, kiln-pg store and migrations, the GitHub/temper HTTP clients, the mirror's git adapter, host scanning over ssh, the pollers, the HTTP server, the dashboard and the API, `Main` |
| `ops/kiln/` (not a module) | files | — | `kiln-scan`, `kiln-ingest` (bash), `kiln.service`, `kiln.toml.example` |

- **The root `pom.xml`** gains `<module>kiln-core</module>` and `<module>kiln-service</module>` in the default reactor:
  - `.github/workflows/ci.yml` runs `mvn install` from the root, so CI builds and tests kiln with no change;
  - `ops/dev/hooks/pre-commit` finds the jbct-checked modules by `<module>` (line 22).
- **jbct:** both modules inherit the root's `jbct-maven-plugin` `check` execution and `jbct.skip=false` (`pom.xml:35`,
  `pom.xml:180-192`). Nothing is added per module. Each coder quotes "Running JBCT check on N Java file(s)" per module.
- **Shade:** `kiln-service` copies the shade execution of `temper-controller/pom.xml:125-162`: attached as `-all` with
  `Main-Class org.pragmatica.kiln.service.Main`, so the main jar stays thin.
- **Surefire:** `kiln-service` copies the it-pg tier of `temper-store/pom.xml:59-73`. It includes `*Test` and `*IT`,
  and the docker ITs skip (reported as skipped) without docker (`PgFixture.java:29`).
- **What kiln does not copy from temper:** the controller's `http` package (`Router` checks bearer tokens before
  routing, `Router.java:41-45`; `HttpGlue` adds the version gate). The store's `pg` package is package-private
  (`Db`, `Sql`, `MigrationRunner`), so kiln **copies** `Db`, `PgDb`, `Sql` and `MigrationRunner` into
  `org.pragmatica.kiln.service.store.pg` with two changes:
  - resources under `kiln/db/migration/V<n>__<name>.sql`, never `db/migration/`, so no temper migration can ever be
    found on kiln's classpath;
  - advisory lock `7171`, not `7070`.
- **Guard test (WP1), `NoTemperJsonTest`:** no `kiln-*` class (main or test, read from its constant pool, so every
  source spelling of a name is caught) refers to `org.pragmatica.temper.protocol.json` or `org.pragmatica.temper.store`,
  and no `kiln-*` pom depends on `temper-store` (§4.5: kiln never decodes with `TemperJson`; D-1: kiln never touches
  temper's store).
  - Probe: add one reference, in any spelling, and the test goes red.

### 1.1 `kiln-core` packages and main types (signature level)

Factories follow JBCT: `TypeName.typeName(raw) → Result<TypeName>`, and every value object is a record built only by
its factory.

| Package | Type | Signature / meaning |
|---|---|---|
| `kiln.core.model` | `Sha` | `Result<Sha> sha(String)`: 40 lowercase hex |
| | `PrNumber` | `Result<PrNumber> prNumber(long)`: ≥ 1 |
| | `BranchName` | `Result<BranchName> branchName(String)`: not blank, no control characters, no `..`, never starts with `-` |
| | `HostName` | `Result<HostName> hostName(String)`: `^[a-z0-9][a-z0-9-]{0,31}$`; `mac` is reserved for the ingest |
| | `CheckoutPath` | `Result<CheckoutPath> checkoutPath(String)`: absolute, no `..`, no control characters |
| | `CheckContext`, `AppId` | a required check's context; a GitHub app id |
| | `Observed<T>` | `record Observed<T>(T value, Instant observedAt)`. Every fact kiln shows travels in one (D-4) |
| `kiln.core.source` | `Source` | sealed: `GitHub`, `Mirror`, `Temper`, `Host(HostName)`, `Mac`. The key is `github`, `mirror`, `temper`, `host:<name>` or `mac` |
| | `SourceState` | sealed: `Ok(Instant at)`, `Unreachable(String reason, Instant since)`, `Incomplete(String reason, Instant at)`, `RateLimited(Instant reset, Instant since)`, `GithubDown(String status, Instant since)`, `TemperDown(String reason, Instant since)`, `TemperSchema(String field, Instant since)` |
| | `SourceStatus` | `(Source, SourceState, Option<Instant> lastSuccess)`, with `boolean staleAt(Instant now, Duration period)`, which is older than 3 × the period (§6) |
| `kiln.core.github` | `PullRequest` | `(PrNumber, String title, PrState, boolean draft, BranchName base, BranchName headRef, Option<String> headRepo, Sha head, Option<Instant> mergedAt, Option<Instant> closedAt, Instant updatedAt, List<String> labels, List<PrNumber> closes, List<PrNumber> partOf)`; `boolean sameRepo(String baseRepo)` |
| | `PrState` | `OPEN`, `CLOSED`, `MERGED` (`merged_at` set ⇒ `MERGED`) |
| | `PullsPage` | `Result<PullsPage.Page> decode(String json, String repo)` (`repo`: the configured `owner/name`, for `ClosingRefs`); `Page(List<PullRequest> pulls, List<Skipped> skipped)`, `Skipped(PrNumber number, String reason)`: a PR whose base or head ref is no `BranchName` is skipped, never failing the page (WP6 shows `skipped #N: <reason>`). Lenient: unknown fields are ignored |
| | `ClosingRefs` | `ClosingRefs parse(String body, String repo)`: the closing keywords as GitHub links them (`(?i)\b(close[sd]?|fix(e[sd])?|resolve[sd]?):? (owner/repo)?#(\d+)(?!\w)`, `owner/repo` only when it is `repo`, case-insensitively; code blocks and inline code not read) and `Part of #N` |
| | `OpenDetail` | `(Option<Boolean> mergeable, Option<String> mergeableState, Option<Instant> headCommittedAt)` |
| | `BranchRead` | `Result<BranchRead> decode(String json)`: `enabled`, `enforcementLevel`, `checks` (falling back to `contexts[]` with `app_id = null`, §4.3) |
| | `RequiredCheck` | `(CheckContext context, Option<AppId> appId)` |
| | `RequiredChecks` | sealed: `Required(List<RequiredCheck>, String enforcement)`, `NoneRequired(String why, boolean stacked)`, `Unknown(String status)` |
| | | `static RequiredChecks resolve(Result<BranchRead> branch, Result<List<RequiredCheck>> rulesets, boolean configured)`: the §4.3 table; rulesets only **add** checks to a `Required` list; a check is the pair `{context, app_id}`, an identical pair kept once, a context pinned differently by the two kept as both |
| | `CheckRun`, `CommitStatus` | decoded check runs (`id, name, app.id, status, conclusion, started_at`) and statuses (`id, context, state, description, updated_at`) |
| | `Cell` | `(RequiredCheck check, String value, int foreign, List<String> shown)`; `boolean isSuccess()` is `value.equals("success")`, the only success (§4.4) |
| | `CheckCells` | `List<Cell> cells(RequiredChecks, List<CheckRun>, List<CommitStatus>)`: the §4.4 selection and mapping. `Unknown` gives one `UNKNOWN` cell per known context; `NoneRequired` gives none |
| `kiln.core.temper` | `GateRead` | sealed: `Answered(String sha, String answer, Option<Long> job, Option<String> judge)`, `SchemaBroken(String field)` |
| | | `static GateRead decode(String body)`: requires only `sha` and `answer` (E-1) and ignores unknown fields |
| `kiln.core.scan` | `ScanLine` | sealed: `CheckoutLine(CheckoutFacts)`, `StubLine(CheckoutPath, Instant activityAt, String commonDir)`, `NonGitLine(String root, CheckoutPath)`, `Trailer(int count, int nonGit, Option<Long> nonGitBytes, Option<String> home)`; `static Result<ScanLine> decode(String line)` |
| | `CheckoutFacts` | path, HEAD (`Option<Sha>`), branch or detached, remote URL, `List<BranchTip>`, modified, untracked, `Option<String> op`, `activityAt`, `List<Long> processes`, `boolean maven`, commonDir, stash (of the common dir), `Option<Integer> salvageRefs` |
| | `ScanOutcome` | sealed: `Complete(List<ScanLine> rows, int nonGit)`, `Incomplete(List<ScanLine> rows, String reason)` |
| | | `static ScanOutcome of(List<String> lines)`: `Complete` only when the **last** line is a trailer whose `count` equals the number of checkout and stub lines. A missing trailer, a count mismatch, or an undecodable line gives `Incomplete` (§5.2). Decoding stops at the first undecodable line: `Incomplete` keeps only the rows before it, so the work is bounded by the good rows (CI OOM ruling, WP9) |
| `kiln.core.checkout` | `Reach` | `MIRROR`, `HOST_BARE`, `NOWHERE`, `UNKNOWN` (no `reach` answer: a Mac row, or the host failed) |
| | `Preservation` | sealed: `Preserved`, `HostBareOnly`, `Unpreserved(List<String> reasons)`; `static Preservation of(CheckoutFacts, Map<Sha, Reach>)` (§5.3, W4) |
| | `Ancestry` | `(boolean headInMirror, boolean atBase, Set<PrNumber> headsContainingHead, Set<Sha> offBaseCommitsOfHead)`, computed by the mirror adapter (§3.3) |
| | `PrIndex` | `static PrIndex prIndex(Collection<PullRequest> all, String baseRepo)`: the **full** table, with forks excluded |
| | `PrLink` | `(List<PrNumber> prs, LinkRule rule)`; `static PrLink link(CheckoutFacts, Ancestry, PrIndex)`: rules 1 > 2 > 3 with the tie-breaks (§5.3) |
| | `CheckoutStatus` | `ACTIVE`, `IN_USE`, `REMOVABLE`, `BASE_CLEAN`, `NEEDS_SALVAGE`, `STALE_UNOWNED`, `UNOWNED`, `UNSCANNED` |
| | `Derivation` | `static Derived derive(Option<CheckoutFacts>, Map<Sha, Reach>, Ancestry, PrIndex, Duration staleAfter, Instant now)`. `Derived` carries the status, preservation, link, `atBase` and the observation times of every input used |
| `kiln.core.summary` | `Change` | `(Instant observedAt, PrNumber pr, ChangeKind kind, String subject, String before, String after)`; kinds `CHECK_CELL` and `PR_STATE` |
| | `Changes` | `List<Change> between(PrSnapshot before, PrSnapshot after, Instant observedAt)` |
| | `SummaryText` | `Summary render(Instant since, Instant asOf, List<SourceStatus>, List<Change>, Counts, Option<Instant> oldestKept)`, which lists the changes with `since < observedAt ≤ asOf` in ≤ 25 lines (past 25 only for one overflowing instant or many sources not OK) and gives the `asOf` **[Q-6]**. `asOf` is the caller's committed watermark (WP5/WP9: the store's low watermark), never a bare `now` |

### 1.2 `kiln-service` packages

| Package | Main types | Based on |
|---|---|---|
| `kiln.service` | `Main`, `Kiln` (assembly: every dependency passed in; `start() → Promise<Running>`; `Running.stop()`) | `controller/Controller.java:80-218`, `controller/Main.java` |
| `kiln.service.config` | `KilnConfig`, `KilnConfigLoader` (`Result<KilnConfig> parse(TomlDocument, Path home)`, with every failure fatal at start), `TomlValues` (copied) | `controller/config/ConfigLoader.java:66-91`, `TomlValues.java` |
| `kiln.service.store` | `KilnStore` and its sub-store interfaces (§3.5); `pg/`: `PgKilnStore`, plus copies of `Db`, `PgDb`, `Sql`, `MigrationRunner` | `temper-store/.../pg/MigrationRunner.java`, `Db.java`, `PgDb.java`, `Sql.java`, `api/DbConfig.java` |
| `kiln.service.github` | `GitHubHttp` (port), `JdkGitHubHttp` (ETag, rate-limit headers, body cap, whole-exchange timeout), `GitHubPoller` | `controller/git/GitHubPulls.java:139-179` (timeout, body cap, `settle`) |
| `kiln.service.mirror` | `MirrorGit` (port), `GitMirror` (`git` CLI, argv only, `GIT_TERMINAL_PROMPT=0`, serialised fetch) | `controller/git/GitCli.java:49-71`, `:156-169`, `:195-212`, `:345-361` |
| `kiln.service.temper` | `TemperHttp` (port), `JdkTemperHttp` (bearer from the read-token file), `TemperReader` | `cli/transport/HttpTransport.java` |
| `kiln.service.scan` | `ScanTransport` (port), `SshScanTransport` (ssh argv as `SshCommand`), `LocalScanTransport` (runs `ops/kiln/kiln-scan` with `SSH_ORIGINAL_COMMAND` set, for tests), `HostScanner` (hot, full and size passes), `MacIngest` | `controller/host/SshCommand.java:31-53`, `controller/flow/Serial.java` |
| `kiln.service.derive` | `DeriveJob`: gathers reach and ancestry, calls `Derivation.derive`, and writes the derived rows | — |
| `kiln.service.http` | `LoopbackServer` (JDK `com.sun.net.httpserver.HttpServer` on `InetAddress.getLoopbackAddress()`, virtual-thread executor) **[Q-1]**, `Route`, `Router` (no credentials), `ApiRequest`, `ApiResponse` | `controller/http/Route.java:93-125` (matching), `Router.java:98-103` (a failure degrades to its status) |
| `kiln.service.api` | `ApiRoutes` (§7 routes), `Views` (JSON records) | `controller/api/DigestRoutes.java` (`format=text`) |
| `kiln.service.web` | `Dashboard`, `CheckoutPages`, `Html` (escaping, no JS framework, `<meta http-equiv=refresh content=30>`) | — |
| `kiln.service.loop` | `Periodic` (`SharedScheduler.scheduleAtFixedRate`, single-flight: a tick is skipped while the previous run is in flight, and the skip is counted), `Daily` (local time of day) | `controller/task/TaskSweeper.java:57-60`, `controller/maintenance/DumpSchedule.java:66-143` |

## 2. kiln-pg schema

There is one migration, `kiln-service/src/main/resources/kiln/db/migration/V1__f1_schema.sql`. A later file is
`V<n>__<milestone>_<what>.sql`, as temper names its own (`temper-store/.../db/migration/`), and it is listed in the
copied `MigrationRunner.MIGRATIONS`. Every F1 table is a **cache**: dropping the database loses nothing (§1, F1). The exception, added later, is the work
ledger (V5 `work_item`, `work_event`, the dashboard's Work tab): nothing rebuilds it, so before dropping or recreating
kiln-pg take a data-only dump (V5 creates the tables, so a dump with `CREATE TABLE` would clash on restore):
`docker exec kiln-pg pg_dump --data-only -U kiln -t work_item -t work_event kiln > work-ledger.sql`. Restore into the fresh kiln-pg: start kiln once so its
migrations apply V5, stop it before any event is recorded (`systemctl --user stop kiln`), load the rows (the dump
carries the `work_event` id sequence's value too) with `docker exec -i kiln-pg psql -U kiln -d kiln -v ON_ERROR_STOP=1 < work-ledger.sql`, then start kiln.

| Table | Columns (PK first) | Written by | Rule |
|---|---|---|---|
| `source_state` | `source text`, `state text`, `detail text`, `since timestamptz`, `last_success_at timestamptz NULL`, `updated_at timestamptz` | every poller | one row per source key; a success sets `last_success_at` |
| `gh_etag` | `url text`, `etag text`, `body text`, `observed_at` | `JdkGitHubHttp` | the last 200 body for each URL, replayed on a 304 |
| `gh_cursor` | `name text`, `value text` | `GitHubPoller` | `backfill_done` (when the backfill finished), `pulls_high_water`; both ISO instants (`PrStore.backfillDone`, `highWater`) |
| `pr` | `number int`, `title`, `state text CHECK (state IN ('open','closed','merged'))`, `draft bool`, `base_ref`, `head_ref`, `head_repo text NULL`, `head_sha char(40)`, `merged_at NULL`, `closed_at NULL`, `updated_at`, `labels text[]`, `closes int[]`, `part_of int[]`, `mergeable bool NULL`, `mergeable_state text NULL`, `head_committed_at NULL`, `observed_at` | `GitHubPoller` | **never deleted**. Indexes on `head_ref` and `head_sha` |
| `required_checks` | `base_ref text`, `kind text` (`REQUIRED`, `NONE_REQUIRED`, `UNKNOWN`), `enforcement text NULL`, `why text NULL`, `status text NULL`, `stacked bool`, `checks jsonb` (`[{context, appId}]`), `observed_at` | `GitHubPoller` | rewritten every cycle; a failed read writes `UNKNOWN(status)` |
| `check_cell` | `(pr int, context text, app_id bigint NULL)` (unique on `(pr, context, COALESCE(app_id, -1))`: one context under two apps, or pinned and not, is two cells; WP5, lead ruling R-A), `head_sha char(40)`, `value text`, `foreign int`, `shown jsonb`, `observed_at` | `GitHubPoller` | the cells of the current head only. A new head with a failed check read gets `UNKNOWN` cells, never the old head's |
| `pr_mirror` | `pr int`, `head_sha`, `base_ref`, `behind_by int NULL`, `behind_tree_empty bool NULL`, `fetched_at` | mirror step | `NULL` reads `UNKNOWN`, never 0 (§5.1) |
| `temper_gate` | `pr int`, `head_sha`, `answer text NULL`, `job bigint NULL`, `judge text NULL`, `schema_field text NULL`, `observed_at` | `TemperReader` | `schema_field` set ⇒ `TEMPER-SCHEMA(field)` for that PR |
| `temper_digest` | `id smallint` (= 1), `text text`, `observed_at` | `TemperReader` | stored verbatim, never decoded |
| `base_run` | `id bigint`, `scope text` (a base, or `schedule`), `name`, `status`, `conclusion NULL`, `head_sha`, `html_url`, `created_at`, `observed_at` | `GitHubPoller` | a successful read replaces its scope's set; a failed one keeps the old set with its age |
| `gh_issue` | `number int`, `title`, `html_url`, `created_at`, `observed_at` | `GitHubPoller` | as `base_run` |
| `checkout` | `(host text, path text)`, `common_dir`, `head_sha NULL`, `branch NULL`, `detached bool NULL`, `remote_url NULL`, `branches jsonb NULL`, `modified int NULL`, `untracked int NULL`, `op text NULL`, `activity_at`, `processes bigint[] NULL`, `maven bool NULL`, `salvage_refs int NULL`, `facts_pass text NULL` (`hot`, `full`, `mac`), `facts_at NULL`, `listed_at`, `size_kb bigint NULL`, `size_at NULL` | `HostScanner`, `MacIngest` | `facts_at IS NULL` ⇒ `UNSCANNED` **[Q-5]**. Deleted only by `applyPass` with a `Complete` outcome from a reachable host, under an absolute root (§3.4) |
| `stash` | `(host, common_dir)`, `count int`, `observed_at` | same | one count per common dir (§5.2) |
| `checkout_derived` | `(host, path)`, `status text`, `preservation text`, `reasons text[]`, `at_base bool NULL`, `linked_prs int[]`, `link_rule text NULL`, `facts_at`, `mirror_at`, `prs_at`, `derived_at` | `DeriveJob` | the times of the inputs are kept, so a status always shows the age of the fact it came from (§5.6) |
| `non_git` | `(host, root, path)`, `listed_at` | `HostScanner`, `MacIngest` | replaced only by a `Complete` pass **[Q-4]** |
| `host_pass` | `(host, kind)`, `started_at`, `finished_at NULL`, `outcome text` (`COMPLETE`, `INCOMPLETE`, `UNREACHABLE`, `RUNNING`), `rows int`, `expected int NULL`, `non_git int NULL`, `progress_k int NULL`, `progress_n int NULL` | `HostScanner`, `MacIngest` | the last pass of each kind; `mac` uses kind `push` (its `finished_at` is `pushedAt`) |
| `change` | `id bigserial`, `observed_at`, `kind text`, `pr int`, `subject text` (`#n`; a check is its `RequiredCheck.key()`: `context`, or `context` U+001F `appId`; shown as its label), `before text`, `after text`, `recorded_at` | `CheckStore`, `PrStore`, in the **same transaction** as the write that changed the value | `observed_at` is the caller's observation (shown); `recorded_at` is the summary cursor, stamped by the store when the write begins (lead ruling R-B). Index on `recorded_at`; rows recorded over 30 days ago are pruned daily (§7) |

## 3. Process structure

### 3.1 Loops

| Loop | Period | Does | Source it sets |
|---|---|---|---|
| `GitHubPoller` | 60 s, stretched when the rate budget demands it **[Q-7]** | backfill once (§4.1), then: pulls (ETag, paged while `updated_at` ≥ high water) → per open PR, `pulls/{n}` (mergeable) and, once per head SHA, `commits/{sha}` (date) → `branches/{b}` and `rules/branches/{b}` per configured base, plus per stacked base for this cycle → check runs and statuses per open head (paged) → runs per base, scheduled runs, issues | `github`: `Ok`, `RateLimited(reset)` (a 403 or 429 with `X-RateLimit-Remaining: 0`, or a budget below the cycle's need), `GithubDown(status)` |
| mirror step | right after each GitHub cycle, and at least every 60 s | `git fetch --prune` with the refspecs of `GitCli.java:197-207` (`refs/heads/*`, `refs/tags/*`, `refs/pull/*/head`) into `~/.local/share/kiln/mirror.git` (cloned `--mirror` once), then `behindBy` and `behindTreeEmpty` per open PR | `mirror` |
| `TemperReader` | 60 s **[guess; Q-11]** | `GET /api/v1/digest?format=text&max=10` (verbatim), then `GET /api/v1/gate/{sha}` per open head, with the bearer token from `[temper] readTokenFile` | `temper`: connect failure, 5xx, 401 or 403 → `TemperDown(reason)`; any `SchemaBroken` this cycle → `TemperSchema(field)` |
| `HostScanner` hot | 2 min per host | `scan` with the hot set on stdin (§3.2) | `host:<name>` |
| `HostScanner` full | daily at 04:00 local (`Daily`) | `scan` in chunks of 100 paths, giving progress `k of N` | `host:<name>` |
| `HostScanner` size | continuous: one `size` every 5 s, null sizes first, then the oldest | `size <path>` | none (only `size_at`) |
| `DeriveJob` | after every pass, mirror fetch and PR upsert, coalesced | reach (`reach` for bigboy and cloud tips the mirror lacks) + ancestry → `Derivation.derive` → `checkout_derived` | — |
| `MacIngest` | on `POST /api/v1/ingest/mac` | the same `applyPass` as a host, with `host = mac` and no `reach` or `size` | `mac` |
| retention | daily | prunes `change` rows over 30 days old | — |

Each loop is single-flight, and the loops of one host share one `Serial`, so one host never sees two kiln ssh sessions
at once. A full pass therefore delays at most one hot tick per chunk.

### 3.2 `kiln-scan` verbs as kiln calls them **[Q-2, Q-3]**

| `SSH_ORIGINAL_COMMAND` | stdin | Emits |
|---|---|---|
| `scan <root>...` | optional: newline-separated checkout paths. Empty stdin means every checkout gets full facts | a full `CheckoutLine` for each listed path (every checkout when stdin is empty); a `StubLine` (path, `activityAt`, common dir: three `stat`s) for every other checkout under the roots; a `NonGitLine` per non-git directory; then the trailer `{"end":true,"count":N,"nonGit":M,"nonGitBytes":null,"home":"<cd ~ && pwd -P>"}`, where `N` counts checkout and stub lines and `home` (absent from older scripts) expands a `~` root |
| `reach <sha>...` (≤ 200) | — | `{"sha":…,"ref":<first ref or null>}` per SHA, then a trailer |
| `size <path>` | — | `{"path":…,"kb":N}` |
| anything else | — | exit 64 |

Rules for the script:
- **Roots** are words of the form `/abs/...` or `~/...`. `*` may appear in any component after the first and matches
  exactly one component; `**`, a `*` in the first component (`/*`, `/a*`), and a `.`, `..` or empty component are
  refused (§9.2). The script expands `~` and the glob itself.
- **Hard exclusions** (`~/work/pragmatica.git`, `~/work/m2-seed`, `~/.temper/jobs/*`, and any match that is the parent
  directory of another root given in the same call) are in the script, not in kiln's config.
- **Mac form:** `kiln-scan scan <root>...` with argv instead of `SSH_ORIGINAL_COMMAND`, used only when
  `SSH_ORIGINAL_COMMAND` is unset.

Hot set: checkouts whose **current** derived status is `ACTIVE`, `IN-USE` or `UNOWNED`, or whose `activityAt` is under
48 h, plus every `UNSCANNED` row **[Q-12]**. The stub lines of a hot pass find new directories and changed
`activityAt`; a row whose `activityAt` moved past its `facts_at` joins the next hot set.

### 3.3 Mirror queries (`MirrorGit`)

Every reachability query names its ref patterns: `refs/heads`, `refs/tags`, `refs/pull/*/head`. A `--mirror` clone
also holds `refs/pull/*/merge`, which no fetch refreshes, so that namespace is never read (`GitCli.java:156-158`).

| Method | git | Answer |
|---|---|---|
| `Promise<Unit> fetch()` | `fetch --prune --quiet origin` (3 refspecs); `clone --mirror` when absent | — |
| `Promise<BehindFacts> behind(Sha head, BranchName base)` | `cat-file -e head^{commit}`; `merge-base head base`; `rev-list --count B..base`; `diff --quiet B base` | both values `UNKNOWN` when the head is absent (§5.1, K-8) |
| `Promise<Map<Sha, Boolean>> reachable(Set<Sha>)` | `cat-file -e` first (a missing object is unreachable without another call), then `for-each-ref --contains <sha> --count=1` over the 3 patterns | memoised per fetch generation |
| `Promise<Ancestry> ancestry(Sha head, List<BranchName> bases)` | `atBase`: `for-each-ref --contains head refs/heads/<base>…`; `headsContainingHead`: `for-each-ref --contains head refs/pull/*/head`; `offBaseCommitsOfHead`: `rev-list head --not <bases> --max-count=500` | `PrLink` rule 3 = PR heads containing HEAD, plus PR heads within `offBaseCommitsOfHead`. Both satisfy "the linking commits are on no base": a descendant of an off-base commit is itself off base |

### 3.4 The rule that no failure makes a count read 0

| Mechanism | Where | Pinned by |
|---|---|---|
| `pr` is never deleted. A failed GitHub read writes only `source_state` | `PrStore.upsert` has no delete path | K-13 |
| `CheckoutStore.applyPass` deletes absent rows **only** for a `Complete` outcome. The deletion is limited to the ABSOLUTE roots of that call; a root still starting with `~` deletes nothing, so WP8 expands `~` from the scan trailer's `home` (WP4) first (WP5 verification ruling). One pass per host at a time. `Incomplete` upserts the rows it has and deletes nothing | `PgKilnStore` | K-4 |
| A transport failure (ssh exit 255, a timeout, a nonzero rc, an empty stream) is a **failure**, never an empty `Complete` outcome. It writes `Unreachable(reason)` and touches no row | `HostScanner` | K-5 |
| Counts and pages are queries over the tables, never over "the last pass". Each count line carries the oldest `facts_at` it rests on | `ViewStore` | K-5 (counts unchanged) |
| A `NULL` mirror fact reads `UNKNOWN` | `pr_mirror` | K-8 |
| `UNKNOWN` required lists and cells are never green, and their cells are not counted as success | `CheckCells` | K-6 |

Sources are held twice: in memory (an `AtomicReference<Map<Source, SourceStatus>>` that the dashboard reads), and in
`source_state` (so a restart shows the last known state with its age).

### 3.5 Store ports (defined in WP1, implemented in WP5)

```java
interface KilnStore { PrStore prs(); CheckStore checks(); SourceStore sources(); CheckoutStore checkouts();
                      ChangeStore changes(); EtagStore etags(); TemperStore temper(); ViewStore views(); }
interface PrStore {        Promise<List<Change>> upsert(List<PullRequest> prs, Instant observedAt);   // inserts PR_STATE changes, never deletes
                           Promise<Unit> openDetail(PrNumber pr, OpenDetail detail, Instant observedAt);
                           Promise<Unit> behind(PrNumber pr, Sha head, BranchName base, BehindFacts facts, Instant fetchedAt);
                           Promise<List<PullRequest>> all();                                           // the full table (K-13)
                           Promise<Option<Instant>> highWater(); Promise<Unit> highWater(Instant at);
                           Promise<Option<Instant>> backfillDone(); Promise<Unit> backfillDone(Instant at); }  // WP5
interface CheckStore {     Promise<Unit> required(BranchName base, RequiredChecks list, Instant observedAt);
                           Promise<List<Change>> cells(PrNumber pr, Sha head, List<Cell> cells, Instant observedAt); }  // CHECK_CELL changes, same tx
interface SourceStore {    Promise<Unit> record(Source source, SourceState state); Promise<List<SourceStatus>> all(); }
interface CheckoutStore {  Promise<PassRecord> applyPass(HostName host, PassKind kind, List<String> roots, ScanOutcome outcome, Instant at);
                           Promise<Unit> passFailed(HostName host, PassKind kind, String reason, Instant at);
                           Promise<List<CheckoutRow>> rows(HostName host);
                           Promise<List<CheckoutPath>> hotSet(HostName host, Instant now, Duration window);
                           Promise<Unit> derived(HostName host, List<DerivedRow> rows);
                           Promise<Unit> size(HostName host, CheckoutPath path, long kb, Instant at);
                           Promise<Option<CheckoutPath>> nextToSize(HostName host); }
interface ChangeStore {    Instant watermark();                                  // WP5, R-B: every change with recordedAt ≤ it is committed
                           Promise<List<Change>> since(Instant since, int limit);  // recordedAt > since
                           Promise<Option<Instant>> oldest(); Promise<Unit> prune(Instant before); }
interface EtagStore {      Promise<Option<Cached>> cached(String url); Promise<Unit> store(String url, String etag, String body, Instant at); }
interface TemperStore {    Promise<Unit> digest(String text, Instant at); Promise<Unit> gate(PrNumber pr, Sha head, GateRead read, Instant at); }
interface ViewStore {      Promise<List<PrView>> openPrs(); Promise<List<HostCounts>> counts();
                           Promise<Page<CheckoutView>> checkouts(Option<CheckoutStatus> status, Option<HostName> host, int page);
                           Promise<BaseView> bases(); Promise<List<NonGitView>> nonGit(HostName host); Promise<Option<String>> digest(); }
```

Service ports between WPs:

```java
interface GitHubHttp   { Promise<GhAnswer> get(String pathAndQuery, Option<String> etag); }  // GhAnswer(status, etag, body, remaining, reset, next)
interface MirrorGit    { /* §3.3 */ }
interface TemperHttp   { Promise<String> digestText(); Promise<GateRead> gate(Sha head); }
interface ScanTransport{ Promise<List<String>> scan(List<String> roots, List<CheckoutPath> full);
                         Promise<List<String>> reach(Set<Sha> shas); Promise<List<String>> size(CheckoutPath path); }
```

## 4. HTTP surface

The server binds `127.0.0.1:<[server] port>`, and, only if `[server] bind` is set, that one IPv4 address on the same port, read-only (§7) **[Q-1]**. There are no credentials and no version gate.
Bodies are capped at 8 MiB **[guess]**.

| Route | Answers |
|---|---|
| `GET /` | the dashboard (§6 panels 1–6) |
| `GET /checkouts?status=&host=&page=` | a drill-down page: 100 rows, sorted by idle time, with sizes |
| `GET /non-git?host=&root=` | the non-git list |
| `GET /api/v1/prs`, `/api/v1/checkouts`, `/api/v1/sources` | the §7 JSON. Every value is an `Observed` (`observedAt`) |
| `GET /api/v1/summary?since=&format=text` | `SummaryText` **[Q-6]**: a `gap` line first when `since` is older than the oldest kept change |
| `POST /api/v1/ingest/mac` | scan lines → `MacIngest`. Answers `{"outcome":"COMPLETE"|"INCOMPLETE","rows":N}` |

## 5. Scripts

- **Shell:** both are bash, `set -euo pipefail`, and bash 3.2 compatible with BSD tools, structured like
  `ops/worker-launch` (`die <code>`, verbs read from `SSH_ORIGINAL_COMMAND`, an exactness check, exit 64 on refusal,
  `worker-launch:385-422`) **[Q-8]**.
- **`kiln-scan` (WP4):**
  - every git call runs as `GIT_OPTIONAL_LOCKS=0 git -c core.hooksPath=/dev/null …`;
  - it runs under `nice -n 19`, plus `ionice -c3` only when `command -v ionice` finds it (E-2);
  - it writes nothing;
  - process probe: Linux reads `/proc/[0-9]*/cwd` once per call for the caller's uid; macOS runs one
    `lsof -a -d cwd -u "$USER" -Fn` (`uname -s`). Maven = `/proc/<pid>/cmdline` or `ps -o command= -p` naming
    `org.codehaus.plexus.classworlds.launcher.Launcher`;
  - `activityAt` reads `$(git rev-parse --git-dir)/{HEAD,index,logs/HEAD}`, so worktrees work;
  - `salvageRefs` = `git for-each-ref refs/salvage | wc -l`;
  - the host bare is `$HOME/work/pragmatica.git`; when it is absent, `reach` answers `null` for every SHA.
- **`kiln-ingest` (WP8):**
  - it refuses a non-empty `SSH_ORIGINAL_COMMAND` and any argument other than `mac` (64);
  - it reads stdin up to 8 MiB into a private temp file (`umask 077`, removed by a trap), then runs
    `curl -sS --fail-with-body --data-binary @file http://127.0.0.1:${KILN_PORT:-7171}/api/v1/ingest/mac`;
  - its exit code is curl's.

## 6. Deployment (bigboy, user `builder`)

| Item | Path / command | Who |
|---|---|---|
| Jar | `install -D -m 0644 kiln-service/target/kiln-service-<ver>-all.jar ~/.kiln/dist/kiln-service.jar` | builder |
| Unit | `ops/kiln/kiln.service` → `~/.config/systemd/user/kiln.service`; `ExecStart=/usr/bin/env java -Xmx512m -jar %h/.kiln/dist/kiln-service.jar %h/.config/kiln/kiln.toml`; `Restart=on-failure`; `UnsetEnvironment=HCLOUD_TOKEN`. A copy of `ops/temper-controller.service`; linger is already enabled for temper | builder |
| kiln-pg | `TEMPER_PG_NAME=kiln-pg TEMPER_PG_VOLUME=kiln-pg TEMPER_PG_PORT=55442 TEMPER_PG_DB=kiln TEMPER_PG_USER=kiln TEMPER_PG_PASSWORD_FILE=~/.config/kiln/pg-password ops/pg-up.sh`. `pg-up.sh` gains the two variables `TEMPER_PG_DB` and `TEMPER_PG_USER` (default `temper`; today they are hard-coded at `pg-up.sh:43,54`) **[Q-10]**. Port 55442 **[guess]** | builder; first run on maintainer approval (the `pg-up.sh` header asks for it) |
| Config | `~/.config/kiln/kiln.toml` (`ops/kiln/kiln.toml.example`): `[server] port = 7171` [guess]; `[db]` …; `[github] repo, bases = ["release-1.0.0-rc5","main"], tokenFile` (optional: absent means unauthenticated); `[mirror] origin, path`; `[temper] url = "http://127.0.0.1:7070", readTokenFile = "~/.config/temper/read-token"`; `[scan] hotSeconds = 120, hotWindowHours = 48, fullAt = "04:00", sizeGapSeconds = 5, staleAfterHours = 48`; `[[hosts]] name address user key knownHosts roots` | builder |
| Secrets | `~/.config/kiln/pg-password` (0600, created by builder); `~/.config/kiln/github-token` (0600) | the PAT is an **OWNER action** (O-2) |
| Scan key | `ssh-keygen -t ed25519 -N '' -f ~/.ssh/kiln_scan` on bigboy | builder |
| Scan script | `install -D -m 0755 ops/kiln/kiln-scan ~/tools/kiln-scan` on bigboy, cloudbb-3 and cloudbb-4; the sha256 is printed and recorded in the deploy record | builder |
| Scan forced command, bigboy | `authorized_keys`: `restrict,from="127.0.0.1,::1",command="exec ~/tools/kiln-scan" ssh-ed25519 … kiln-scan`. `restrict` covers the spec's `no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding` and more, as `worker-launch:5` does | **OWNER action** |
| Scan forced command, cloud hosts | the same line without `from=` | **OWNER action** |
| Ingest key | Mac: `ssh-keygen -t ed25519 -N '' -f ~/.ssh/kiln_ingest`. bigboy: `install -m 0755 ops/kiln/kiln-ingest ~/tools/kiln-ingest`, then `authorized_keys`: `restrict,command="exec ~/tools/kiln-ingest mac" ssh-ed25519 … kiln-ingest` (`from=` **[Q-16]**) | key: maintainer; `authorized_keys`: **OWNER action** |
| Mac push | `~/tools/kiln-scan scan '~/src/pragmatica*' ~/src/ticketing '/private/tmp/scratch-501/*/*/work/*' \| ssh -i ~/.ssh/kiln_ingest <user>@<bigboy>` | a scheduled sweep, or the operator |
| Token holders | the token inventory gains "kiln" as a reader of `read-token` (O-8) | maintainer |

## 7. Work packages

Each WP is one coder (~120 tool calls) on its own branch off the previous WP's merge, and follows `BRIEF-RULES.md`.
Every K test names a positive and a negative control, and its mutation probe is recorded in the WP's state file.

### 7.1 List and dependencies

| WP | Scope | Depends on | Delivers (interface to later WPs) |
|---|---|---|---|
| **WP1** Skeleton and contracts | both poms and the root `<module>` lines; every `kiln.core.model` and `kiln.core.source` type with tests; the signatures of §1.1 as types (records, sealed interfaces) with **stub bodies** that fail (`Causes.cause("not built").result()`); the store and service ports of §3.5; `KilnConfig` and its loader with tests; `NoTemperJsonTest` | — | compiling contracts that WP2–WP10 code against |
| **WP2** GitHub rules (core) | `PullsPage`, `ClosingRefs`, `BranchRead`, `RequiredChecks.resolve`, `CheckRun`, `CommitStatus`, `CheckCells`, `Changes.between` | WP1 | pure functions |
| **WP3** Checkout rules (core) | `ScanLine.decode`, `ScanOutcome.of`, `Preservation`, `PrIndex`, `PrLink`, `Derivation`, `GateRead.decode` | WP1 | pure functions; the scan-line format (§3.2) as a decoder |
| **WP4** `kiln-scan` | `ops/kiln/kiln-scan` (`scan`, `reach`, `size`); `KilnScanIT` in `kiln-service`, which runs the real script (`Path.of("..","ops","kiln","kiln-scan")`, as `DeployJarsIT.java:41-42`) on fixture repos built in `@TempDir` with `HOME` redirected | WP1, WP3 (decoder; the script may start in parallel with WP3) | the script, and `LocalScanTransport` |
| **WP5** Store | `V1__f1_schema.sql`; copies of `MigrationRunner`, `Db`, `PgDb`, `Sql`; `PgKilnStore` for every port of §3.5; `KilnPgFixture` (copy of `PgFixture`); `pg-up.sh` with `TEMPER_PG_DB` and `TEMPER_PG_USER` | WP1 | the PG store + a test fixture |
| **WP6** HTTP sources | `JdkGitHubHttp` (ETag via `EtagStore`, rate limit, paging by `Link`), `GitHubPoller` (backfill, incremental, budget), `JdkTemperHttp`, `TemperReader`; `GitHubFake` (JDK `HttpServer` serving canned pages, counting 200s and 304s) | WP2, WP3 (`GateRead`), WP5 | the pollers |
| **WP7** Mirror | `GitMirror` (§3.3) on fixture origin repos: a local bare as the "GitHub" origin, with `refs/pull/N/head` created by `update-ref` | WP1 | `MirrorGit` |
| **WP8** Scan pipeline | `SshScanTransport`, `HostScanner` (hot, full, size; chunking; `Serial`), `MacIngest` (logic), `kiln-ingest` script, `DeriveJob` | WP3, WP4, WP5, WP7 | the passes and the derivation |
| **WP9** Assembly and HTTP | `LoopbackServer`, `Router`, `ApiRoutes` (incl. summary, ingest), `Kiln`, `Main`, the shade config, `ops/kiln/kiln.service`, `kiln.toml.example`; `LoopbackOnlyIT`; K-15 | WP6, WP8, WP10 | the deployable jar |
| **WP10** Dashboard | `Dashboard`, `CheckoutPages`, `Html` over `ViewStore` records: pure rendering with golden-file tests (panel red at 3 × the period, `as of` lines, Mac always in full, `UNKNOWN` never green) | WP5 | HTML renderers |

Waves (≤ 3 coders at a time):

| Wave | WPs |
|---|---|
| W0 | WP1 |
| W1 | WP2, WP3, WP5 |
| W2 | WP4, WP6, WP7 |
| W3 | WP8, WP10 |
| W4 | WP9 |

### 7.2 Acceptance tests: one WP each

| K | WP | Test class | Fixture / fake | Mutation that must turn it red |
|---|---|---|---|---|
| K-1 | WP8 | `PreservationPipelineIT` | fixture repos in `@TempDir`, scanned by the real `kiln-scan` through `LocalScanTransport`; a local bare origin fetched into a real `GitMirror`; a second bare at `$HOME/work/pragmatica.git` for `HOST-BARE-ONLY`; `git worktree add` for the sibling-stash case; `git rebase` stopped on a conflict; a detached HEAD on a pushed `archive/x` tag | each rule in turn: drop the stash term; ignore `op`; stop counting tags as preserving; treat `HOST_BARE` as `MIRROR`. Each turns one row red |
| K-2 | WP3 | `SquashMergeLinkTest` | facts: HEAD not on base, `Ancestry.atBase=false`, PR state `MERGED`, every tip `MIRROR` | derive merged from `atBase` (ancestry) instead of `PrState` |
| K-3 | WP4 | `KilnScanReadOnlyIT` | (a) `chmod -R a-w .git`, then `scan` facts equal to the writable run; (b) 50 scans while a background `git commit` loop runs: 0 commit failures naming `index.lock`; (c) **deterministic arm**: touch a tracked file so the index is stat-dirty, then a scan leaves every file under `.git` byte- and mtime-identical (snapshot before and after) | drop `GIT_OPTIONAL_LOCKS=0`: arm (c) goes red every time (`git status` refreshes the index). Arm (b) alone can miss the race, which is why (c) exists |
| K-4 | WP8 | `IncompleteScanIT` | a `ScanTransport` fake that answers a complete listing, then the same listing with no trailer, then with `count` off by one; PG via `KilnPgFixture` | remove the trailer check in `ScanOutcome.of`: the truncated listing deletes rows |
| K-5 | WP8 | `UnreachableHostIT` | a real `sshd` in docker (alpine + openssh + bash + git, copying `controller/host/Sshd.java:29-60`) with `kiln-scan` as the forced command; scan once, stop the container, scan again | `HostScanner` maps a transport failure to `ScanOutcome.Complete(List.of(), 0)`: rows are deleted and the counts read 0 |
| K-6 | WP2 | `RequiredChecksTest` | `Result.failure(Status 403)` → `UNKNOWN(403)`; `enforcement_level: off` → `NONE-REQUIRED(off)`; rulesets `[]` with 9 classic checks → `REQUIRED(9, non_admins)` | resolve from the rulesets alone, or map an empty list to success |
| K-7 | WP2 | `SkipStatusTest` | status `success` with description `"skipped"` → `success*`, not `isSuccess()` | remove the `(?i)skip` check |
| K-8 | WP7 | `BehindTreeEmptyIT` | the base ahead by two `git commit --allow-empty` → `true`; by one real commit → `false`; a head SHA absent from the mirror → `UNKNOWN` | replace `diff --quiet` by `behindBy == 0` |
| K-9 | WP3 | `PrLinkTest` | branch > SHA > ancestry; a checkout at the base tip → no PR; a stacked chain → all PRs; a branch name reused by a closed and an open PR → the open one | swap rules 1 and 2; drop the `atBase` exclusion |
| K-10 | WP6 | `EtagCycleIT` | `GitHubFake`: a cycle, then a second cycle with nothing changed | drop `If-None-Match`: the second cycle's 200 count is > 0 |
| K-11 | WP2 | `CheckSelectionTest` | a rerun (two runs; the later `started_at` decides, ties broken by `id`); a same-named run from another app → `foreign=1`; `startup_failure` → verbatim | each selection rule removed in turn |
| K-12 | WP6 | `LenientGateIT` | a temper fake (JDK `HttpServer`): a gate answer with an extra field → `Answered`; a NONE answer without `job` and `judge` → `Answered` (E-1); one without `answer` → `TEMPER-SCHEMA(answer)`, while the source is not `TEMPER-DOWN` | fail on unknown properties, or require `judge` |
| K-13 | WP6 | `FullTableLinkIT` | `GitHubFake` serving 1,001 PRs over 11 pages, `#1` closed and oldest; backfill into PG; then `Derivation.derive` for a preserved checkout on `#1`'s branch → `REMOVABLE` | cap the backfill at 10 pages (a window) |
| K-14 | WP4 | `KilnScanProbeIT` | Linux: a `sleep` child with its cwd inside the fixture → `processes` holds its PID. macOS branch: a fake `uname` (prints `Darwin`) and a fake `lsof` (prints `p<pid>`/`n<cwd>`) first on `PATH`. Plus one manual run of the script on the Mac (bash, no Maven), recorded as evidence **[Q-14]**. The ACTIVE leg is rule 1 of `CheckoutStatus`, pinned by `DerivationTest` (WP3) | remove either probe: the matching case reads no PID |
| K-15 | WP9 | `KilnLiveSmokeIT` (tag `host`, `-Pacceptance` and `-Dkiln.live`, in `temper-acceptance`, beside its host tier) | the real GitHub with the real token; `gh pr list --state open --json number --jq length` in the same minute **[Q-15]**; the rc5 list reads `REQUIRED(9, non_admins)` | — (a live smoke) |

Further tests per WP (not K items, still required):
- WP1: config loader refusals.
- WP5: `KilnMigrationIT` (a gap, tampering, an empty schema) and a `PgKilnStore` contract IT for every port.
- WP9: `LoopbackOnlyIT`. A connection to the host's first non-loopback address is refused; with no such address the
  test is skipped, never passed. Probe: bind the wildcard address.
- WP10: the golden HTML files.

### 7.3 F2–F4 are not blocked (§10)

| Later need | What F1 leaves for it |
|---|---|
| F2 Claim → Checkout | `checkout (host, path)` is a stable natural key, so F2 adds `claim(…, host, path)` without touching F1 tables |
| F2 WorkItem → PR | `pr` is complete and kept forever |
| F2 salvage | `salvage_refs` is already a column; `size_kb` gives the disk recovery figure |
| F3 | its own tables, with no reference into the cache |
| F4 | acceptance needs the head's tree hash. `GitMirror` gains `treeOf(Sha)`, and a column is added to `pr_mirror` by `V2` |
| Backups | F1 tables are all cache. F2's first non-cache table brings `pg_dump`, which needs the `kiln-pg` container name; `[pgDump] container` follows `ConfigLoader.java:276-281` |

## 8. Guarantees this design adds to §8

| Operation | Guarantee | Mechanism |
|---|---|---|
| Server reachability | only processes that can connect to 127.0.0.1 on bigboy | `LoopbackServer` binds `InetAddress.getLoopbackAddress()`, plus `[server] bind` if set (GET only from any non-loopback connection); `LoopbackOnlyIT`, `LanBindIT` |
| Summary completeness | every change observed after `since` is returned across successive calls, ≤ 25 lines per call | changes are inserted in the transaction that changes the value, stamped `recorded_at` by the store when the write begins; `asOf` = `ChangeStore.watermark()` (the oldest in-flight stamp minus 1 µs, or now), or the last listed `recorded_at` when truncated **[Q-6]**. Holds for one kiln process, the only writer |
| Rate limit | kiln never spends past `X-RateLimit-Remaining` minus a reserve of 10 | budget check before each cycle **[Q-7]** |

## 9. Questions for the maintainer (each with a proposed answer)

| # | Finding | Proposed answer |
|---|---|---|
| Q-1 | §3 and §7 bind HTML and the API to 127.0.0.1, but rc4 `http-server` cannot do it: `HttpServerConfig` has no address and `NettyHttpServer` calls `bootstrap.bind(config.port())`, which binds every interface. **temper has the same defect:** `ConfigLoader.java:101` parses `[server] bind`, and `Controller.java:262-268` never passes it on, so a `bind = "127.0.0.1"` is silently ignored | kiln serves through the JDK's `com.sun.net.httpserver.HttpServer` on the loopback address (no new dependency; the routes and views are kiln's own). Pinned by `LoopbackOnlyIT`. The temper `bind` defect goes to the tracker separately |
|  | **Owner decision 2026-11-05 (LAN reads):** optional `[server] bind` (one IPv4 address) serves the dashboard to the home LAN on the same port; reads are LAN-wide, writes (every non-GET) stay loopback-only by the socket's remote address, and there are still no credentials | `LoopbackServer`, `KilnConfigLoader`; pinned by `LanBindIT`, `KilnConfigLoaderTest` |
| Q-2 | The hot pass (§5.5) must scan a subset and also find new directories, and the full pass reports `k of N`. The three verbs of §5.2 do neither | `scan` takes an optional stdin path list: full facts for the listed paths, stub lines (3 `stat`s each) for the rest. The full pass sends chunks of 100 |
| Q-3 | Roots are "per host, in config" (A7), but the forced command has no config of its own | Roots travel as words of `scan`, validated by the script (§3.2). The hard exclusions stay in the script |
| Q-4 | Non-git directories are "never listed per row", yet the dashboard shows "a drill-down list" | Emit `NonGitLine`s, which are not counted in `count` and are counted in `nonGit` |
| Q-5 | `UNSCANNED` = "a full pass has not yet seen it", but a hot pass covers new directories. Read literally, a new checkout scanned by a hot pass stays `UNSCANNED` until 04:00 | `UNSCANNED` = no full-facts observation from **any** pass |
| Q-6 | `summary` has ≤ 25 lines, yet guarantees "every change after `since` is listed" | When truncated, `asOf` = the `observedAt` of the last listed change, and the last line reads `more: since=<asOf>`. The caller loops, and the guarantee holds over calls |
| Q-7 | Unauthenticated, the limit is 60/h. One cycle needs about 9 + 3 × open PRs requests, so it cannot run every 60 s. GitHub documents 304s as free only "when correctly authorized", so P-2's "~0" may hold for the token only | The cycle stretches to `3600 × need / limit` and the header shows `RATE-LIMITED` with the stretched period. Re-run P-2 without a token |
| Q-8 | §5.2 says "POSIX shell script"; `BRIEF-RULES.md` says bash with `set -euo pipefail`, bash 3.2 compatible | bash 3.2 (as `worker-launch`); every target host has bash |
| Q-9 | D-1: kiln never uses temper's store. Is a compile dependency on `temper-protocol` (only `exec`: `ProcessRunner`, `Command`, `Completed`) acceptable? | Yes: it is shared code, not shared state, and `NoTemperJsonTest` keeps `TemperJson` and `temper-store` out. The alternative is copying about 300 lines |
| Q-10 | `pg-up.sh` hard-codes database and user `temper`, and its header asks for approval before the first run on bigboy, while §3 says kiln-pg needs "no owner action" | Add `TEMPER_PG_DB` and `TEMPER_PG_USER` (default `temper`). The first `kiln-pg` run is approved by the maintainer, not the owner |
| Q-11 | The temper reader's period is not specified | 60 s, every open head every cycle (a gate answer changes while it runs) |
| Q-12 | The hot set is defined by status "at the previous full pass" | The current derived status: a superset, so nothing hot is missed |
| Q-13 | P-3 requires recording the build's state during the measurement; the figures given here carry none | Record it at the next measurement, or state that none ran |
| Q-14 | K-14's macOS leg cannot run under Maven: there is no Maven on the Mac, and builds run on Linux | A fake `uname`/`lsof` test pins the branch and its parsing on Linux, plus one recorded manual run of the script on the Mac |
| Q-15 | K-15 compares with `gh pr list`; `gh` on bigboy is not established | Use `gh` where it is installed. Otherwise the maintainer picks the oracle; the proposal is `GET /search/issues?q=repo:pragmaticalabs/pragmatica+is:pr+is:open` (`total_count`) |
| Q-16 | The ingest key's `authorized_keys` line: should `from=` pin the Mac's LAN address? | Yes, if the Mac's address is fixed (DHCP reservation). Otherwise omit it, as §7 already states the residual (a fake Mac scan affects only cache rows) |

### 9.1 Rulings (maintainer, 2026-10-30)

Every proposed answer above is **accepted**. Notes:
- **Q-1:** the JDK `HttpServer` on loopback. The rc4 bind gap is pragmatica #2180; temper's own ignored `[server] bind`
  is filed in the temper tracker.
- **Q-7:** the token exists (bigboy `~/.config/kiln/github-token`, zero permissions, 0600; read by path, never
  printed). P-2 re-run **with it** on 2026-10-30 (bigboy load 0.8, 3 Maven processes): limit 5,000/h; 10 answers 304
  moved `x-ratelimit-used` by 0; 4 answers 200 moved it by 4. **Authenticated 304s are free.** Unauthenticated
  (2026-10-29, Mac) each 304 cost 1 of 60. The stretch logic and `RATE-LIMITED` stay, for a missing or expired token.
- **Q-9:** `temper-protocol` for `exec` only, pinned by `NoTemperJsonTest`.
- **Q-10:** the first `kiln-pg` run on bigboy is maintainer-approved, with `TEMPER_PG_DB` and `TEMPER_PG_USER` parameterised.
- **Q-13:** the build state is recorded at each measurement (as in Q-7 above).
- **Q-16:** no `from=` on the ingest key (the Mac's LAN address is not fixed); the residual is the one §7 states.

### 9.2 WP1 deviations, ruled (temper session, 2026-10-30)

WP1's notes are in its `.wp-state.md` (D1–D11). These change the plan:
- **Stubs with no `Result`** return a safe value marked "not built" (`Unknown`, `Incomplete` with no rows,
  `Unpreserved`, `UNSCANNED`, `SchemaBroken`, empty lists). Each WP replaces its own. **WP9 adds a guard test: no
  main source holds the text "not built".**
- **`Derived` carries no observation times.** `derive()` receives none; the times live in the store's `DerivedRow`
  (§2 `checkout_derived`). This replaces the §1.1 wording.
- **`SummaryText.render` belongs to WP2**, with `Changes.between`.
- **Roots (§3.2):** `*` may appear in any component **after the first**, and matches exactly one component; `**` and a
  `*` in the first component are refused (WP4). That admits a Mac root such as `/private/tmp/scratch-501/*/*/work/*`.
- **Config:** `[server] bind` is optional (one literal IPv4 address, never the wildcard or a loopback address; `hostNames` only with it; refusals exit 78); a set `tokenFile` that is missing, empty or not
  one header-safe token fails the start; unset or blank means unauthenticated.
- **Mirror step (WP9):** `kiln.service.mirror.MirrorStep` (fetch, then the behind facts of every open head; source
  `mirror`; a successful fetch triggers `DeriveJob.mirrorFetched`), run after each GitHub cycle and every 60 s.
- **PR-upsert trigger (WP9):** `kiln.service.UpsertTrigger`, the store as the GitHub poller alone sees it: an upsert that
  wrote a PR triggers `DeriveJob.prsUpserted` with its observation time.
- **Vanished roots:** a root with no `*` (or whose fixed part) vanishes makes every scan of its host exit 74: the host
  reads INCOMPLETE and nothing under any of its roots is deleted until the config changes (WP8 round 2; list only
  persistent roots).
