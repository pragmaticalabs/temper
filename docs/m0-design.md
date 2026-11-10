# temper — M0 implementation design

**Status.** Design for milestone M0. The contract is `docs/spec.md` v0.2, including the
2026-10-13 R-M2 copy-seed rule and A13; the rationale is `docs/design.md`. This document is the
input for parallel `jbct-coder` agents. It says *where* code goes, *which* types are shared, and
*which* rc4 APIs are used. It does not restate the spec; section references (§n) are to `spec.md`.

**Markers used here.**
- **[V]**: verified by reading the rc4 sources jar or pom in `.m2-local` (2026-10-13). The path
  is relative to the unpacked sources jar.
- **[U]**: unverified. The coder must confirm it before relying on it.
- **[P]**: a temper proposal made in this document. It can be challenged.
- **[G]**: a guess forced by an underdetermined spec. Each one is listed in §12 with what would
  settle it.
- **Q-n**: a spec ambiguity routed back to the maintainer (§12). The design proceeds on the marked guess
  until the maintainer rules.

**M0 scope.**
- Modules: domain, protocol, store, controller, worker and CLI.
- Postgres with migrations.
- One host: bigboy, reached over `ssh -T localhost`.
- Recipes `Mvn` and `ModuleTest`, unsharded, from the `Pushed` source.
- Results, testcases and events.
- Per-unit EMPTY, unit timeouts with thread dumps, and contention facts.
- The runner image.
- CLI commands `submit`, `wait`, `result`, `watch`, `cancel` and `log`.
- **Exit gate: A1–A5, A7–A10 and A13.**

---

## 1. Parent POM and module layout

### 1.1 Coordinates [V]

These were checked against `.m2-local/org/pragmatica-lite/**/1.0.0-rc4/*.pom`. Every artifact's
groupId is `org.pragmatica-lite`, version `1.0.0-rc4`.

| Need | artifactId | Notes |
|---|---|---|
| Promise/Result/Option/Cause, scheduler, FileOps | `core` | compile dep: slf4j-api 2.0.17 only |
| TOML | `toml` | `config` is a **pom aggregator** (no jar); depend on `toml` directly |
| JSON | `jackson` | Jackson **3** (`tools.jackson.core` 3.1.0) + `com.fasterxml.jackson.core:jackson-annotations` 2.21. `json` is a pom parent with no jar |
| HTTP server + WebSocket server | `http-server` | pulls netty-all 4.2.18.Final, netty-codec-http3, `tcp`, `utility`, `http-types` |
| HTTP request/response types | `http-types` | core only |
| Postgres | `postgres-async` | netty-all, core. **Not managed** in the `pragmatica` parent's dependencyManagement; give the version explicitly |
| JBCT checks | `jbct-maven-plugin` (packaging `maven-plugin`) | present in rc4 |

**Not used, and why:**
- `http-routing`: there is no glue to `http-server`. `RequestContext` can only be built from a
  Netty `FullHttpRequest`, which `http-server` never exposes.
- `http-client`: it drags netty-all into the CLI fat jar. `JdkHttpOperations` is just the JDK
  client.
- `aether/resource-db-async`: it pulls in the Aether `resource-api` tree. Its `transactional`
  also never calls `conn.close()` (`PgAsyncSqlConnector.java:86-205`), so it leaks a pool slot on
  every transaction. Its `?`→`$n` rewrite corrupts the jsonb `?` operators.
- `config-service`: `tomlConfigService()` hard-codes `aether.toml`. `TomlParser` is enough.
- `aether/sql-splitter`: postgres-async `completeScript` runs multi-statement files, and V1 has no
  `CONCURRENTLY` statements.

Third-party versions present in `.m2-local` [V]:
- junit-jupiter 5.13.4
- assertj-core 3.27.7
- slf4j-api 2.0.17
- logback-classic 1.5.21
- testcontainers (`testcontainers`, `postgresql`, `junit-jupiter`) 1.21.4
- maven-compiler-plugin 3.15.0
- maven-surefire-plugin 3.5.4
- maven-shade-plugin 3.6.1
- maven-jar-plugin 3.5.0

Only the plugin jars were confirmed; their transitive dependencies were not. **An offline
`-o` build is [U] until a `build-runner` runs one.**

### 1.2 Parent `pom.xml` (owned by WP1)

```
groupId    org.pragmatica.temper            [G] (repo is pragmaticalabs/temper; no published groupId exists)
artifactId temper-parent   version 0.1.0-SNAPSHOT   packaging pom
modules    temper-domain temper-protocol temper-store temper-controller temper-worker temper-cli
           (temper-acceptance only under profile `acceptance`, see §9)
properties maven.compiler.release=25, project.build.sourceEncoding=UTF-8,
           pragmatica.version=1.0.0-rc4, junit.version=5.13.4, assertj.version=3.27.7,
           slf4j.version=2.0.17, logback.version=1.5.21, testcontainers.version=1.21.4
dependencyManagement: core, toml, jackson, http-server, http-types, postgres-async (all ${pragmatica.version});
           temper-* siblings ${project.version}; junit-jupiter, assertj-core, logback-classic, testcontainers
pluginManagement: compiler 3.15.0 (release 25), surefire 3.5.4, shade 3.6.1, jar 3.5.0
build/plugins: org.pragmatica-lite:jbct-maven-plugin:1.0.0-rc4, goal `check` (default phase VERIFY) [V];
           parameters jbct.skip (default false), jbct.includeTests (default false) [V]
```

- **Do not inherit from `org.pragmatica-lite:pragmatica`.** It sets `jbct.skip=true` and
  `-proc:full`, and it manages 74 artifacts that temper does not need.
- **`jbct.toml` is optional** [V]. `ConfigLoader` folds the built-in defaults, then
  `~/.jbct/config.toml`, then the repo-root-down `jbct.toml` files. M0 ships none, so the defaults
  apply; WP1 may add `[lint] excludePackages` if a generated package appears.
- **`.mvn/maven.config`** already holds `-Dmaven.repo.local=.m2-local`. Never write `~/.m2`.
- Each module's surefire runs **unit tests only**. Tests that need docker or a real host carry
  the JUnit tag `host` and are excluded with `<excludedGroups>host</excludedGroups>`. They run
  only through `-Pacceptance` (§9).
- **Gate-scope warning:** a green default build says nothing about A4, A7, A10 or A13-live.

### 1.3 Module graph

```
temper-domain    -> core                                        (pure; no I/O; JDK + core only)
temper-protocol  -> temper-domain, core, jackson                (frames, message records, API DTOs, exec adapter)
temper-store     -> temper-domain, temper-protocol(api DTOs for jsonb), core, postgres-async, jackson
temper-controller-> domain, protocol, store, core, toml, jackson, http-server, logback
temper-worker    -> domain, protocol, core, jackson, logback    (NO netty)
temper-cli       -> domain, protocol, core, jackson             (NO netty; JDK HttpClient + JDK WebSocket)
temper-acceptance-> everything (profile `acceptance`, tag `host`)
```

**Base package:** `org.pragmatica.temper.<module>` [G]. Example: `org.pragmatica.temper.domain.verdict`.

**Fat jars** (maven-shade):
- `temper-controller-<ver>.jar` (main `org.pragmatica.temper.controller.Main`)
- `temper-worker-<ver>.jar` (main `org.pragmatica.temper.worker.Main`). Its resources carry
  `runner/Dockerfile` and `runner/RunSteps.java`.
- `temper-cli-<ver>.jar` (main `org.pragmatica.temper.cli.Main`), plus `ops/temper` as the
  wrapper for `~/.local/bin/temper`.

---

## 2. Pragmatica rc4 APIs to use (verified)

All entries are **[V]** unless marked otherwise. Paths are inside the respective sources jar.

### 2.1 core: `org.pragmatica.lang`

| Use | API |
|---|---|
| Async results | `Promise<T>` (sealed). `Promise.success/failure/lift(ThrowingFn0)/lift(Fn1<Cause,Throwable>, ThrowingFn0)/promise()/all(p1..p15).map(...)/allOf(Collection)` → `Promise<List<Result<T>>>`. Instance: `map/flatMap/mapError/recover/onSuccess/onFailure/onResult/fold/timeout(TimeSpan)` (returns a **derived** promise), `await()`/`await(TimeSpan)` (javadoc: `@TerminalOperation` required outside tests; annotation package **[U]**), `cancel()`, `resolve(Result)` |
| Threading | `lift`, `promise(Supplier)` and `Promise.async(Runnable)` run on `Executors.newVirtualThreadPerTaskExecutor()`. Blocking process I/O inside `Promise.lift` is therefore acceptable. Timeouts run on a 4-thread platform scheduler |
| Sync results | `Result<T>`: `success/failure/lift/tryOf/all(r1..r15).map(...)/allOf(List)` → `Result<List<T>>`, `async()`, `fold(failureFn, successFn)`, `unitResult()` |
| Optional | `Option<T>`: `option/some/none/present/empty/from(Optional)`, `toResult(Cause)`, `fold(Supplier, Fn1)`, `or(T)` |
| Errors | `Cause` (single abstract `String message()`), plus `Cause.Terminal` and `Cause.Transient`. `Causes.cause(String)`, `Causes.forOneValue(String template)` / `forTwoValues` → `Fn1<Cause,T>`. **`Causes.with1` does not exist.** Library pattern: a sealed interface extending `Cause` with records or enums, as in `toml/.../TomlError.java` |
| Validation | `Verify.ensure(T, Predicate<T>, Cause)` and the `Verify.Is` predicates: `notBlank`, `lenBetween`, `matches(String, Pattern)`, `between`, `positive`, `nonNegative` |
| Unit / tuples | `Unit.unit()`, `Tuple.tuple(...)` |
| Time | `org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds()`, `.duration()`, `fromDuration(Duration)`. Text parsing: `org.pragmatica.lang.parse.TimeSpan.timeSpan(String)` → `Result<TimeSpan>` (format **[U]**; the CLI uses ISO-8601 `Duration.parse` or `90m`/`6h`, see §8) |
| Scheduler | `org.pragmatica.lang.utils.SharedScheduler.schedule(Runnable, TimeSpan)` and `scheduleAtFixedRate(Runnable, TimeSpan[, TimeSpan])`, each returning `ScheduledFuture<?>`. A recurring task that throws is logged and never cancelled |
| Backoff | `org.pragmatica.lang.utils.Retry.BackoffStrategy.exponential().initialDelay(1s).maxDelay(60s).factor(2.0).withJitter()` → `TimeSpan nextTimeout(int attempt)`. The host link computes its delays from this directly and schedules with `SharedScheduler`. **`Retry.execute` is not used for reconnect** because it is bounded by `attempts(int)`, and reconnect is unbounded |
| Files | `org.pragmatica.lang.io.FileOps`: `readString/writeString/writeBytesDurable/moveAtomic/createDirectories/walk/deleteIfExists/setPosixPermissions` → `Result<…>` |
| IDs | not needed (job and unit ids are bigserial) |

**Not provided by rc4 (0 hits for `ProcessBuilder`, `ssh` or `MessageDigest` across core, toml,
config-service, utility, http-\*, jackson, tcp):** process execution, SSH and hashing. temper
supplies these as thin adapters (§2.6) or as JDK calls (`MessageDigest` for sha256; pure, so it
may sit in the domain).

### 2.2 TOML: `org.pragmatica.config.toml`

- Entry points: `TomlParser.parseFile(Path)` → `Result<TomlDocument>`, and `TomlParser.parse(String)`.
- Getters on `TomlDocument` (a record):
  - `getString/getInt/getLong/getBoolean(String section, String key)` → `Option<…>`
  - `getStringList` → `Option<List<String>>`
  - `getTableArray(String)` → `Option<List<Map<String,Object>>>`, used for `[[hosts]]`
- Root keys use section `""`.
- **TOML dates are not supported**, so durations are written as strings, e.g. `"PT60M"`.

### 2.3 JSON: `org.pragmatica.json` (artifact `jackson`)

- Mapper: `JsonMapper.defaultJsonMapper()`, which is `jsonMapper().withPragmaticaTypes().build()`.
  **There is no `JsonMapper.create()`.**
- Customisation: `jsonMapper().configure(Consumer<tools.jackson.databind.json.JsonMapper.Builder>)`.
- Methods:
  - `writeAsString(T)` and `writeAsBytes(T)` → `Result<…>`
  - `readString(String, Class<T>)`, `readBytes(byte[], Class<T>)`, `readString(String, TypeToken<T>)`
  - `readTree`
- Failures are the sealed `JsonError`.
- `TypeToken` is `org.pragmatica.lang.type.TypeToken`.
- `PragmaticaModule` registers serializers for `Option` and `Result` only.
- **There is no sealed or record support beyond Jackson 3 itself.** Polymorphic DTOs use the
  standard `com.fasterxml.jackson.annotation.@JsonTypeInfo(use = NAME, include = WRAPPER_OBJECT)`
  plus `@JsonSubTypes`. That produces exactly the spec's `{"pushed": {...}}` and
  `{"moduleTest": {...}}` shapes.
- **Annotations live only in temper-protocol DTOs, never in temper-domain.** Jackson 3's handling of
  2.21 annotations on records and sealed interfaces is **[U]**: WP1's first test must
  round-trip one sealed DTO.

### 2.4 HTTP server and WebSocket: `org.pragmatica.http.server`, `org.pragmatica.http.websocket`

**Server**
- Start: `HttpServer.httpServer(HttpServerConfig, BiConsumer<HttpRequest, ResponseWriter>)` →
  `Promise<HttpServer>`. Stop with `stop()` → `Promise<Unit>`.
- Config: `HttpServerConfig.httpServerConfig(String name, int port)`, then
  `.withMaxContentLength(int)` (default 65536) and `.withWebSocket(WebSocketEndpoint)`.
- **There is no bind-address field.** `NettyHttpServer.java:128,152` calls
  `bootstrap.bind(config.port())`, which binds every interface. This contradicts spec §7 ("bound to
  the LAN interface only"); see **Q-1**.

**Request and response**
- Request (`org.pragmatica.http.HttpRequest`): `method()`, `path()`, `headers().get(name)` (case
  insensitive, returns `Option`), `queryParams().get(name)` (`Option`), `body()`, `bodyAsString()`.
- Response (`ResponseWriter`): call `header(name, value)` first, then
  `write(HttpStatus, byte[], ContentType)`, or one of `respond(HttpStatus, String json)`,
  `ok(String json)`, `okText(String)`, `notFound()`, `badRequest(String)`.
- Status 422 is `HttpStatus.UNPROCESSABLE_ENTITY`.
- **There is no streaming.** Every response is one `FullHttpResponse`. That is fine for M0 (the
  evidence tar.gz is M1).
- The handler returns `void`. It must call the `ResponseWriter` from the Promise's `onResult`. An
  exception thrown by the handler becomes a 500.

**WebSocket**
- Endpoint: `WebSocketEndpoint.webSocketEndpoint(String path, Supplier<WebSocketHandler>)`.
- Handler: `void handle(WebSocketSession, WebSocketMessage)`. Messages are the sealed
  `Open()`, `Text(String content)`, `Binary(byte[])` and `Close()`.
- Session: `send(String)`, `close()`, `isOpen()`, `id()`.
- **Paths match exactly** (a HashMap lookup), so there are no path params. **The handler never sees
  the upgrade request headers.**
- Consequence: the WS path is fixed at `/api/v1/events`. The subscriber name and the bearer token
  travel in the client's first `Text` message (§6.5). This deviates from the spec route
  `/subscribers/{name}/events`; see **Q-2**.
- **Possible wiring defect [U]:** Netty's `WebSocketServerProtocolHandler` sits before
  temper's request handler (`NettyHttpServer.java:217-221`), which may swallow `Open` and `Close`.
- **WP3's first task is a spike test:** a JDK `java.net.http.WebSocket` client against
  `httpServer` must observe Open, then a Text round-trip, then Close. If it fails, the fallback is
  in §6.5.
- **Spike outcome:** the pre-release rc4 build (2026-10-13) delivered no frames, so the controller uses the §6.5
  long-poll fallback. The released 1.0.0-rc4 on Maven Central (2026-10-18) delivers the Text and its echo
  (`WebSocketSpikeTest` now pins that). **maintainer ruling (2026-10-18): long-poll stays.** The original reason, the
  WebSocket defect, is gone; the choice now rests on "it works, and nothing needs push latency" (the digest has cut
  `/events` traffic). Reopen only for a concrete latency or connection-count requirement.

### 2.5 Postgres: `postgres-async`

**Pool**
- Build with `new NettyConnectibleBuilder()` and set `.hostname(..).port(..).database(..).username(..).password(..).maxConnections(10)`, then `.pool()` → `Connectible`.
- Keep the builder in a `var`, because the setters return the base `ConnectibleBuilder`.

**Queries**
- `Connectible` extends `QueryExecutor`:
  - `completeQuery(String sql, Object... params)` → `Promise<PgResultSet>`
  - `completeScript(String sql)` → `Promise<Collection<PgResultSet>>` (multi-statement, no params)
- `Connectible.getConnection()` → `Promise<Connection>`. `Connection.begin()` → `Promise<Transaction>`.
- `Transaction` also extends `QueryExecutor`, with `commit()`, `rollback()` and `close()`.
- **Commit and rollback do not release the connection.** Call `Connection.close()` afterwards.
- A `Connection` is not thread-safe: only one Promise may be in flight on it.

**Placeholders and rows**
- Placeholders are **`$1, $2, …`**. The javadoc says `?`, and that is wrong.
- Rows: `PgRow.getLong/getInt/getString/getBoolean/getInstant(String column)` (boxed; null for
  NULL) and `getArray(col, String[].class)`.
- jsonb has no accessor, so read it with `getString` and parse it with JsonMapper.

**Typing hazards** (inferred from `DataConverter.assumeTypes`; must be pinned by store ITs)
- An `Instant` parameter is declared as TIMESTAMP. **Bind `OffsetDateTime` (`instant.atOffset(UTC)`)**
  and start Postgres with `-c timezone=UTC`.
- A `String` bound to jsonb is declared VARCHAR, so write **`$n::jsonb`**.
- `String[]` is UNSPECIFIED, so write **`$n::text[]`**.

**Statements and notifications**
- `FOR UPDATE SKIP LOCKED` is plain SQL on a `Transaction`.
- LISTEN/NOTIFY exists (`Connection.subscribe(channel, Consumer<String>)`) but **is not used in
  M0**. The single controller JVM is the only event writer, so it wakes subscribers in-process.

**temper-store owns a 40-line `Db` helper:**

```java
public interface Db {
    <T> Promise<T> inTransaction(Fn1<Promise<T>, Transaction> work);  // begin; work; commit on success / rollback on failure; ALWAYS conn.close()
    QueryExecutor executor();                                           // pool-level single statements
    Promise<Unit> close();
}
```

### 2.6 Adapters that rc4 lacks (owned by WP1, in `temper-protocol`, package `org.pragmatica.temper.protocol.exec`)

```java
public record Command(List<String> argv, Option<Path> workDir, Map<String,String> env, boolean inheritEnv,
                      TimeSpan timeout) {}                     // argv only; no shell, ever (R-SHELL)
public record Completed(int rc, byte[] stdout, byte[] stderr) {}
public interface ProcessRunner {
    Promise<Completed> run(Command command);                    // run to completion; Promise.lift on a virtual thread;
                                                                // on timeout: destroyForcibly + ExecError.TimedOut
    Result<Spawned> spawn(Command command);                     // long-lived (ssh link, docker wait)
    static ProcessRunner processRunner() { ... }                // ProcessBuilder impl
}
public interface Spawned {
    InputStream stdout(); OutputStream stdin(); InputStream stderr();
    Promise<Integer> exit();                                     // completes on process exit
    void kill();                                                 // destroyForcibly + descendants
    long pid();
}
public sealed interface ExecError extends Cause { record StartFailed(..), TimedOut(..), NonZero(int rc, String stderrTail) }
```

- **Placement [P]:** both the controller (ssh, git) and the worker (git, docker) need this adapter.
  `temper-protocol` is the one module both already depend on.
- **Rejected alternative:** a seventh module, `temper-exec`, which would deviate from spec §1.
- With `inheritEnv=false`, `ProcessBuilder.environment().clear()` runs first. Every docker and git
  call made by the worker uses it, with only `PATH` and `HOME` copied explicitly (plus
  `DOCKER_HOST` if the worker's own env sets it **[U]**).

---

## 3. temper-domain (pure; owned by WP1)

**Rules:** no I/O, no Jackson, no threads. Every factory is `static Result<X> x(raw…)`. Errors are a
sealed `DomainError extends Cause`.

### 3.1 Value objects (package `…domain.model`)

| Type | Factory | Rule |
|---|---|---|
| `JobId(long)` | `jobId(long)` | > 0 |
| `UnitName(String)` | `unitName(String)` | `^[a-z0-9][a-z0-9-]{0,31}$`. M0 always uses `main` |
| `UnitRef(JobId, UnitName)` | — | container name `temper-<job>-<unit>`, network `temper-<job>-<unit>` |
| `Sha(String)` | `sha(String)` | `^[0-9a-f]{40}$` after lowercasing. Short SHAs are refused **[G]** (Q-9) |
| `Ref(String)` | `ref(String)` | `^[A-Za-z0-9._/-]{1,200}$`, no leading `-` (prevents argv injection into git), no `..`, no `//`, does not end in `/` or `.lock` |
| `OwnerTag(String)` | `ownerTag(String)` | `^[A-Za-z0-9_]{1,64}$` (length cap **[G]**) |
| `Priority(int)` | `priority(Option<Integer>)` | default −20 (§6.3); range −1000..1000 **[G]** |
| `ModulePath(String)` | `modulePath(String)` | `^:?[A-Za-z0-9._/-]+$`, no leading `-`, no `..` |
| `TestSelection(String)` | `testSelection(String)` | `^[A-Za-z0-9_*?!#,.$/-]+$` (surefire pattern alphabet), no leading `-` |
| `MvnArg(String)` | `mvnArg(String)` | non-blank, ≤ 4096 chars, no NUL. **Content is not restricted here**; refusals (§3.4) judge it |
| `UnitTimeout(Duration)` | `unitTimeout(String iso)` | 1 min ≤ d ≤ 6 h **[G]**; A10 uses ≥ 1 min. Lower it to 10 s if the A10 harness needs it |
| `MarkerEpoch(long nanos)` | `markerEpoch(String fileText)` | trimmed, `^[0-9]{1,19}$`, > 0. **Takes the file's text, never its mtime** (A2) |
| `MemoryMb(int)` | `memoryMb(int)` | 512..65536 |

**Source** (sealed). M0 accepts only `Pushed`:

```java
sealed interface SourceRequest { record ByRef(Ref ref) ; record BySha(Sha sha) }      // what the client asked
record PushedSource(Option<Ref> ref, Sha sha) { String identity() { return sha.value(); } }  // after resolution
```

`Bundle` and `Patch` parse successfully into `SourceRequest.Unsupported(kind)`, and submit refuses
them with `REFUSED(unsupported)` **[G]**.

**Recipe** (sealed; canonical form used for dedup):

```java
sealed interface Recipe {
  record Mvn(List<MvnArg> args) implements Recipe {}
  record ModuleTest(List<ModulePath> pl, Option<TestSelection> test, Option<TestSelection> itTest) implements Recipe {}
  record Unsupported(String kind) implements Recipe {}     // gate, mutate in M0
  String kind();                                           // "mvn" | "module-test" | ...
  String canonical();                                      // §3.5
}
record RecipeOptions(Option<UnitTimeout> timeout)          // per-submit override (§4.3); Q-6 on where it lives
```

**UnitSpec** (the planner's output; immutable):

```java
record UnitSpec(UnitName name, UnitKind kind, List<List<String>> steps, boolean requestsTests,
                Duration timeout, MemoryMb memory, boolean needsPortGroup) {}
enum UnitKind { GATE_UNSHARDED, REST, EMBER, BUILD, MODULE_TEST, MVN, CONTROL, MUTANT, RERUN }
```

**Planner** (`…domain.plan.Planner`), pure:
`Result<List<UnitSpec>> plan(Recipe, RecipeOptions, PlanDefaults)`.
- **Mvn:** produces `steps = [["mvn", "-B", <args…>, "-Dmaven.repo.local=" + WS_M2]]`.
- **ModuleTest:**
  - Step 1 is `["mvn","-B","-pl",join(pl),"-am","install","-DskipTests","-Dmaven.repo.local=…"]`.
  - Step 2 is `["mvn","-B","-pl",join(pl),"test","-Dtest=<test>", …]`.
  - When `itTest` is present, step 2 is `["mvn","-B","-pl",join(pl),"verify","-Dit.test=<itTest>"(, "-Dtest=<test>" if present), …]`.
  - The case with `itTest` set and `test` absent is **Q-5**.
- `WS_M2` is the placeholder token `${ws}/.m2-local`. The worker substitutes the absolute
  workspace path, and only in that one token. The placeholder is a constant, never request data.
- `-B` (batch mode) is added **[P]**.
- **needsPortGroup:** see Q-4.
  - M0 computes it with `PortGroupRule.needs(steps, portGroupModules)`. A unit needs the port group
    when tests are requested and either (a) a `-pl` entry is in the configured port-group module list,
    or (b) the args have no `-pl` and request tests, or (c) `-am` or `-amd` is present together with a
    test phase.
  - **M0 has no bb-hold, so submit refuses such a unit with `REFUSED(port-group)`** **[G]**.
- **requestsTests** = `TestRequest.requested(steps)`, which is pure:
  - **True** when some step's argv contains a lifecycle phase at or past `test`, or a
    `surefire:test` / `failsafe:integration-test` goal. The phases that count are `test`,
    `prepare-package`, `package`, `pre-integration-test`, `integration-test`,
    `post-integration-test`, `verify`, `install` and `deploy`.
  - **False** when that step also carries `-DskipTests`, `-DskipTests=true`, `-Dmaven.test.skip`
    or `-Dmaven.test.skip=true`. Each step is judged alone, and the unit requests tests if any step
    does.
  - **[P]:** module-specific skip properties such as `-Dember.skipTests` are ignored here. They need
    the effective POM, which is M1.

### 3.2 Timeout table (`…domain.plan.Timeouts`), §4.3

```java
static Duration defaultFor(UnitKind k)   // GATE_UNSHARDED 3h, REST 2h, EMBER 90m, BUILD 45m,
                                         // MODULE_TEST 60m, MVN 60m, CONTROL 30m, MUTANT 30m, RERUN 30m
static Duration effective(UnitKind k, Option<UnitTimeout> override)
```

The table is pinned by one parameterised test.

### 3.3 Reports (`…domain.report`), §5.1 and §4.4

```java
enum Outcome { PASSED, FAILED, ERROR, SKIPPED }
record ParsedTestcase(String className, String name, Outcome outcome, double timeSeconds) {}
record ReportFile(String fileName, long testsAttr, List<ParsedTestcase> testcases) {}
record TestcaseRow(UnitName unit, String className, String name, Outcome outcome, double timeSeconds, boolean rerun) {}

final class ReportParser { static Result<ReportFile> parse(String fileName, byte[] xml); }
record ReportCandidate(String path, long mtimeEpochNanos) {}
final class ReportSelection { static List<ReportCandidate> newerThan(MarkerEpoch marker, List<ReportCandidate> all); } // strict >
```

**Parser setup:**
- Use a StAX `XMLInputFactory.newFactory()` with `IS_NAMESPACE_AWARE=false`, `SUPPORT_DTD=false`
  and `IS_SUPPORTING_EXTERNAL_ENTITIES=false` (XXE closed).
- It parses an in-memory `byte[]`, so it does no I/O and stays pure.

**`testsAttr`:**
- It is the sum of `tests=` over every `<testsuite>` element, including those under a
  `<testsuites>` wrapper.

**Testcase rows:**
- Each `<testcase classname= name= time=>` produces one row. The outcome comes from the first child
  element that is `<failure>` (FAILED), `<error>` (ERROR) or `<skipped>` (SKIPPED); with none of
  those the outcome is PASSED.
- `<flakyFailure>` and `<flakyError>` alone mean PASSED. `<rerunFailure>` alone means FAILED
  **[P]**.
- A missing `classname` falls back to the enclosing `testsuite@name` **[P]**.
- A missing `time` is 0.

**Failures:** any `XMLStreamException`, an unparseable `time`, or a non-UTF-8/XML body gives
`ReportError.Malformed(fileName, detail)`. The unit is then `ERROR(report)` (§5.3).

### 3.4 Refusals (`…domain.refusal`), §2.5

```java
enum RefusalCode { R_1553("R-1553"), R_FAILSAFE("R-FAILSAFE"), R_ENV("R-ENV"), R_M2("R-M2"), R_SHELL("R-SHELL"),
                   R_DISK("R-DISK"), SOURCE("source"), PORT_GROUP("port-group"), UNSUPPORTED("unsupported") }
record Refusal(RefusalCode code, String detail) implements Cause { ... }

sealed interface RefusalRule {           // data rows from the `refusals` table (structural rules are code)
  record ExcludeTestClass(String code, String simpleClassName, String disabledMarker) implements RefusalRule {}  // R-1553
  record ItTestRequired(String code, String moduleSegment) implements RefusalRule {}                              // R-FAILSAFE
}
record TreeFacts(Map<String, List<String>> sourcesByClass)  // simpleClassName -> file contents at the SHA (read by the controller)
record SubmitShape(boolean envFieldPresent, List<UnitSpec> units)

final class Refusals {
  static Result<Unit> checkSubmit(SubmitShape shape, List<RefusalRule> rules, TreeFacts tree);   // composes the list below
  // one named function per code; each returns Result<Unit>
  static Result<Unit> env(SubmitShape s);          // R-ENV
  static Result<Unit> m2(List<UnitSpec> u);        // R-M2
  static Result<Unit> shell(List<UnitSpec> u);     // R-SHELL
  static Result<Unit> rule(RefusalRule r, List<UnitSpec> u, TreeFacts t);  // R-1553, R-FAILSAFE
  static Result<Unit> disk(long freeDockerRoot, long freeWorkspace, long floorBytes);  // R-DISK (worker, unit start)
}
final class ContainerEnv { static Map<String,String> build(EnvInputs in); }   // R-ENV allow-list, §7.4
```

**The rules, as M0 implements them:**

- **R-ENV.**
  - The submit DTO declares an optional `env` field (and `environment`) **only** so that its presence
    can be refused as `REFUSED(R-ENV)`. Any other unknown field is a 400.
  - Structurally, `ContainerEnv.build` returns only the §2.5 keys, whatever the host environment
    holds.
  - **[P]**: the spec names no submittable "offending form" for R-ENV, which is why the field is
    declared; see Q-7.
- **R-M2.** A unit is refused when any step argv element:
  - equals or starts with `-Dmaven.repo.local`;
  - is `-D` followed by an element starting with `maven.repo.local`;
  - is `--define=maven.repo.local…`, or `--define` followed by `maven.repo.local…`;
  - is `-s`, `--settings`, `-gs` or `--global-settings`. **[P]**: a settings file can redirect
    `localRepository`; see Q-7.

  The planner's own forced value is appended *after* this check runs, on a different code path.
  Workspace seeding by copy is enforced by the worker (§7.3, A13).
- **R-SHELL.**
  - The first element of every step must be exactly `mvn`.
  - A unit is refused when any argument names a shell-capable plugin goal (`exec:exec`, `exec:java`,
    `antrun:run`) or sets `-Dexec.executable`.
  - This is **[P]**. The spec gives no submittable form for R-SHELL; see Q-7.
  - Structurally, the worker's `DockerRunArgs` (§7.4) never emits `sh`, `bash` or `-c`.
- **R-1553** (`ExcludeTestClass`):
  - The rule applies when `tree.sourcesByClass` holds the class.
  - It passes when the class source contains `disabledMarker`, using the regex
    `@Disabled\(\s*"#1553"\s*\)` built from the row.
  - It also passes when every step that requests tests has a `-Dtest=` or `-Dit.test=` value that
    contains `!<simpleClassName>` or `!**/<simpleClassName>*`. Plain glob matching is used.
  - Otherwise the unit is refused.
  - Whether a *positive* selection that cannot match the class (e.g. `-Dtest=Foo`) counts as an
    exclusion is **Q-3**. M0 answers **no** **[G]**, which is the strict reading of the spec.
- **R-FAILSAFE** (`ItTestRequired`):
  - The unit is refused when a step has a `-pl` entry whose last path segment, or whose
    `:artifactId` form, equals `moduleSegment`, **and** that step has `-Dtest=` but no `-Dit.test=`.
  - The same applies to a `-f`/`--file` path that contains the segment.
- **R-DISK.** The worker checks `free < floor` for each of the docker root and the workspace
  filesystem, and a breach gives `VOID(disk)` (§7.3).

**A1 mutation-probe design:**
- `checkSubmit` is literally `Result.allOf(List.of(env(...), m2(...), shell(...), rules...))`.
- Each A1 test feeds one offending form and asserts its code.
- A probe deletes one element of that list, or makes one named function `return unitResult()`.
  Exactly that code's test must then go red.
- `disk` is probed inside the worker's `UnitStarter`.
- The probe is run by the independent verifier and recorded in the milestone notes (§9).

### 3.5 Dedup key (`…domain.dedup.DedupKey`), §2.3

```java
static String of(String sourceIdentity, Recipe recipe, RecipeOptions opts, String runnerVersion)
// sha256 hex over: len(a) ':' a '\n' len(b) ':' b '\n' len(c) ':' c   (length-prefixed, so "‖" is unambiguous)
// b = recipe.canonical() + opts canonical: JSON, keys sorted, no whitespace, absent Options omitted,
//     lists kept in submitted order (order is semantic for mvn args)
static boolean reusable(JobState s)   // QUEUED, RUNNING, CANCELLING?, PASSED, FAILED, EMPTY -> true; VOID, ERROR, CANCELLED, REFUSED -> false
```

- A CANCELLING job is **not** reusable **[G]**: it is on its way to CANCELLED.
- `runnerVersion` = the controller's `runner.version` config value, which equals the worker jar
  version. That jar version determines the runner image (§7.6). *(Amended by owner decision D-6 A1, 2026-10-24: it is
  the controller's whole build version, `<project.version>+<judge12>[-dirty]` or `+unjudged`
  (`ControllerConfig#dedupRunner`), so no verdict of another temper build is reused; the runner image tag stays the
  bare `project.version`. Spec §2.3.)*
- `priority`, `keep` and `submittedBy` are not part of the key **[P]**, because they don't change
  what runs.
- The timeout override **is** part of the key **[P]**, because it can change the verdict.

### 3.6 Verdict (`…domain.verdict`), §5.3

```java
enum KillCause { TIMEOUT, CANCEL }
record UnitOutcome(UnitName name, boolean requestsTests, boolean rerun,
                   Option<Integer> rc, Option<KillCause> killed,
                   Option<String> voidReason, Option<String> errorReason,   // set by worker/controller (disk, lost, report, infra, seed-isolation)
                   List<TestcaseRow> testcases, long testsAttr) {}
record Manifest(Set<String> classes) {}                                     // M0: always none (manifest is M1)
record VerdictInput(List<UnitOutcome> units, boolean cancelled, Option<Manifest> manifest) {}

sealed interface Verdict {
  record Void(String reason) ; record Error(String reason) ; record Cancelled() ;
  record Empty(List<UnitName> units) ; record Failed(String reason) ; record Passed()
}
record Evaluation(Verdict verdict, Counts counts, List<TestcaseRow> failed, int observedFailures, List<String> annotations, Set<String> drift) {}
final class Verdicts { static Evaluation evaluate(VerdictInput in); }
```

The rules are evaluated in order, and the first match wins:

1. **VOID(reason):** any unit has `voidReason`. Ties go to the first unit by name.
2. **ERROR(reason):** any unit has `errorReason` (lost, report, infra, seed-isolation). Also
   `ERROR(plan)` when a `className` appears in testcases of more than one non-rerun unit.
3. **CANCELLED:** `cancelled`.
4. **EMPTY:** any unit with `requestsTests` and 0 testcase rows, **excluding units with
   `killed = TIMEOUT`** **[G]**; see Q-8. Without that exclusion, A10's sleeping-test unit would
   report EMPTY, since surefire writes no XML for an unfinished class. That would contradict A10's
   `FAILED(timeout)`.
5. **FAILED(reason):** the reason is the first that applies, in the order `timeout`, then
   `missing-classes` (needs a manifest: `manifest − reported ≠ ∅`), then `tests` (any
   FAILED/ERROR row), then `rc` (any rc ≠ 0) **[P]**.
6. **PASSED.**

**Counts:** `testcases`, `passed`, `failed`, `error`, `skipped`, `testsAttr`,
`classes = |distinct className|`, `manifest`, `missing`.

**`observedFailures`** = the FAILED+ERROR rows. It is filled for every verdict and printed for
VOID and ERROR.

**Annotations in M0:** `superseded` only. `unpushed` cannot occur in M0, and `KNOWN` and `rerun`
are M2.

### 3.7 Contention facts (`…domain.facts`), §4.5 and §6.1

```java
record ProcInfo(long pid, String cmdline, String cgroup) {}
record HostFacts(double load1, double load5, int cores, int foreignReactors, int temperUnitsRunning,
                 Option<Integer> bbRunning, Option<Integer> bbWaiting, long memAvailableKb,
                 long freeDockerRootBytes, long freeWorkspaceBytes, Instant at, List<String> notes) {}
record UnitEndFacts(HostFacts host, Option<Long> memoryPeakBytes, Instant memoryPeakSampledAt,
                    Map<String,Long> cpuStat, TcLeftovers tcLeftovers) {}           // §12.7
record TcLeftovers(List<TcLeftover> containers, Option<String> unverified) {}
final class HostReactors {
  static final String LAUNCHER = "org.codehaus.plexus.classworlds.launcher.Launcher";
  static int foreign(List<ProcInfo> procs, Set<String> temperContainerIds);   // launcher procs whose cgroup does NOT contain any temper container id
  static int total(int foreign, int temperActiveUnits);                        // §6.1 host reactors
}
```

- **A7:** take a snapshot with one temper container whose Launcher's cgroup is
  `0::/system.slice/docker-<id>.scope`, set temperActive=1, and expect a total of 1.
- Matching uses a "contains `<full 64-hex id>`" test. That covers both the systemd driver
  (`docker-<id>.scope`) and the cgroupfs driver (`/docker/<id>`).
- `bbRunning` and `bbWaiting` are the running and waiting tickets of `~/tools/bb-queue 0` (Q-10 ruling: reading
  is not touching; §12.7 item 2). An unreadable or unparsable listing gives `none()` and a `notes` entry.

### 3.8 Seed isolation (`…domain.seed.SeedIsolation`), A13

```java
record FileLinks(String relativePath, int nlink) {}
static Result<Unit> verify(List<FileLinks> files)   // any nlink > 1 -> SeedError.Hardlinked(count, first 5 paths)
```

### 3.9 Exit codes (`…domain.exit.ExitCode`), §8.1

```java
enum ExitCode { PASSED(0), ACCEPTED(0), FAILED(1), REFUSED(2), EMPTY(3), VOID(4), ERROR(5), CANCELLED(6),
                WAIT_TIMEOUT(7), NONE(8), PENDING(9), DEDUPLICATED(10), USAGE(64), UNREACHABLE(69);
  static ExitCode forVerdict(Verdict v); static ExitCode forState(JobState s); }
```

`JobState` is `{REFUSED, QUEUED, RUNNING, CANCELLING, SUPERSEDED, PASSED, FAILED, EMPTY, VOID, ERROR, CANCELLED}`,
with `isTerminal()` and `fromVerdict(Verdict)`.

`UnitState` is `{QUEUED, GRANTED, RUNNING, CANCELLING, PASSED, FAILED, EMPTY, VOID, ERROR, CANCELLED}`.

### 3.10 Reconcile and grant policies (pure; owned by WP3, in `temper-controller`, package `…controller.policy`)

These live in the controller module, not in the domain, because only the controller uses them.
They are still pure and unit-testable.

```java
// §6.4
record DiskUnit(UnitRef ref, boolean hasMarker, Option<Integer> rc, ContainerState container) {}   // from worker Hello
enum ContainerState { RUNNING, EXITED, ABSENT }
record DbUnit(UnitRef ref, UnitState state, Duration timeout) {}
sealed interface ReconcileAction { Collect(UnitRef); Reattach(UnitRef, Duration timeout); MarkLost(UnitRef);
                                   Requeue(UnitRef); KillOrphan(UnitRef); Cancel(UnitRef) }
static List<ReconcileAction> Reconciler.decide(List<DbUnit> db, List<DiskUnit> disk)
```

`decide` works through these rules in order:

1. When the DB unit is GRANTED, RUNNING or CANCELLING, apply the first match:
   - rc present: **Collect**.
   - the container is EXITED with no rc: **Collect** (the worker writes rc from `docker inspect`).
   - the container is RUNNING: **Reattach**, plus **Cancel** if the DB says CANCELLING.
   - a marker exists with no container and no rc: **MarkLost**, giving `ERROR(lost)`.
   - there is no marker: **Requeue** **[P]**.
2. When the disk unit is unknown to the DB, or the DB unit is already terminal, and its container
   is RUNNING: **KillOrphan**.

**Why Requeue is safe:** the marker is written before `docker run`, so a missing marker proves the
unit never ran. The single-worker host lock (§7.2) rules out a late `docker run` from a previous
worker. Requeueing is therefore not a re-run, and this refines the spec's "neither → ERROR(lost)";
see Q-11.

```java
// §6.1
record HostSnapshot(int cap, int foreignReactors, int activeUnits, int memoryBudgetMb, int activeMemoryMb,
                    long freeBytes, long floorBytes, boolean portIsolated) {}
record QueuedUnit(long unitId, int priority, Instant submittedAt, int memoryMb, boolean needsPortGroup) {}
record Starvation(Option<Long> headUnitId, Option<Instant> blockedSince) {}
record GrantDecision(Option<Long> grant, Starvation next) {}
static GrantDecision GrantPolicy.choose(HostSnapshot h, List<QueuedUnit> ordered, Starvation s, Instant now, Duration bound /*1200s*/)
```

`choose` applies these rules in order:

1. **Cap:** if `foreign + active ≥ cap`, grant nothing. This is the A7 cap rule, and the starvation
   state is left unchanged.
2. **Fit:** a unit fits when `activeMemory + memoryMb ≤ budget`, `freeBytes ≥ floor`, and either it
   doesn't need the port group or `portIsolated` holds.
3. **Head:** if the head fits, grant it and reset the starvation state.
4. **Starvation:** if the head doesn't fit, record `blockedSince` the first time the head is seen
   blocked. Once `now − blockedSince > bound`, grant nothing, so other units wait until the head
   fits.
5. **Backfill:** otherwise, grant the first unit that fits.

The starvation state lives in memory, and a controller restart resets it **[P]**.

---

## 4. temper-protocol (owned by WP1)

### 4.1 Frame codec (`…protocol.frame`)

The wire format of one frame:

```
frame := uint32 big-endian length N (1 ≤ N ≤ 16 MiB) || N bytes UTF-8 JSON (one Envelope)
```

```java
final class FrameCodec {
  static final int MAX_FRAME = 16 * 1024 * 1024;
  static Result<byte[]> encode(Envelope e, JsonMapper m);
  static Result<Envelope> decode(byte[] payload, JsonMapper m);
}
final class FrameReader { FrameReader(InputStream in); Result<Option<byte[]>> next(); }  // none() on clean EOF at a frame boundary; error on EOF mid-frame or N out of range
final class FrameWriter { FrameWriter(OutputStream out); synchronized Result<Unit> write(byte[] frame); } // write + flush
record Envelope(long id, Option<Long> replyTo, Message msg) {}
```

**stdout discipline in the worker:**
- At startup the worker captures `new FileOutputStream(FileDescriptor.out)` for frames.
- It then calls `System.setOut(System.err)`, so a stray library print cannot corrupt the stream.
- Logback logs to stderr only.

### 4.2 Messages (`…protocol.message`)

`Message` is a sealed interface with `@JsonTypeInfo(use=NAME, include=WRAPPER_OBJECT)`. Every
record below is a permitted subtype whose type name is the record name in camelCase. Times are ISO
strings and durations are ISO-8601. **Protocol version `1`.**

**Handshake**

| Direction | Message | Fields |
|---|---|---|
| W→C | `Hello` | `protocol:int, workerVersion, host, uid:int, gid:int, dockerGid:int, cores:int, facts:HostFactsDto, units:List<DiskUnitDto>` |
| C→W | `Welcome` | `protocol:int, controllerVersion` |
| C→W | `Incompatible` | `expectedProtocol:int, expectedWorkerVersion, reason` (the worker exits 0) |

- The worker sends `Hello` first.
- The controller accepts only when `protocol == 1` **and** `workerVersion == controllerVersion`.
- Otherwise it sends `Incompatible`, runs the deploy flow (§6.4) and reconnects.

**Controller → worker** (each request expects the reply listed)

| Message | Fields | Reply |
|---|---|---|
| `PrepareJob` | `job:long, sha, originUrl` | `JobPrepared{job, ws}` · `Failure{code, detail}` |
| `StartUnit` | `job, unit, kind, steps:List<List<String>>, timeout, memoryMb, cpuShares, runnerVersion, mavenOpts:Option<String>, tcHubPrefix:Option<String>` | `UnitStarted{job, unit, containerId, imageId, markerEpochNs:long, startedAt, facts}` · `UnitStartFailed{job, unit, verdictKind: "VOID"\|"ERROR", reason, facts?}` |
| `Reattach` | `job, unit, timeout` | `Ack` |
| `Collect` | `job, unit` | `Ack` (followed by the unit report stream) |
| `CancelUnit` | `job, unit` | `Ack{state}` |
| `UnitReportAck` | `job, unit` | — (the worker then removes the container and network) |
| `LogTail` | `job, unit, lines:int (≤ 10000)` | `LogChunk{text (≤ 1 MiB), truncated:bool, source: "file"\|"docker"}` |
| `FactsRequest` | — | `Facts{facts}` |
| `Shutdown` | — | — |

**Worker → controller** (unsolicited)

| Message | Fields |
|---|---|
| `Heartbeat` | `running:List<UnitRefDto>, facts:HostFactsDto` (every 10 s) |
| `UnitExited` | `job, unit, rc:int, killedBy:Option<"timeout"\|"cancel">, endedAt, endFacts:UnitEndFactsDto, reportFiles:int, testsAttr:long, threadDumps:int, errorReason:Option<String>` |
| `UnitReport` | `job, unit, chunk:int, last:bool, rows:List<TestcaseDto>` (≤ 2000 rows per chunk) |
| `Failure` | `replyTo, code, detail` |

**Report delivery**
- After a unit exits (or after `Collect`), the worker sends `UnitExited` followed by
  `UnitReport` chunk 0..k, with `last=true` on the final chunk.
- The controller persists everything in **one** transaction after the last chunk (§5.3), then sends
  `UnitReportAck`.
- **Collection is idempotent:** if the ack is lost, the next `Collect` resends. The controller acks
  without inserting when the unit is already terminal.

**Bulk-data rule (design §7, Condition 3)**
- Testcase rows go in chunked frames, which bounds each frame.
- Logs are tails bounded at 10 000 lines and 1 MiB.
- Evidence transfer over a second ssh session is M1.

### 4.3 HTTP/WS API DTOs (`…protocol.api`, shared by the controller and the CLI)

- `SubmitRequest`: `submittedBy`, `priority?`, `keep?`, `fresh?`, `source` (`{pushed:{ref|sha}}`, or
  `bundle`/`patch` parsed as unsupported), `recipe` (`moduleTest`/`mvn`/`gate`/`mutate`),
  `timeout?` (Q-6), `env?`/`environment?` (accepted by the DTO only so it can be refused as R-ENV).
- `SubmitResponse{job, state, deduplicated}`.
- `RefusedResponse{refused, detail, job?}`.
- `JobView{job, state, verdict?, reason?, submittedBy, submittedAt, priority, keep, fresh, superseded, source, recipe, units:List<UnitView>}`.
- `UnitView{name, state, host?, rc?, startedAt?, endedAt?, imageId?, containerId?, killedBy?}`.
- `ResultRecordDto`: exactly §5.4.
- `TestcaseDto{class, name, outcome, timeSeconds, unit, rerun}`.
- `CancelResponse{state}`.
- `HostView{name, state, cap, reactors, foreign, active, facts}`.
- `ErrorResponse{error}`.

**WS messages:**
- client → server: `WsSubscribe{token, subscriber, terminal:bool}` and `WsAck{seq}`;
- server → client: `WsEvent{seq, at, job, kind, verdict?, line}`, `WsGap{from, to}` and
  `WsError{error}`.

**DTO-to-domain mapping** happens in the DTO's `toDomain()`, which returns a `Result` and calls the
domain factories (parse, don't validate).

---

## 5. temper-store (owned by WP2)

### 5.1 Postgres runtime

**Container**

```
docker run -d --name temper-pg --restart unless-stopped \
  -v temper-pg:/var/lib/postgresql/data \
  -p 127.0.0.1:55432:5432 \
  -e POSTGRES_DB=temper -e POSTGRES_USER=temper -e POSTGRES_PASSWORD_FILE=/run/secrets/pw \
  -v ~/.config/temper/pg-password:/run/secrets/pw:ro \
  postgres:16 -c timezone=UTC
```

- The image is `postgres:16`, which is already present on bigboy (spec §1).
- The command lives in `ops/pg-up.sh`, owned by WP2. It is **idempotent**: it starts the container
  if it is stopped and creates it if it is absent.
- Port 55432 **[G]** avoids any existing 5432.
- The password file is 0600.
- Running it on bigboy is an owner-visible action; ask before the first run.

**Migrations**
- Files are `temper-store/src/main/resources/db/migration/V<n>__<name>.sql`. `MigrationRunner`
  applies them at controller start:
  1. `CREATE TABLE IF NOT EXISTS schema_migrations(version int PRIMARY KEY, name text NOT NULL, sha256 text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`.
  2. `SELECT pg_advisory_lock(7070)`.
  3. For each file not yet applied, in one `Transaction`: `tx.completeScript(fileText)`, then
     insert the row.
  4. If an applied file's checksum no longer matches, abort startup with
     `StoreError.MigrationTampered(version)`.
- The runner is about 80 lines, built on `completeScript` [V].

### 5.2 V1 (`V1__m0_schema.sql`)

It covers the tables M0 uses plus the forward-compatible ones (`manifests`, `unit_facts`, `hosts`,
`refusals`). `known`, `blobs` and `priorities` arrive in their milestones' migrations.

```sql
CREATE TABLE hosts (
  name text PRIMARY KEY, address text NOT NULL, host_key text,
  cap int NOT NULL DEFAULT 5, memory_budget_mb int NOT NULL, port_isolated boolean NOT NULL DEFAULT false,
  state text NOT NULL DEFAULT 'DOWN' CHECK (state IN ('DOWN','CONNECTING','UP')),
  facts jsonb, facts_at timestamptz);

CREATE TABLE jobs (
  id bigserial PRIMARY KEY,
  submitted_by text NOT NULL CHECK (submitted_by ~ '^[A-Za-z0-9_]{1,64}$'),
  submitted_at timestamptz NOT NULL DEFAULT now(),
  priority int NOT NULL, keep boolean NOT NULL DEFAULT false, fresh boolean NOT NULL DEFAULT false,
  unpushed boolean NOT NULL DEFAULT false,
  source jsonb NOT NULL, source_sha text CHECK (source_sha ~ '^[0-9a-f]{40}$'), ref text,
  recipe jsonb NOT NULL, recipe_kind text NOT NULL, dedup_key text,
  state text NOT NULL CHECK (state IN ('REFUSED','QUEUED','RUNNING','CANCELLING','SUPERSEDED',
                                       'PASSED','FAILED','EMPTY','VOID','ERROR','CANCELLED')),
  refusal_code text, refusal_detail text,
  superseded boolean NOT NULL DEFAULT false, superseded_by bigint REFERENCES jobs(id),
  cancel_requested_at timestamptz, finished_at timestamptz);
CREATE INDEX jobs_dedup  ON jobs (dedup_key, id DESC);
CREATE INDEX jobs_active_ref ON jobs (ref, recipe_kind) WHERE state IN ('QUEUED','RUNNING','CANCELLING');
CREATE INDEX jobs_sha    ON jobs (source_sha);

CREATE TABLE units (
  id bigserial PRIMARY KEY, job_id bigint NOT NULL REFERENCES jobs(id),
  name text NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,31}$'), kind text NOT NULL,
  host text REFERENCES hosts(name),
  state text NOT NULL CHECK (state IN ('QUEUED','GRANTED','RUNNING','CANCELLING',
                                       'PASSED','FAILED','EMPTY','VOID','ERROR','CANCELLED')),
  reason text, args jsonb NOT NULL, requests_tests boolean NOT NULL,
  needs_port_group boolean NOT NULL DEFAULT false, memory_mb int NOT NULL, timeout_s int NOT NULL,
  lease_until timestamptz, granted_at timestamptz, started_at timestamptz, ended_at timestamptz,
  rc int, killed_by text CHECK (killed_by IN ('timeout','cancel')),
  marker_epoch_ns bigint, image_id text, container_id text, tests_attr bigint,
  rerun boolean NOT NULL DEFAULT false,
  UNIQUE (job_id, name));
CREATE INDEX units_queued      ON units (id) WHERE state = 'QUEUED';
CREATE INDEX units_host_active ON units (host) WHERE state IN ('GRANTED','RUNNING','CANCELLING');

CREATE TABLE results (
  job_id bigint PRIMARY KEY REFERENCES jobs(id), verdict text NOT NULL, reason text,
  annotations text[] NOT NULL DEFAULT '{}', counts jsonb NOT NULL, record jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE testcases (
  job_id bigint NOT NULL REFERENCES jobs(id), unit_id bigint NOT NULL REFERENCES units(id),
  class text NOT NULL, name text NOT NULL,
  outcome text NOT NULL CHECK (outcome IN ('passed','failed','error','skipped')),
  time_s double precision NOT NULL, rerun boolean NOT NULL DEFAULT false);
CREATE INDEX testcases_job ON testcases (job_id, outcome);

CREATE TABLE manifests (job_id bigint NOT NULL REFERENCES jobs(id), module text NOT NULL, class text NOT NULL,
  PRIMARY KEY (job_id, module, class));

CREATE TABLE events (
  seq bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), job_id bigint REFERENCES jobs(id),
  kind text NOT NULL CHECK (kind IN ('queued','started','unit-finished','finished','cancelled','superseded')),
  payload jsonb NOT NULL);

CREATE TABLE subscribers (
  name text PRIMARY KEY CHECK (name ~ '^[A-Za-z0-9_.-]{1,64}$'),
  cursor bigint NOT NULL, selectors jsonb NOT NULL DEFAULT '[]');

CREATE TABLE refusals (
  code text PRIMARY KEY, matcher jsonb NOT NULL, issue int, reason text NOT NULL,
  until_text text, expired_at timestamptz);

CREATE TABLE unit_facts (
  unit_id bigint NOT NULL REFERENCES units(id), phase text NOT NULL CHECK (phase IN ('start','end')),
  facts jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (unit_id, phase));

-- write-once (§5.4, §10). Retention (M1) sets: SET LOCAL temper.retention = 'on'
CREATE FUNCTION temper_write_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_setting('temper.retention', true) = 'on' THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'temper: % on % is forbidden (write-once)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER results_write_once   BEFORE UPDATE OR DELETE ON results   FOR EACH ROW EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER testcases_write_once BEFORE UPDATE OR DELETE ON testcases FOR EACH ROW EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER results_no_truncate   BEFORE TRUNCATE ON results   FOR EACH STATEMENT EXECUTE FUNCTION temper_write_once();
CREATE TRIGGER testcases_no_truncate BEFORE TRUNCATE ON testcases FOR EACH STATEMENT EXECUTE FUNCTION temper_write_once();

INSERT INTO refusals (code, matcher, issue, reason, until_text) VALUES
 ('R-1553', '{"excludeTestClass":{"simpleClassName":"StreamPublishReshuffleTest","disabledMarker":"#1553"}}',
  1553, 'StreamPublishReshuffleTest is unstable until #1553 is fixed', 'until #1553 closes'),
 ('R-FAILSAFE', '{"itTestRequired":{"moduleSegment":"forge-tests"}}',
  NULL, 'forge-tests with -Dtest but no -Dit.test runs every IT', 'permanent');
```

**What the write-once guarantee covers:**
- No application path can UPDATE, DELETE or TRUNCATE `results` or `testcases`. That is enforced by
  row and statement triggers.
- The triggers do **not** stop a superuser who runs `ALTER TABLE … DISABLE TRIGGER`, or anyone who
  drops the table. The `result.json` copy (M1) covers that.

### 5.3 Repository interfaces (`…store.api`). WP2's first commit, verbatim

```java
public interface JobStore {
  Promise<SubmitOutcome> submit(NewJob job);                       // tx: pg_advisory_xact_lock(hashtextextended(dedup_key,0));
                                                                   //  if !fresh: latest job with same key and reusable state -> Deduplicated
                                                                   //  else insert job+units, supersede (§5.4), events 'queued'(+'superseded'/'cancelled')
  Promise<JobId> recordRefused(NewJob job, Refusal refusal);       // state REFUSED, no units, no event
  Promise<Option<JobRow>> job(JobId id);                           // + units
  Promise<List<JobRow>> list(JobQuery q);
  Promise<CancelOutcome> requestCancel(JobId id);                  // QUEUED: units+job -> CANCELLED (+result row, event 'cancelled');
                                                                   // RUNNING: job+active units -> CANCELLING, returns units to kill
}
public interface UnitStore {
  Promise<List<Granted>> grant(HostName host, Fn1<GrantDecision, List<QueuedUnit>> policy, TimeSpan lease, int maxGrants);
      // tx: SELECT … FOR UPDATE OF u SKIP LOCKED (below); call policy; conditional UPDATE … WHERE state='QUEUED'; repeat ≤ maxGrants
  Promise<Unit> started(UnitRef ref, UnitStartedRow row);          // GRANTED -> RUNNING; job QUEUED -> RUNNING + event 'started'; unit_facts start
  Promise<Unit> refreshLeases(HostName host, List<UnitRef> running, TimeSpan lease);
  Promise<List<ActiveUnit>> active(HostName host);                 // GRANTED|RUNNING|CANCELLING on host
  Promise<CompleteOutcome> complete(UnitRef ref, UnitFinishRow finish, List<TestcaseRow> rows,
                                    Fn1<Option<JobFinish>, List<UnitOutcome>> decideJob);
      // ONE tx: if unit terminal -> AlreadyComplete (idempotent); else insert testcases, update unit, unit_facts end,
      // event 'unit-finished'; load all units' outcomes; decideJob (pure Verdicts.evaluate) -> if Some: insert results,
      // update job state/finished_at, event 'finished' or 'cancelled'
  Promise<Unit> requeue(UnitRef ref);                              // GRANTED -> QUEUED (no marker; §3.10)
}
public interface ResultStore {
  Promise<Option<ResultRecordDto>> result(JobId id);
  Promise<List<TestcaseRow>> testcases(JobId id, Option<Outcome> outcome);
}
public interface EventStore {
  Promise<Long> append(…)                                          // used only inside other txs via the package-private EventWriter
  Promise<List<EventRow>> after(long seq, Set<String> kinds, int limit);
  Promise<Long> minSeq();                                          // gap detection
  Promise<SubscriberRow> subscriber(String name);                  // create with cursor = max(seq) if absent [G]
  Promise<Unit> ack(String name, long seq);                        // UPDATE … SET cursor = GREATEST(cursor, $2)
}
public interface RefusalStore { Promise<List<RefusalRule>> active(); }   // expired_at IS NULL
public interface HostStore {
  Promise<Unit> upsert(HostConfig h);
  Promise<Unit> state(HostName h, String state);
  Promise<Unit> facts(HostName h, HostFacts f);
}
public interface Store extends AutoCloseable { JobStore jobs(); UnitStore units(); ResultStore results();
  EventStore events(); RefusalStore refusals(); HostStore hosts();
  static Promise<Store> connect(DbConfig cfg); }                   // pool + MigrationRunner
```

The row records `NewJob`, `JobRow`, `UnitStartedRow`, `UnitFinishRow`, `Granted`, `ActiveUnit`,
`EventRow`, `SubscriberRow`, `SubmitOutcome` (`Accepted|Deduplicated`), `CancelOutcome` and
`CompleteOutcome` live in `…store.api`. WP2 defines them in the first commit, and WP3 codes against
them.

**Grant query** (inside `grant`):

```sql
SELECT u.id, u.job_id, u.name, u.memory_mb, u.needs_port_group, j.priority, j.submitted_at
  FROM units u JOIN jobs j ON j.id = u.job_id
 WHERE u.state = 'QUEUED'
 ORDER BY j.priority DESC, j.submitted_at ASC, u.id ASC
 LIMIT 200
   FOR UPDATE OF u SKIP LOCKED;
-- after GrantPolicy.choose picks $1:
UPDATE units SET state = 'GRANTED', host = $2, granted_at = now(),
                 lease_until = now() + make_interval(secs => $3)
 WHERE id = $1 AND state = 'QUEUED'
RETURNING id, job_id, name, kind, args, timeout_s, memory_mb;
```

**Honest guarantee for grant:**
- *At most one grant per unit* comes from the conditional `UPDATE … WHERE state='QUEUED'` inside
  the transaction.
- `SKIP LOCKED` adds non-blocking behaviour if a second scheduler loop ever ran concurrently. It is
  not what earns the property for the single controller in M0.

**Event ordering:**
- Every transaction that inserts into `events` first takes
  `pg_advisory_xact_lock(70700001)`.
- That makes commit order equal `seq` order. Without it, a reader at cursor `c` could see
  `c+2` committed before `c+1` and skip `c+1` for good.
- The cost is serialised event writes, which is negligible at this scale.

---

## 6. temper-controller (owned by WP3)

### 6.1 Config: `~/.config/temper/controller.toml` [P]

```toml
# no `version` key: the build's ${project.version} (§12.7 item 3); == worker jar version == runner.version
[server]   bind = "192.168.x.y"  port = 7070  tokenFile = "~/.config/temper/token"
[db]       host = "127.0.0.1"  port = 55432  name = "temper"  user = "temper"  passwordFile = "~/.config/temper/pg-password"
[repo]     origin = "<git url of the pragmatica repo>"  mirror = "~/.temper/controller-mirror.git"
[scheduler] intervalSeconds = 2  starvationSeconds = 1200  leaseSeconds = 30
[units]    memoryMb = 6144  cpuShares = 1024  diskFloorGiB = 50  mavenOpts = ""  tcHubPrefix = ""
[portGroup] modules = ["aether/ember", "aether/forge/forge-tests"]   # Q-4; maintainer supplies the real list
[[hosts]]  name = "bigboy"  address = "localhost"  user = "builder"  key = "~/.ssh/temper_worker"
           knownHosts = "~/.temper/known_hosts"  cap = 5  memoryBudgetMb = 20480  portIsolated = false
```

- It is parsed with `TomlParser.parseFile` into a `ControllerConfig` record; the `Result` failure is
  fatal at start.
- `[[hosts]]` is upserted into `hosts` on start.
- `server.bind` is recorded but **cannot be honoured** by rc4 `http-server` (Q-1).
- `units.memoryMb` and `memoryBudgetMb` are **[G]**. §12.1 sizing replaces them.
- **Assumption on the token:** the controller reads the same token file as the clients, and the
  comparison is constant time (`MessageDigest.isEqual`).

### 6.2 HTTP routes (M0)

`Api` is temper's own router of about 80 lines. It splits `HttpRequest.path()` into segments,
matches them against `(method, pattern)`, and requires a bearer token on **every** route (otherwise
401 `{error}`). Handlers have the form `Fn1<Promise<ApiResponse>, ApiRequest>`. An `ApiResponse`
carries `status`, `contentType` and `body`; the one adapter that writes `ResponseWriter` lives in
`HttpGlue`.

| Method | Path (under `/api/v1`) | Use case | Status |
|---|---|---|---|
| POST | `/jobs` | `SubmitJob` | 200 `SubmitResponse` · 422 `RefusedResponse` · 400 `ErrorResponse` |
| GET | `/jobs/{id}` | `GetJob` | 200 `JobView` · 404 |
| GET | `/jobs/{id}/result` | `GetResult` | 200 `ResultRecordDto` · 404 until terminal |
| GET | `/jobs/{id}/testcases?outcome=` | `ListTestcases` | 200 `List<TestcaseDto>` |
| POST | `/jobs/{id}/cancel` | `CancelJob` | 200 `CancelResponse` |
| GET | `/jobs/{id}/log?unit=&tail=` | `TailLog` | 200 text · 503 when the host link is down |
| GET | `/jobs?state=&submittedBy=&sha=&ref=&limit=` | `ListJobs` | 200 |
| GET | `/hosts` | `ListHosts` | 200 `List<HostView>` (used by A7) |
| WS | `/events` (fixed path; Q-2) | `EventStream` | §6.5 |

Request bodies are capped at `withMaxContentLength(1 MiB)`.

### 6.3 SubmitJob (Zone A use case; a sequencer of async steps)

```
SubmitRequest(json) ─► ValidSubmit.validSubmit(dto)          Result (fork-join of the domain factories)        → 400 on failure
  ─► .async()
  ─► resolveSource       Promise<PushedSource>       GitRemote.lsRemote(ref) | Mirror.fetch()+Mirror.containsInHeads(sha)
                                                     → Refusal(SOURCE) on miss
  ─► plan                Result<List<UnitSpec>>      Planner.plan(recipe, opts, defaults)
  ─► gatherRefusalInputs Promise.all( RefusalStore.active(), TreeReader.facts(sha, ruleClassNames) )   fork-join
  ─► refuse              Result<Unit>                Refusals.checkSubmit(...); port-group check
                         on Refusal: JobStore.recordRefused → 422 {refused, detail, job}
  ─► dedupKey            pure
  ─► JobStore.submit(NewJob)                         → Accepted|Deduplicated → 200
  ─► wake scheduler + event subscribers (in-process signal)
```

**Controller adapters** (Zone C; each an interface returning `Promise`, implemented over
`ProcessRunner`):

```java
interface GitRemote { Promise<Sha> lsRemote(Ref ref); }
  // `git ls-remote --exit-code <origin> -- refs/heads/<ref> refs/tags/<ref> <ref>`; exactly one distinct sha, else Refusal(SOURCE) [G]
interface Mirror {
  Promise<Unit> fetch();                          // `git --git-dir <mirror> fetch --prune --quiet origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'`
  Promise<Boolean> containsInHeads(Sha sha);      // `git --git-dir <mirror> for-each-ref --contains <sha> --count=1 refs/heads`
}
interface TreeReader { Promise<TreeFacts> facts(Sha sha, Set<String> simpleClassNames); }
  // `git --git-dir <mirror> grep -l -F -e 'class <Name>' <sha> -- '*.java'` then `git --git-dir <mirror> show <sha>:<path>`
```

- The mirror is created with `git clone --mirror <origin> <mirror>` on first use.
- **Note:** spec §2.2 says `git branch -r --contains`, but a `--mirror` clone has no
  remote-tracking refs, so `for-each-ref refs/heads` is the equivalent. Fetches are serialised by
  a per-mirror in-process lock.
- **Supersede** (§2.4) runs inside `JobStore.submit` for `Pushed{ref}` jobs. It looks for an earlier
  job with the same `(ref, recipe_kind)` and a different SHA, in state QUEUED, RUNNING or
  CANCELLING, and applies the §2.4 table. A RUNNING job without `keep` goes to CANCELLING with
  `superseded=true`, and the controller issues `CancelUnit`. Whether supersede is in M0's scope is
  **Q-12**; it is designed in because the cost is low.

### 6.4 Host link (`…controller.host`)

**Starting the worker**
- One `HostLink` exists per configured host, as a state machine
  `DOWN → CONNECTING → HANDSHAKING → UP → DOWN`.
- It spawns, through `ProcessRunner.spawn`:

  ```
  ssh -T -o BatchMode=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3
      -o StrictHostKeyChecking=yes -o UserKnownHostsFile=<knownHosts>
      -o IdentitiesOnly=yes -i <key> <user>@<address> run <version>
  ```

- **On the host**, `authorized_keys` carries
  `command="$HOME/.temper/bin/worker-launch",restrict ssh-ed25519 …`.
- `worker-launch` is shipped in `ops/worker-launch` (WP4). It reads `$SSH_ORIGINAL_COMMAND`, which
  must be exactly `run <ver>` or `install <ver> <sha256>`, with the version matching
  `^[0-9A-Za-z.+-]{1,40}$`:
  - `run` does `exec java -jar ~/.temper/worker-<ver>.jar`;
  - `install` reads the jar from stdin into a temp file, checks its sha256, and moves it atomically
    to `~/.temper/worker-<ver>.jar`.
- **[P]**: a `restrict`ed key cannot `scp`, so the "controller deploys the worker" step of design §7
  needs this second verb. Setting up `authorized_keys` and the host key on bigboy is an
  owner/maintainer-approved step (Q-13).

**Handshake and reconnect**
- **Handshake:** the controller reads `Hello`, checks the version (otherwise it deploys, then
  reconnects), and sends `Welcome`. It then runs `Reconciler.decide(UnitStore.active(host), hello.units)`
  and executes the resulting actions. Only after that does it set the host `UP` and enable grants for
  the host.
- **Liveness:** if no frame arrives for 35 s, the link is DOWN. The controller kills the ssh
  process; a `Spawned.exit()` completion is DOWN as well.
- **Backoff:** reconnect delay = `BackoffStrategy.exponential().initialDelay(1s).maxDelay(60s).factor(2).withJitter().nextTimeout(attempt)`.
  The attempt counter resets after 60 s of UP.
- **Heartbeat** (every 10 s) runs `UnitStore.refreshLeases(host, running, 30s)` and
  `HostStore.facts`.
- **Lease expiry** while the host is DOWN changes nothing in the DB. The API shows such units as
  `RUNNING` with `leaseExpired=true` ("RUNNING(unknown)"), and they are never re-granted.

**Requests over the link**
- Request/response correlation uses `Envelope.id`/`replyTo`, and every request carries a
  `Promise.timeout`:
  - 30 s by default;
  - 15 min for `PrepareJob`, which covers fetch, checkout and seed copy;
  - 20 min for a `StartUnit` that has to build the runner image.

### 6.5 Scheduler, events and subscriber cursors

**Scheduler**
- `SharedScheduler.scheduleAtFixedRate(tick, timeSpan(2).seconds())`, plus an immediate tick on
  every in-process wake signal (submit, unit exit, link UP).
- Ticks run one at a time behind a non-reentrant guard.
- Each tick, for every host that is UP:
  1. Build the `HostSnapshot`. `foreignReactors` comes from the latest worker facts; `activeUnits`
     and `activeMemoryMb` come from `UnitStore.active(host)`, which counts GRANTED as well, so a
     grant whose container hasn't started yet still counts.
  2. Call `UnitStore.grant(host, GrantPolicy::choose, 30s, maxGrants=cap)`.
  3. For each grant, send `PrepareJob` (idempotent) and then `StartUnit`.
- **Outcomes of a start:**
  - `UnitStarted` → `UnitStore.started`.
  - `UnitStartFailed{VOID, disk}` → `complete` with `voidReason=disk`.
  - `UnitStartFailed{ERROR, …}` → `complete` with `errorReason`.
  - A timeout or a link drop leaves the unit GRANTED, and the next handshake's reconcile resolves it.
- **A7 rule:** the host reactor count is `foreignReactors` (worker facts) plus `activeUnits` (DB).
  Temper units are never counted from `ps`. The live probe for A7 is in §9.

**Events**
- They are written only inside store transactions. The `line` is rendered by the pure
  `EventLines.render(kind, job, verdict, counts)`, e.g.
  `finished 4711 FAILED sha=0cf5631 module-test testcases=12 failed=1 seq=88`.
- **[P]:** the spec says the line carries `seq` but doesn't place it. M0 appends `seq=` at the end.

**WebSocket protocol** (`/api/v1/events`):

1. The client sends `WsSubscribe{token, subscriber, terminal}`. A bad token gets
   `WsError{error:"unauthorized"}` and the socket is closed.
2. The server loads the subscriber, creating it with `cursor = current max(seq)` **[G]** (Q-14), so
   a new name does not replay all of history.
3. **Gap check:** if `cursor + 1 < minSeq()`, the server sends `WsGap{from: cursor+1, to: minSeq-1}`
   first. In M0 there is no retention, so this cannot fire live; it is unit-tested with a fake
   store.
4. **Delivery has a window of 1:** the server sends the next matching event with `seq > cursor`
   (kinds `finished` and `cancelled` when `terminal`; selectors empty means all jobs), then waits
   for `WsAck{seq}`. On the ack it calls `EventStore.ack` (cursor = `GREATEST(cursor, seq)`), then
   sends the next one. When nothing is pending, it waits on the in-process wake signal, with a 30 s
   re-poll as a backstop.
5. **What this guarantees:**
   - Every matching event is delivered at least once.
   - A crash between print and ack repeats **at most one** line per reconnect, because there is
     never more than one unacked event.
   - Nothing is skipped silently. That rests on the advisory-lock ordering in §5.3 plus
     `cursor = GREATEST(...)`.

**Fallback if the WS spike (§2.4) fails [P]:** long-poll
`GET /api/v1/subscribers/{name}/next?terminal=` (with a 30 s wait) plus
`POST /api/v1/subscribers/{name}/ack {seq}`. It has the same window-1 semantics, and the CLI's
`EventTransport` interface hides which one is in use.

### 6.6 Cancel, timeouts and the result record

**Cancel**
- `CancelJob` calls `JobStore.requestCancel`.
- For units in CANCELLING, it sends `CancelUnit` if the link is UP. Otherwise reconcile sends it
  after re-attach.
- The verdict after the kill is CANCELLED unless VOID or ERROR applies (§3.6).

**Timeouts** are enforced in the worker (§7.5). The controller only stores `timeout_s` and passes
it in `StartUnit` and `Reattach`.

**Result record:** built from the `Evaluation` and stored as `results.record`.
- §5.4 shape.
- `timings.queued` = first `started_at − submitted_at`.
- `timings.run` = last `ended_at` − first `started_at`.
- `evidence` = `{"stored": false}` in M0, but **no** `evidence=missing` annotation. The evidence
  store is M1, and that annotation applies only to failed copies.

---

## 7. temper-worker (owned by WP4)

### 7.1 Host layout (§4.1) and files written

```
~/.temper/
  worker-<ver>.jar   bin/worker-launch   worker.lock   known_hosts(controller side)
  mirror.git/                              bare mirror (worker's own; separate from ~/work/pragmatica.git)
  seed-m2/                                 third-party seed (§7.3)
  runner/<ver>/{Dockerfile,RunSteps.java}  extracted from the jar for `docker build`
  jobs/<jobId>/
    ws/                                    checkout; ws/.m2-local (COPY of seed-m2); ws/.home (container HOME) [P]
    units/<unit>/
      .temper/ args  timeout  marker  start  container  image  network  rc  end  killed  facts-start.json  facts-end.json
      reports/            copied XML (only those newer than the marker)
      threaddumps/<pid>.txt
      log                 `docker logs` output, written at exit
```

- **Additions to spec §4.1 [P]:** `timeout`, `network`, `killed`, `threaddumps/`, and `container`
  written at start as well as at exit (re-attach needs it).
- **Atomic writes:** every file is written to `*.tmp` and then `FileOps.moveAtomic`.
- **`rc` comes first:** `rc` is always written before anything else at exit, because it is the
  re-attach discriminator.

### 7.2 Frame loop (`…worker.Main`, `…worker.link.FrameLoop`)

1. Rebind stdout to a dedicated stream (§4.1 of this doc).
2. **Acquire `~/.temper/worker.lock`** with `FileChannel.lock()`, waiting up to 30 s.
   - This makes the host single-worker: a dying predecessor (ssh dropped and SIGHUP not yet
     processed) cannot race a new worker's `docker run`.
   - On failure, exit 75, and the controller backs off.
3. Scan `jobs/*/units/*` into `DiskUnitDto`: marker present, `rc` contents, and container state
   from `docker inspect -f {{.State.Status}} <name>`, mapped to RUNNING, EXITED or ABSENT. Then
   send `Hello`.
4. Run a read loop on a virtual thread. Each request is dispatched to a handler that returns
   `Promise<Message>`, and the reply is written by the synchronized `FrameWriter`.
5. **EOF on stdin:** cancel pending work and exit 0. Containers keep running because they are
   detached.

`Heartbeat` is sent every 10 s via `SharedScheduler`.

### 7.3 Workspace preparation (`PrepareJob`) and seed isolation (A13)

`WorkspacePreparer` is a sequencer, and it is idempotent:

1. **Mirror.** On first use `git clone --mirror <origin> ~/.temper/mirror.git`. Otherwise run
   `git --git-dir … fetch --prune origin` (serialised by an in-process lock), then
   `git --git-dir … cat-file -e <sha>^{commit}`.
2. **Checkout**, only if `ws/.git` is absent:
   `git clone --no-checkout --quiet ~/.temper/mirror.git ws`, then
   `git -C ws checkout --quiet --detach <sha>`.
   - A local clone hardlinks `.git/objects`. That is harmless, because git objects are immutable.
     The seed rule concerns `.m2-local`.
3. **Seed**, only if `ws/.m2-local` is absent: run `Seeder.copy(seed-m2, ws/.m2-local.tmp)`, then
   do an atomic rename.
   - The production `Seeder` is a Java NIO walk with `Files.copy(…, COPY_ATTRIBUTES)`. A plain copy
     always creates new inodes, so the link count is 1.
   - **[P]:** this is chosen over `cp -a --reflink=auto`, which is faster on btrfs/xfs and also
     safe, because it is portable (tests run on macOS) and has no GNU dependency.
   - Cost: a full copy per job. See risk R5.
4. **Verify:** walk `ws/.m2-local`, read `Files.getAttribute(p, "unix:nlink")` into
   `FileLinks`, and call `SeedIsolation.verify`. A failure is
   `Failure{code: "seed-isolation"}`, and the controller turns that into `ERROR(seed-isolation)` for
   every unit of the job. **This runs before any unit starts.**
5. Create `ws/.home`.

**`seed-m2` itself** is created at host registration by `ops/seed-host.sh`, which copies
`~/work/m2-seed` into `~/.temper/seed-m2` with `cp -a --no-preserve=links`, never `-l` (plain `cp -a` would
reproduce hard links internal to the source). That step reads from
`~/work` on the host, so it is owner-run, and its first run needs a ruling (Q-10).

### 7.4 Unit runner (`StartUnit`)

`UnitStarter` is a sequencer. Every docker call uses `ProcessRunner` with `inheritEnv=false`.

1. If the unit is in the cancelled set, reply `UnitStartFailed{CANCELLED}`. This also covers a
   CancelUnit that raced ahead of the start.
2. **R-DISK.** Read `FileStore.getUsableSpace` for the workspace filesystem and for
   `docker info -f {{.DockerRootDir}}`, and call `Refusals.disk(…)`. A breach gets
   `UnitStartFailed{VOID, "disk"}`.
3. **RunnerImage.ensure(version)**, serialised:
   - If `docker image inspect temper/runner:<ver>` fails, extract the resources and run
     `docker build -t temper/runner:<ver> --label temper.runner=<ver> <dir>`.
   - Return the image id from `docker image inspect -f {{.Id}}`.
4. Write `.temper/args` (steps JSON) and `.temper/timeout` (ISO duration).
5. **Network.** `docker network create --label temper.job=<job> --label temper.unit=<unit> temper-<job>-<unit>`,
   skipped if it already exists. The gateway is read with
   `docker network inspect -f '{{(index .IPAM.Config 0).Gateway}}' <net>`.
6. Write `facts-start.json` (§7.7).
7. **Marker:** `.temper/marker` = the current epoch nanos as text, from
   `Instant.now()` → `seconds*1e9 + nanos`, with `Math.multiplyExact`.
8. Run `docker run -d …` with argv from `DockerRunArgs.build(UnitLaunch)`, which is pure and
   pinned by tests:

   ```
   docker run -d --name temper-<job>-<unit>
     --label temper.job=<job> --label temper.unit=<unit> --label temper.version=<ver>
     --network temper-<job>-<unit>
     --user <uid>:<gid> --group-add <dockerGid>
     -v /var/run/docker.sock:/var/run/docker.sock
     -v <ws>:<ws>  -w <ws>
     -e JAVA_HOME=/opt/java/openjdk -e PATH=<image PATH> -e HOME=<ws>/.home -e TZ=UTC
     -e TESTCONTAINERS_HOST_OVERRIDE=<gateway> [-e MAVEN_OPTS=<cfg>] [-e TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=<cfg>]
     --memory <memoryMb>m --cpu-shares <cpuShares>
     temper/runner:<ver>
     java -cp /opt/temper RunSteps <n1> <step1 argv…> <n2> <step2 argv…>
   ```

   - Each step's argv is passed as separate docker argv elements. `${ws}` is substituted only in the
     planner's own `-Dmaven.repo.local` token.
   - **Count-prefixed steps** avoid any separator or escaping (R-SHELL).
   - **uid, gid and dockerGid** come from the worker's own process (`unix:uid` of `$HOME`) and from
     `unix:gid` of `/var/run/docker.sock`.
   - **Assumption:** the ssh user is `builder`, the user spec §4.2 names.
9. Write `.temper/container` (the container id), `.temper/start` (epoch nanos) and
   `.temper/image` (the runner image id).
10. Reply `UnitStarted`.
11. Arm the unit: start a `docker wait` watcher on a virtual thread and schedule the timeout (§7.5).
12. **Failure handling:** any failure in steps 3–8 replies `UnitStartFailed{ERROR, "infra: …"}`.
    It is an infrastructure failure before mvn ran, which §5.3 classes as ERROR. The worker then
    removes any half-created network or container.

**`RunSteps.java`** is shipped in the image and compiled at image build. It is not a shell:

- Its env is `System.getenv()` filtered to the R-ENV allow-list (§2.5).
- **Why it filters:** the temurin image's own `ENV` (LANG, JAVA_VERSION, …) would otherwise leak into
  the mvn process. Docker cannot unset an image ENV, so the allow-list is enforced twice, once by
  `-e` and once by the launcher.
- It runs the steps in order through `ProcessBuilder(step).inheritIO()` with
  `environment().clear(); putAll(filtered)`.
- It exits with the first non-zero rc, or 0.

**On container exit** (when the `docker wait` watcher returns, or on `Collect`):
1. Write `.temper/rc` (the wait output, or `docker inspect -f {{.State.ExitCode}}`) and
   `.temper/end`.
2. Write `log` from `docker logs <c>`, capping it at the last 200 MiB **[P]**.
3. Collect reports with `ReportCollector`:
   - walk `ws` for `**/target/{surefire,failsafe}-reports/TEST-*.xml`;
   - build `ReportCandidate(path, mtime in ns)`;
   - call `ReportSelection.newerThan(marker text)`;
   - copy the selected files to `reports/`;
   - run `ReportParser.parse` on each.

   Any parse failure sets `errorReason = "report"`, and the rows parsed so far are still sent as
   observed failures.
4. Write `facts-end.json`.
5. Send `UnitExited` and the `UnitReport` chunks.
6. On `UnitReportAck`, remove the container and its network:
   `docker rm -f temper-<job>-<unit>` then `docker network rm temper-<job>-<unit>`.

### 7.5 Timeouts that survive an ssh drop (§4.3)

- `UnitTimer.arm(ref)` computes `deadline = start(nanos from .temper/start) + timeout(.temper/timeout)`,
  then calls `SharedScheduler.schedule(fire, max(0, deadline − now))`.
- **It is armed twice:** at start, and on `Reattach`, which carries the controller's authoritative
  timeout. A deadline that passed while no worker was attached therefore fires immediately on
  re-attach.

`fire` runs these steps:

1. Write `.temper/killed` = `timeout`.
2. Run `docker exec <c> jcmd -l` and take every pid whose main class is not `sun.tools.jcmd.JCmd`.
3. For each pid, run `docker exec <c> jcmd <pid> Thread.print` and write
   `threaddumps/<pid>.txt` with a 60 s per-call timeout. A dump that fails is written as
   `threaddumps/<pid>.err`.
4. Run `docker kill <c>`.

The normal exit path then records rc 137, and `UnitExited.killedBy = "timeout"` (from the `killed`
file), which gives `FAILED(timeout)`.

**Honest guarantee:**
- The bound is enforced while a worker is attached.
- If no worker is attached at the deadline, because the controller is down or the link is in
  backoff, enforcement is **deferred to the next re-attach**, and the timer fires then. The
  bound is not reset.
- **Alternative [P, not in M0]:** a `setsid` per-unit watchdog process, like bb-hold's detachment,
  would enforce the bound with no controller at all. Raise it if deferred enforcement is not
  acceptable.

**Testcontainers siblings:**
- Containers started through the socket are not inside the unit container, so `docker kill` doesn't
  stop them.
- Ryuk reaps them when the killed JVM's connection drops, but that is **[U]** for this setup.
- A label sweep (`--filter label=org.testcontainers.sessionId=…`) isn't possible in M0, because the
  worker doesn't know the session id. **M0 relies on Ryuk** and records this as risk R7.

### 7.6 Runner image (§4.6)

`src/main/resources/runner/Dockerfile` (WP4):

```dockerfile
FROM eclipse-temurin:25-noble@sha256:<DIGEST>          # WP4 pins: docker pull + docker inspect --format '{{index .RepoDigests 0}}' on bigboy [U]
ARG MAVEN_VERSION=3.9.12
ARG MAVEN_SHA512=<128 hex>                             # WP4 copies from https://downloads.apache.org/maven/maven-3/3.9.12/binaries/apache-maven-3.9.12-bin.tar.gz.sha512
                                                       # and cross-checks archive.apache.org; the constant is committed, never fetched at build [U]
RUN curl -fsSLo /tmp/m.tgz https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
 && echo "${MAVEN_SHA512}  /tmp/m.tgz" | sha512sum -c - \
 && tar -xzf /tmp/m.tgz -C /opt && ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven && rm /tmp/m.tgz
ENV PATH=/opt/maven/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
COPY RunSteps.java /opt/temper/RunSteps.java
RUN javac -d /opt/temper /opt/temper/RunSteps.java
```

- The image id is recorded as `.temper/image` and `units.image_id`, which appears as `units[].image`
  in the result.
- **Whether `git` is needed in the image is [U].** If the pragmatica build uses a git-commit plugin,
  add `git` from apt pinned by version. WP4 checks this by running one ModuleTest.
- The `curl` in the build is not a request field and never touches unit data; R-SHELL does not apply
  to it.

### 7.7 Facts (§4.5) (`…worker.facts.FactsProbe`)

| Fact | Source |
|---|---|
| load1, load5 | `/proc/loadavg` |
| cores | `Runtime.availableProcessors()` |
| MemAvailable | `/proc/meminfo` |
| free disk | `FileStore.getUsableSpace` for the workspace filesystem and the docker root |
| foreign reactors | `/proc/*/cmdline` (NUL-separated) and `/proc/<pid>/cgroup`, collected into `ProcInfo`; temper container ids from `docker ps -q --no-trunc --filter label=temper.unit`; then `HostReactors.foreign` |
| temper units running | the count of those container ids |
| bb-* tickets | `none()` (Q-10) |

**`memory.peak` and `cpu.stat` at the end of a unit:**
- The container's cgroup is removed when it exits, so they cannot be read after exit.
- The worker samples `/sys/fs/cgroup/system.slice/docker-<id>.scope/{memory.peak,cpu.stat}` on every
  heartbeat (10 s), keeps the last sample, and records it at exit together with `sampledAt`.
- **Honest guarantee:** `memory.peak` is a lower bound, at most 10 s stale. `memory.peak` needs
  Linux ≥ 5.19, cgroup v2 and the systemd cgroup driver on bigboy, all **[U]**. When absent, the
  field is `none()`.

### 7.8 Cancel and log

**`CancelUnit`**
1. Add the unit to the cancelled set.
2. If the container exists: write `.temper/killed` = `cancel`, then `docker kill <c>`. The exit path
   records rc 137 with `killedBy=cancel`.
3. Reply `Ack`.

**`LogTail`**
- When `units/<u>/log` exists: tail the file, reading backwards at most 1 MiB.
- Otherwise: `docker logs --tail <n> <c>`.

---

## 8. temper-cli (owned by WP5)

**Configuration**
- URL: `TEMPER_URL`, otherwise `~/.config/temper/cli.toml` `url`, otherwise
  `http://bigboy:7070/api/v1`.
- Token: `~/.config/temper/token`. A missing file is exit 64. Permissions looser than 0600 print a
  warning to stderr **[P]**.
- Owner: `--owner`, otherwise `TEMPER_OWNER`, otherwise `BB_OWNER`. A missing owner is exit 64
  **[G]**.

**Transport**
- `HttpTransport` is an interface returning `Promise<HttpReply>`. It is implemented over the JDK
  `java.net.http.HttpClient` with a 10 s connect timeout, wrapped in `Promise.lift`.
- `EventTransport` is implemented over the JDK `java.net.http.WebSocket` (or the long-poll fallback,
  §6.5).
- A connection failure is exit 69.

**Command parsing**
- `CommandLine.parse(String[])` → `Result<CliCommand>` is pure and hand-rolled (no picocli in
  `.m2-local` [U]).
- A parse failure prints usage and exits 64.

| Command | Line (stdout) | Exit |
|---|---|---|
| `submit module-test\|mvn [--ref R\|--sha S] [--pl a,b] [--test T] [--it-test T] [--prio N] [--keep] [--fresh] [--unit-timeout PT90S] [-- mvn args]` | `submit 4711 QUEUED sha=0cf5631 recipe=module-test deduplicated=false` | 0 |
|  | `submit 4711 PASSED sha=… recipe=… deduplicated=true` | 10 |
|  | `submit - REFUSED code=R-M2 detail="…" job=4712` | 2 |
| `submit gate\|mutate`, `--bundle`, `--patch` | `submit - USAGE not in M0` | 64 **[G]** |
| `wait <job> [--timeout 6h]` | `wait 4711 FAILED reason=tests testcases=12 failed=1 observed-failures=1` | verdict code; 7 on timeout (the line shows the current state) |
| `result <job> [--json]` | `result 4711 PASSED testcases=12 failed=0 skipped=0 units=1` | verdict code; **9 if not yet terminal [G]** |
| `watch --terminal --subscriber <name>` | one event `line` per event (already contains `seq=`) | runs until killed; reconnects with backoff (1→60 s) forever; exits 64 on usage, 77 on unauthorized, 5 on closed stdout, 69 on an undecodable event (Q-15; corrected after WP5a verification) |
| `cancel <job>` | `cancel 4711 CANCELLING` | 0 if accepted; if the job was already terminal, prints its verdict and exits with its code **[G]** |
| `log <job> [--unit U] [--tail N]` | raw text (the only multi-line command) | 0; 5 when the host is unavailable (HTTP 503) **[G]** |

- **`--json`** prints the full DTO instead of the line, and the exit code is unchanged.
- **`wait`** polls `GET /jobs/{id}` every 2 s, backing off to 10 s. Up to 5 consecutive connection
  failures are tolerated (controller restart) before exit 69.
- **HTTP 401** has no code in §8.1, so M0 uses **77** **[G]** (Q-15).
- **`watch` ordering:** print the line → `System.out.flush()` → send `WsAck`. The order is pinned by
  a test (§9, A5).
- **Exit codes** come from the shared `ExitCode` enum (§3.9) only.

---

## 9. Test plan per acceptance criterion

**Test tiers:**

| Tier | Where | When |
|---|---|---|
| **unit** | pure tests in the default build | every build |
| **it-pg** | testcontainers `postgres:16` (needs docker) | default build where docker exists; tag `pg` |
| **host** | real bigboy: docker, ssh to localhost, the real worker; module `temper-acceptance`, profile `-Pacceptance` | M0 exit only |

**Rules for host-tier runs:**
- Use non-default ports (controller 17070, PG 55433) and the job-name prefix `temper-acc-`.
- Run in a maintainer-approved window, under the shared-machine lock rules of the global conventions.
  Taking the shared suite lock (`~/<workspace>/.aether-suite-lock`) is the operator's call, not temper's
  (Q-13).

| AC | Tier | Test(s) and owner | Mutation probe / control |
|---|---|---|---|
| **A1** | unit | WP1 `RefusalsTest`: one test per code (R-1553 enabled class present and no exclusion; R-FAILSAFE `-pl aether/forge/forge-tests -Dtest=X`; R-ENV `env` field; R-M2 `-Dmaven.repo.local=/x`, `-D maven.repo.local=/x`, `-s s.xml`; R-SHELL `exec:exec`; R-DISK free < floor). Each passing control (e.g. the class carries `@Disabled("#1553")`) is asserted OK. WP3 `SubmitJobTest` (fakes for GitRemote, TreeReader and the stores): POST the offending form, assert 422 with `refused=<code>`. WP4 `UnitStarterTest` (fake fs and ProcessRunner): low disk gives `UnitStartFailed{VOID,disk}` and **no** `docker run`. WP4 `DockerRunArgsTest`: no `sh`, `bash` or `-c`; no `.m2` mount; env keys ⊆ allow-list even when the host env has `HCLOUD_TOKEN` | The verifier deletes each element of `Refusals.checkSubmit`'s list in turn (and the `disk` call in `UnitStarter`), and exactly the matching test goes red. Each outcome is recorded in the M0 notes |
| **A2** | unit | WP1 `VerdictsTest` + `ReportParserTest` + `ReportSelectionTest`, on fixture XML under `temper-domain/src/test/resources/reports/`: EMPTY (unit requests tests, 0 rows); missing classes (manifest fixture); duplicated class across two units gives `ERROR(plan)`; `tests=` undercount (`@Nested`: suite `tests=3`, 5 `<testcase>`; counts use 5); stale XML (mtime < marker gives excluded); copied marker (marker text T, report mtime T−1 s, the marker file's own mtime T+10 s is **not even an input** and the report is excluded). WP4 `ReportCollectorIT` repeats the stale and copied-marker cases with real files and `Files.setLastModifiedTime` | Changing `>` to `>=`, or reading the marker's mtime, turns the selection tests red |
| **A3** | unit | WP5 `ExitCodeContractTest`: a table test over every row of §8.1 using a fake `HttpTransport`. Covers submit accepted/dedup/refused; wait for each verdict and timeout; result pending; usage error; connection refused (69). WP1 `ExitCodeTest` pins the enum numbers | Changing any enum number turns it red |
| **A4** | host | WP5 `ReattachAcceptanceIT`. **Case 1:** submit an Mvn job on a fixture repo whose single test sleeps 90 s; when RUNNING, kill the ssh pid (`pgrep -f 'ssh -T .* run <ver>'`), stop and restart the controller JVM. Assert: the unit ends PASSED; `docker inspect` shows the same container id as in `.temper/container`; the marker is unchanged; the DB unit has 1 `started_at` and its testcases. **Case 2:** stop the controller, `docker rm -f` the container, make sure no `rc` exists, restart. Assert `ERROR(lost)` | Control: delete the reconcile `Reattach` branch, and case 1 must fail |
| **A5** | it-pg + unit | WP3 `EventCursorIT`: a raw JDK WebSocket client subscribes, receives seq N, closes **without ack**, reconnects, and receives seq N again. It then acks N, reconnects, and receives N+1. A second test inserts events from two concurrent transactions and asserts the client never skips. WP5 `WatchOrderTest`: a fake transport records the call order and asserts print+flush happens before ack; a fake that throws after print gives no ack | Removing the advisory lock must break the concurrency test. This is probabilistic: loop 200× |
| **A7** | unit + host | WP1 `HostReactorsTest`: a fixture `/proc` snapshot (launcher inside the `docker-<id>.scope` of a temper container, temperActive=1) gives 1; plus one foreign launcher gives 2; plus the cgroupfs path form. WP3 `GrantPolicyTest`: cap 1 and reactors 1 give no grant; backfill; starvation after 1200 s. WP5 `CapCountAcceptanceIT` (host, quiet window: no bb-* reactors): one unit RUNNING, then `GET /hosts` gives `reactors == 1` | Counting all launchers from `ps` gives 2, so the test goes red |
| **A8** | unit | WP1 `VerdictsTest.perUnitEmpty`: unit `a` rc 0 with 12 rows, unit `b` rc 0 with 0 rows and `requestsTests`, gives EMPTY | — |
| **A9** | unit + it-pg | WP1 `DedupKeyTest` (canonicalisation; `reusable` table). WP2 `JobStoreDedupIT`: for each of VOID, ERROR and CANCELLED, a resubmit creates a new id; for PASSED, FAILED, EMPTY (and QUEUED, RUNNING), it returns the old id with `deduplicated=true`; `fresh` always creates a new id; two concurrent identical submits give one job | Removing the advisory lock makes the concurrent case flaky-red |
| **A10** | unit + host | WP4 `UnitTimerTest` (fake clock and ProcessRunner): fires at deadline; on re-attach after the deadline it fires immediately; dumps happen before the kill. WP1 `VerdictsTest.timeoutNotEmpty`. WP5 `TimeoutAcceptanceIT`: an Mvn job whose test sleeps 10 min, `--unit-timeout PT60S`; assert `FAILED(timeout)`; `threaddumps/` has one file per JVM listed by `jcmd -l` in the container (RunSteps + mvn + surefire fork ≥ 3), each containing `Full thread dump` | Removing the dump step gives 0 files and a red test |
| **A13** | unit + local fs | WP1 `SeedIsolationTest`. WP4 `WorkspacePreparerSeedIT`, which runs without docker but needs a POSIX fs: build a temp seed, run the production `Seeder`, and assert `verify` is OK and every nlink is 1. **Negative:** seed with `cp -al` (a `HardlinkSeeder` test double, or the real `cp -al` on Linux/macOS), and preparation must fail with `seed-isolation` | Swapping the production Seeder to `Files.createLink` turns the positive test red |

**Fixture repo for host tests:**
- `temper-acceptance/src/test/resources/fixture-repo/` is a tiny Maven project: one module, a
  sleeping test whose sleep comes from a system property, and a passing test.
- At test setup it is pushed to a local bare repo used as `repo.origin`.
- No network or GitHub access is needed, except for the runner image's base pull.

---

## 10. Work packages

**Dependencies:**

```
WP1 (shared types) ──► WP2 store ──(api commit)──► WP3 controller ──┐
         │                                                           ├──► WP5b acceptance (host)
         ├──────────────────────────────► WP4 worker ────────────────┘
         └──────────────────────────────► WP5a CLI
```

**Order:**
1. WP1 lands first. WP2 can start against WP1's `.domain`/`.protocol.api` as soon as WP1's
   value-object and DTO commits exist.
2. WP2's **first commit is `…store.api` verbatim from §5.3**. WP3 starts on it, with in-memory fakes
   for its unit tests.
3. WP3, WP4 and WP5a then run in parallel.
4. WP5b (acceptance) runs last, after WP3 and WP4.

**File ownership.** No file is edited by two packages.
- WP1 creates the parent POM **and one stub `pom.xml` per other module** (artifactId and
  dependencies from §1.3).
- Ownership of each stub then transfers to that module's package. After WP1 merges, WP1 never
  touches them again.

| WP | Owns (exclusive) | Content | Size (main + test LOC, est.) |
|---|---|---|---|
| **WP1** shared types | `pom.xml`, `jbct.toml` (if any), `temper-domain/**`, `temper-protocol/**`, initial stubs of the 4 other module poms | §3 (all), §4 (codec, messages, API DTOs, WS DTOs), §2.6 `ProcessRunner`; tests A1-domain, A2, A3-enum, A7-pure, A8, A9-key, A13-pure; DTO round-trip test (sealed `WRAPPER_OBJECT` on Jackson 3) | **L**: ~2.8k + 2.2k |
| **WP2** store | `temper-store/**`, `ops/pg-up.sh` | §5: `Db`, `MigrationRunner`, V1, every repository, write-once triggers, grant SQL, event advisory lock; ITs: dedup, write-once (UPDATE/DELETE/TRUNCATE rejected), grant conditional update, event ordering, Instant/jsonb/text[] binding | **M**: ~1.4k + 1.1k |
| **WP3** controller | `temper-controller/**`, `ops/temper-controller.service` (systemd user unit, optional) | §6: config, HTTP glue + router + routes, **WS spike first**, SubmitJob (+ GitRemote, Mirror, TreeReader adapters), GrantPolicy, Reconciler, scheduler loop, HostLink (ssh, handshake, backoff, deploy), events/WS, cancel, result record | **XL**: ~3.2k + 1.8k |
| **WP4** worker | `temper-worker/**` (incl. `runner/Dockerfile`, `runner/RunSteps.java`), `ops/worker-launch`, `ops/seed-host.sh` | §7: frame loop, host lock, scan, WorkspacePreparer + Seeder + seed verify, UnitStarter, DockerRunArgs, UnitTimer + thread dumps, ReportCollector, FactsProbe, cancel, log tail, image pinning (digest + sha512) | **L**: ~2.4k + 1.5k |
| **WP5** CLI + acceptance | `temper-cli/**`, `ops/temper` (wrapper), `temper-acceptance/**` (module + `acceptance` profile entry; WP1 adds only the `<profile>` stub) | §8 (5a); §9 host ITs A4, A7-live, A10-live + fixture repo (5b) | **M**: ~1.2k + 1.4k |

**Handoff contract for every package:**
- `jbct-reviewer` reviews the package.
- An independent verifier, not the author, runs the mutation probes listed in §9.
- The build is a root `mvn install` (full reactor, no `-pl`) through `build-runner`, which never
  writes `~/.m2`.

---

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The rc4 `http-server` binds every interface, which includes the docker bridges, so unit containers can reach :7070 | Bearer token on every route (units never get the token); Q-1 asks for a ruling |
| R2 | The WebSocket wiring defect is suspected but unverified | WP3 spike first; the long-poll fallback (§6.5) keeps the same CLI contract |
| R3 | Jackson 3 + 2.21 annotations on sealed records: behaviour unverified | WP1's first test round-trips the DTOs; the fallback is manual `JsonNode` mapping in the protocol module |
| R4 | postgres-async binds `Instant` as `timestamp` and `String` as varchar, so data could be silently wrong | Bind `OffsetDateTime`, use `::jsonb` and `::text[]`, run PG with `timezone=UTC`, and pin all of it with ITs |
| R5 | Copy-seeding `.m2-local` per job costs time and disk (the seed size is unknown) | Measure on the first host run. If too slow, use `cp -a --reflink=auto` (a reflinked copy has nlink 1, so it is still A13-safe) on a reflink-capable fs; whether bigboy's fs is one is **[U]** |
| R6 | Timeout enforcement is deferred while no worker is attached | Documented guarantee; optional setsid watchdog (§7.5) |
| R7 | Testcontainers siblings survive a timeout or cancel kill if Ryuk fails | Rely on Ryuk; add a label-based sweep in M1 once session labels are known |
| R8 | `memory.peak` is a lower bound (10 s sampling), and kernel/cgroup support is unverified | Record `sampledAt`; field is `none()` when unsupported |
| R9 | Offline availability of the plugins' transitive deps is unverified | `build-runner` runs `mvn -o install` once after WP1 |
| R10 | The base image digest and the Maven sha512 are not yet known | WP4 obtains and commits them; a build without them must fail (`ARG` with no default) |
| R11 | Local `git clone` hardlinks `.git/objects`, which is easy to confuse with A13 | A13 scope is `.m2-local` only, and the test walks only that directory |

---

## 12. Spec ambiguities and guesses (for the maintainer; not resolved silently)

| Q | Spec text | Conflict / gap | M0 guess |
|---|---|---|---|
| Q-1 | §7 "bound to the LAN interface only" | rc4 `HttpServerConfig` has no bind address (`NettyHttpServer.java:128`) | Bind all + token. Options: (a) add `bindAddress` to rc4 `HttpServerConfig` (the maintainer's tree), (b) host firewall, (c) JDK `HttpServer` + long-poll |
| Q-2 | §7 `WS /subscribers/{name}/events` | rc4 WS paths match exactly and can't see the upgrade headers | `/api/v1/events`; name + token in the first message |
| Q-3 | §2.5 R-1553 "unit args must exclude it" | Is a positive `-Dtest=Foo` (which cannot run the class) an exclusion? Read literally, every ModuleTest is refused while the class is enabled | Strict: refuse unless `@Disabled("#1553")` or an explicit `!` exclusion |
| Q-4 | §3.1 port group "until the spike passes"; bb-hold is M1 | M0 has no bb-hold, so port-group units can't hold the group. The closure rules (`bb-portgroup.py`) and the module list were not provided | Refuse port-group units in M0 (`REFUSED(port-group)`); the module list comes from config |
| Q-5 | §3.1 ModuleTest with `itTest` | With `itTest` set and `test` absent, `verify` also runs all surefire tests of `pl`. Is that intended? | Run as specified (`verify -Dit.test=…`), with no surefire suppression |
| Q-6 | §4.3 "a submit can override it per recipe" | There is no field in §2.1; A10 needs it | `timeout` (ISO duration) at the top level of the submit; it is part of the dedup key |
| Q-7 | A1 "submits the offending form" for R-ENV, R-SHELL, R-M2(-s) | §2.1 has no env field and shell is structural | R-ENV: an `env` field is refused. R-SHELL: `exec:exec`, `exec:java`, `antrun:run`, `-Dexec.executable` are refused. R-M2: `-s`/`--settings`/`-gs` are also refused |
| Q-8 | §5.3 order EMPTY before FAILED vs A10 `FAILED(timeout)` | A timed-out unit with a sleeping test has 0 rows, so EMPTY would win | A timeout-killed unit is excluded from EMPTY. Also: should EMPTY require rc 0 (A8 says "exits rc 0")? Under the literal order, a compile failure in `verify` (rc 1, 0 rows) reports EMPTY. Kept literal |
| Q-9 | §2.1 `{pushed:{sha}}` | Short SHAs? | Only full 40-hex SHAs are accepted |
| Q-10 | §4.5 "bb-* running and waiting tickets"; §6.2 "temper never touches ~/work/queue"; §4.1 seed from `~/work/m2-seed` | Reading is necessary for facts and the seed, but is reading "touching"? The queue format is unknown | `bbRunning`/`bbWaiting` = none in M0; the seed copy is an owner-run script |
| Q-11 | §6.4 "neither → ERROR(lost)" | A unit granted but never started (no marker) | Requeue (safe: marker-before-run + host lock). Otherwise ERROR(lost) |
| Q-12 | §2.4 supersede vs the §12 M0 list | Is supersede in M0? Not in the exit gate | Designed in; cheap |
| Q-13 | Design §7 deploy; §6.2 lock usage | A `restrict`ed forced-command key can't scp the worker jar; host setup (authorized_keys, known_hosts) and acceptance windows need approval | `worker-launch run\|install` verbs; an owner/maintainer-approved host setup step |
| Q-14 | §8.3 new subscriber's cursor | Start at 0 (replay all) or at now? | At the current max(seq) |
| Q-15 | §8.1 table | No code for unauthorized (401); `result` before terminal; `cancel` exit; `log` with the host down | 77; 9; 0 / verdict code; 5 |
| Q-16 | §2.5 "A refusal at submit makes the job REFUSED" vs §7 422 body without an id | Is a refused job persisted? | Persisted (state REFUSED, no units) and `job` added to the 422 body |

### 12.1 Rulings (temper main session, 2026-10-13; sent to the maintainer, who may override)

| Q | Ruling |
|---|---|
| Q-1 | Accept the guess for M0: bind on all interfaces and require the bearer token on every route. The code in unit containers is same-repo and trusted, and the token still applies. Bind address or firewall: revisit at M4 |
| Q-2 | Accept: `/api/v1/events`, with name and token in the first message |
| Q-3 | **Refined.** R-1553 refuses a unit only if its selection *could run* `StreamPublishReshuffleTest`. Either there is no `-Dtest`/`-Dit.test` in a module whose tests include it, or a positive pattern glob-matches it. An explicit `!` exclusion or `@Disabled("#1553")` passes. A positive `-Dtest=Foo` that cannot match passes |
| Q-4 | Accept: `REFUSED(port-group)` in M0. Config `portGroup.modules = [aether/ember, aether/forge/forge-tests]`; a whole-reactor run is a member; `-DskipTests` is not a member (bb-portgroup.py semantics) |
| Q-5 | Accept, literal |
| Q-6 | Accept: top-level `timeout`, part of the dedup key |
| Q-7 | Accept |
| Q-8 | **Spec correction.** EMPTY requires **rc 0** plus 0 testcases in a unit that requested tests; that is the trap EMPTY exists for. rc ≠ 0 with 0 rows is FAILED(rc). A timeout-killed unit is FAILED(timeout). The order becomes VOID > ERROR > CANCELLED > FAILED(timeout) > EMPTY(rc 0) > FAILED > PASSED |
| Q-9 | Accept: the API takes full 40-hex SHAs only. The CLI may expand a short SHA through the controller's mirror before submitting |
| Q-10 | **Reading is not touching.** The worker may *read* `bb-queue` output and `~/work/m2-seed` (copying out of it). It never writes anything under `~/work` |
| Q-11 | Accept: requeue |
| Q-12 | Accept: designed in |
| Q-13 | Accept the `install` verb. Adding a forced-command key to bigboy's `authorized_keys` needs maintainer approval, requested |
| Q-14 | Accept: current max(seq) |
| Q-15 | Accept: 77; 9; 0 or the verdict code; 5 |
| Q-16 | Accept: persisted, with the job id in the 422 body |
| Deviations | Accept both: the process adapter lives in temper-protocol; the `temper-acceptance` module is profile-gated |

### 12.2 maintainer confirmations (2026-10-13)

**Confirmed rulings:**
- **Q-1, Q-3, Q-4, Q-8, Q-10:** confirmed.
- **Required tests:**
  - Q-8: a compile failure in `verify` → FAILED(rc); A10 → FAILED(timeout).
  - Q-3 matcher: no selection; `*Test`, `Stream*`, `*Reshuffle*` (refused); `Foo*` (allowed); `!StreamPublishReshuffleTest` (allowed); `-Dit.test` vs `-Dtest`. The rule stays a refusals-table row.
  - Q-1: a route without the token → 401.

**Q-13, approved with conditions (WP4 implements; the main session runs the host step):**
1. Generate the keypair ON bigboy. The private key stays in `~/.temper/` (mode 0600) and never leaves the host.
2. The `authorized_keys` entry is
   `restrict,from="127.0.0.1,::1",command="exec ~/.temper/bin/worker-launch" <pubkey>`.
   The localhost restriction stays until M4.
3. `worker-launch install` writes only under `~/.temper/`, verifies sha256 before an atomic rename,
   and refuses everything else. `run` execs only a jar whose hash matches the recorded one.
4. Back up `authorized_keys`, then append exactly one line and change nothing else.
5. Record the line, its fingerprint and how to revoke it (delete the line) in
   the bigboy host record, and notify the maintainer.

### 12.3 bb-hold delivered (maintainer, 2026-10-13); used from M1 (WP4 must know this)

**Location:** `~/tools/bb-hold`, sha256 `2c2eb469…`. It is identical on bigboy, cloudbb-1 and
cloudbb-2.

**Usage:** `bb-hold <owner> <prio> --container <name> -- <argv>`. On success it:
- prints `granted <ticket>` on stderr;
- holds the group until `docker wait` returns;
- exits with the container's rc.

| Event | rc | Effect |
|---|---|---|
| bad args, missing `--container`, empty argv, bad owner, invalid container name | 2 | refused |
| duplicate owner+name | 3 | refused |
| signal | 143 | releases the ticket; **the container keeps running** |

**State:** `~/work/bb-hold/<owner>-<name>/`, holding the pid file and a state file (ticket, container,
start). Reading it is allowed (Q-10).

**Priority:** `<prio>` is advisory. The effective value comes from `~/work/priorities` by owner, and
`temper` is 5.

**kill -9:** bb-* reap marks the ticket orphaned and releases the group within about 4 s, while the
container keeps running. That matches the spec's `VOID(port-group-lost)` plus kill-container rule.

**Cancel order is unchanged:** `docker kill` the container first. `docker wait` then returns, and
bb-hold releases the ticket by itself and exits with 137.

### 12.4 WP4 additions after S1 (maintainer + verifier, 2026-10-13)

- **No fixed host ports (maintainer).** A temper unit's `docker run` never carries `-p`, `--publish` or
  `--network host`. A test on `DockerRunArgs` pins this.
  - Testcontainers publishes container ports on the host through the shared socket, but only on
    random high ports.
  - That guarantee is what makes `portIsolated` safe next to bare-host bb-* builds.
- **Testcontainers reap.** A unit isn't clean until the Testcontainers containers it spawned are
  gone. After the unit container exits, the worker waits up to 60 s for Ryuk to remove the
  `org.testcontainers` containers whose session belongs to the unit. Any leftover is reported in
  the unit's end facts.
- **Port group on bigboy:** keep `bb-hold` for unsharded Gate units until E1 passes. The S1 pass
  justifies `portIsolated` only for non-Gate units.

### 12.5 Ruling on R-FAILSAFE from WP1 (2026-10-13)

**Keep the literal refusal.** `moduleTest{pl: forge-tests, test: X}` is refused.

**Why:** forge-tests has surefire `skip=true`, so a `-Dtest` selection there runs nothing. It would
produce an EMPTY or false-green job, the bb-build rc 10 case. The refusal message points to
`itTest`.

### 12.6 postgres-async rc4 #2181 (maintainer-confirmed, 2026-10-13)

**The bug:** `ErrorResponse.asServerResponse()` swaps SQLSTATE `code` and `level`.

**Rule:**
- No temper code reads SQLSTATE from `response().code()`.
- Classify by the `SqlError` subtype, or read SQLSTATE through one adapter that takes `level()`,
  marked `TODO(#2181)`.

**Canary:** one test asserts the *current, swapped* behaviour, so it **goes red when #2181 is
fixed**, and the workaround is then removed. WP2's `WriteOnceIT` accepts either field, which is
tolerant but not a canary; the WP2 follow-up adds the canary.

**Resolved (2026-10-18):** the released 1.0.0-rc4 on Maven Central fixes the swap; the canary went red in CI, so it and
the either-field tolerance are removed, and `WriteOnceIT` reads SQLSTATE from `code()`. The pre-release rc4 build in the
per-tree `.m2-local` repos (dated 2026-09-16) still has the swap: it is a different binary under the same version.

### 12.7 Open follow-ups from WP4 (to batch into integration, before the M0 gate)

1. **Protocol v1 additions (WP1 area):**
   - a field for Testcontainers leftovers in unit end facts (today they are only in
     `facts-end.json`);
   - a unit id on the unsolicited `lost` message (today it is only in `detail`).
2. **bb-queue facts:** `bbRunning`/`bbWaiting` are `none()`. Parse `~/tools/bb-queue` output
   (read-only, Q-10). The format is in `~/tools/bb-queue` and `bb-queue-lib.sh`; the inventory has
   copies in the scratchpad `bbtools/`.
3. **Version handshake:** the worker reports `0.1.0-SNAPSHOT`, and the controller must expect
   exactly the version of its own build.
4. **JVM list:** dumps take the JVM list from `RunSteps --jvms`, because `jcmd -l` sees nothing
   when the host uid has no passwd entry.

**Integration status (m0-integration):** items 1–3 are done.
- Item 1: `UnitEndFacts.tcLeftovers` (`{"containers":[{"id","session"}],"unverified"}`) travels in `UnitExited`,
  `unit_facts` phase `end` and the result record's units; `Failure.unit` names the unit of an unsolicited `lost`,
  and the controller ends that unit `ERROR(lost)` when it is active on the reporting host.
- Item 2: `bbRunning`/`bbWaiting` come from `~/tools/bb-queue 0`; an unreadable listing gives `none()` plus a
  `HostFacts.notes` entry.
- Item 3: the controller reads its version from a filtered resource; `controller.toml` no longer has (and refuses)
  `version`. `temper-dist` collects the three fat jars of one build, and `DistJarsIT` checks that they agree.

### 12.8 WP3 deviations: rulings (maintainer agreed, 2026-10-13)

- **Events use long-poll; WS is gone.** rc4 http-server WS doesn't deliver Open, Text or Close.
  - `GET /api/v1/subscribers/{name}/next?terminal=` → 200 `event` or `gap`, or 204 after about 30 s.
  - `POST …/ack` → 204.
  - The cursor moves only on ack. The §8.3 contract is unchanged: at-least-once, `seq` dedup and
    an explicit `gap` line.
- **A worker `Failure` frame during reconcile** → that unit is ERROR(infra), a visible loss. It
  needs a test (check in WP3 verification; add one if missing).
- **A git outage at submit** → 503, not REFUSED(source).

### 12.9 rc4 triage results (maintainer, 2026-10-13)

- **#2184, WebSocket.** rc4 http-server is broken: the first endpoint's handler gets nothing, and
  later endpoints never complete the handshake. temper keeps long-poll. WP3's
  `WebSocketSpikeTest` is the canary.
- **`Promise.timeout`.** It is documented as returning a derived promise AND failing the receiver
  (`HttpForwarder:1163` relies on that). temper code applies timeout only to a derived promise it
  owns, never to a shared or cached one. No upstream issue is needed.
