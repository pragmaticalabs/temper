# temper user guide

For people and agents who submit jobs and read their results. Every flag and exit code here comes from the CLI's code:
`temper-cli/src/main/java/org/pragmatica/temper/cli/command/CommandLine.java` (the grammar and the usage text),
`…/cli/CliError.java` and `temper-domain/…/domain/exit/ExitCode.java` (exit codes), and `…/cli/command/Lines.java`
(the one-line outputs).

## 1. Setup

Install the CLI jar and the wrapper (`ops/temper`):

```
mkdir -p ~/.local/lib/temper ~/.local/bin
cp temper-cli/target/temper-cli-<ver>.jar ~/.local/lib/temper/temper-cli.jar
cp ops/temper ~/.local/bin/temper && chmod 755 ~/.local/bin/temper
```

The wrapper runs Java 25+ from `TEMPER_JAVA`, else `$JAVA_HOME/bin/java`, else `java`; `TEMPER_CLI_JAR` overrides the
jar path. A missing jar or java exits 64 from the wrapper. Use a CLI built from the same commit as the controller: the
controller has no version endpoint, and an older CLI cannot decode newer job states (the deploy runbook, step 8).

### 1.1 Configuration (`…/cli/config/CliConfig.java`)

| Setting | Source, first match wins |
|---|---|
| controller URL | `TEMPER_URL`; else the `url = "…"` line of `~/.config/temper/config`; else `http://bigboy:7070`, the CLI's built-in default (`…/cli/config/ControllerEndpoint.java`). The value is `http[s]://host[:port]`; a trailing `/api/v1` is accepted and dropped |
| bearer token | the contents of the file `TEMPER_TOKEN_FILE` names (a leading `~/` is `$HOME`); else `~/.config/temper/token`, trimmed. A named file that is missing is exit 64, never a fall back to the default. It must exist and hold one token of printable ASCII, or the command exits 64. If group or others can read or write it, a warning goes to stderr; use `chmod 600`. A machine that should only read puts the controller's read token here (§1.2) |
| owner (submit only) | `--owner`; else `TEMPER_OWNER`; else `BB_OWNER` |

`~` is `$HOME` as the shell sets it. Example `~/.config/temper/config`:

```
url = "http://bigboy:7070"
```

### 1.2 The read token

The controller can hold a second, read-only token: `[server] readTokenFile` in `controller.toml` (optional, mode 0600;
it must differ from `tokenFile`, or the controller does not start). It passes the verbs that only read (`wait`, `result`,
`log`, `refusal list`, `known list`, `digest`, `jobs`, `alarm-check`): every GET route except `subscribers/{name}/next`.
A verb that writes (`submit`, `cancel`, `refusal add|expire`, `known add|expire`, `subscribe|unsubscribe`, and `watch`,
whose poll creates its subscriber) gets HTTP 403 and exits 77.
Without the key there is no read token.

An agent that runs as the owner's user reads with the read token by setting `TEMPER_TOKEN_FILE` to its file, and gives
itself its own digest cursor with `TEMPER_DIGEST_CURSOR` (§3.9). This selects a token; it is not a boundary: a process
running as that user can still read `~/.config/temper/token`. Only a separate user (or host) keeps an agent read-only.

## 2. Output and exit codes

Every job command prints one line on stdout, `<verb> <job> <STATE|VERDICT> key=value …`. The exceptions: a refused
submit prints `submit - REFUSED …` (no job in that position); a verb not available yet prints `<verb> - USAGE not in
M0`; `refusal add` and `refusal expire` print one `refusal <code> ACTIVE|EXPIRED …` line, and `refusal list` one per
refusal (nothing when there are none); `known` prints `known <id> ACTIVE|EXPIRED …` lines the same way; `log` prints raw
text; `watch` prints one line per event; `digest` prints its own fact lines (§3.9). `--json`, where a verb accepts it,
prints the full response object instead; the exit code is the same. Problems go to stderr, one line each; usage errors
add the usage text. A value with whitespace, `"`, `=` or `\` is printed in double quotes with escapes; `detail`, and
`reason` on refusal and known lines, are always quoted.

| Exit | Meaning |
|---|---|
| 0 | PASSED; submit accepted; cancel taken; `log`, `refusal`, `known` and `digest` succeeded; `gate` PASSED; `diff` with nothing newly failing, no added failing test, and tests on side B |
| 1 | FAILED (a job, or the `gate` answer); `diff` with a newly failing or an added failing test, or a side B that ran no tests |
| 2 | REFUSED (a submit refused, or a refused job shown); `refusal add` of a code that exists (HTTP 409); `known add` of a test with an active entry (HTTP 409); `digest` with a cursor not from this event log (HTTP 409) |
| 3 | EMPTY (a job, or the `gate` answer) |
| 4 | VOID |
| 5 | ERROR; also `log` when the job's host is down (HTTP 503), and stdout closed while printing |
| 6 | CANCELLED |
| 7 | `wait`, or `gate --wait`, reached its timeout |
| 8 | `gate`: NONE, no Gate at that SHA decides anything; `diff`: no base, a conflicting base, a side pruned by retention, or a side not judged (VOID, ERROR, CANCELLED, REFUSED) |
| 9 | `result` of a job that is not terminal yet; `gate` RUNNING; `diff` with a side not terminal |
| 10 | submit deduplicated: the existing job's id is printed |
| 11 | `gate`: CONFLICT, the newest judge's Gates at that SHA disagree; never read it as green or as a plain red |
| 64 | usage or local configuration error; an unknown job id or known id (HTTP 404); a request the controller rejected (HTTP 400, also a `gate` prefix that matches Gates at two SHAs); a verb or form not available yet |
| 69 | controller unreachable, or an answer outside the route's contract (an unexpected status such as 503 from submit, an undecodable body) |
| 77 | the controller rejected the token (HTTP 401), or refused a write to the read token (HTTP 403) |

`alarm-check` has its own meanings for 0, 2, 3 and 64 (§3.8), and `events --until` for 3 to 7 (§3.14).

## 3. Commands

### 3.1 `submit`

```
temper submit gate (--ref R | --sha S) [common]
temper submit module-test --pl a,b [--test T] [--it-test T] (--ref R | --sha S) [common]
temper submit mvn (--ref R | --sha S) [common] -- <mvn args>
  common: [--prio N] [--keep] [--fresh] [--unit-timeout PT90S] [--owner O] [--json]
```

| Flag | Meaning |
|---|---|
| `--ref R` | a branch or tag on the origin, resolved once at submit; the job builds that SHA |
| `--sha S` | a full 40-hex commit SHA that some branch head, or a same-repo pull-request head (`refs/pull/<N>/head`), on the origin contains (a fork-PR head is `REFUSED(fork-pr)`, an origin GitHub could not confirm is `REFUSED(pr-origin-unverified)`: resubmit); short SHAs are refused (exit 64) |
| `--pl a,b` | module-test only, required: comma-separated module paths (or `:artifactId`) |
| `--test T` | module-test only: the `-Dtest` selection |
| `--it-test T` | module-test only: the `-Dit.test` selection; the second step then runs `verify` instead of `test` |
| `--prio N` | priority, −1000..1000, higher first; default −20 |
| `--keep` | pin the job: if it is running when a newer submit of its ref supersedes it, it is only marked superseded and runs on; retention never deletes its data |
| `--fresh` | skip dedup and always run a new job |
| `--unit-timeout D` | ISO-8601 duration, 1 minute to 6 hours (`PT90M`); overrides the unit's default bound (Gate 3 h, module-test and mvn 60 min). It is part of the dedup key |
| `--owner O` | the owner tag, overriding `TEMPER_OWNER`/`BB_OWNER` |
| `--json` | print the submit response object |
| `-- <args>` | mvn only, required: the mvn arguments |

Exactly one of `--ref` and `--sha` is required. `gate` takes no `--pl`, `--test`, `--it-test` or `--`; `module-test`
takes no `--`; `mvn` takes no `--pl`, `--test` or `--it-test`. The CLI parses the request with the same domain rules
the controller uses, so a bad SHA, owner, priority or timeout exits 64 without contacting the controller.

```
$ temper submit gate --ref release-1.0.0-rc5 --prio 5
submit 4711 QUEUED sha=0cf5631 recipe=gate deduplicated=false
$ temper submit module-test --pl core --test 'Foo*' --sha 0cf563192a…   # full 40 hex
submit 4712 QUEUED sha=0cf5631 recipe=module-test deduplicated=false
$ temper submit mvn --ref no-such-branch -- -pl core verify
submit - REFUSED code=source detail="ref no-such-branch matches no pushed branch or tag" job=4713
```

Exit 0 accepted, 10 deduplicated (the line shows the existing job's state), 2 refused. A refused submit is stored as a
REFUSED job whose id is printed as `job=`.

**Not available yet** (exit 64, line `submit - USAGE not in M0`): `submit mutate`, and the options `--bundle`,
`--patch`, `--base`, `--spec`.

### 3.2 `wait`

```
temper wait <job> [--timeout 6h] [--json]
```

Polls the job every 2 s, doubling to at most 10 s, until it is terminal, then prints its verdict line and exits with
the verdict's code. `--timeout` takes a time span such as `6h` or `10s`; it must be positive, and the default is 6 h. At the deadline it
prints the last state seen and exits 7. Up to 5 consecutive transient failures (controller unreachable, or a 5xx) are
ridden out; one more exits 69.

```
$ temper wait 4711
wait 4711 FAILED reason=tests testcases=21048 failed=1 observed-failures=1 missing=0 drift=0 evidence=stored
```

A refused job prints `wait <job> REFUSED reason=<code>` and exits 2.

### 3.3 `result`

```
temper result <job> [--json]
```

```
$ temper result 4711
result 4711 FAILED reason=tests testcases=21048 failed=1 skipped=43 units=1 observed-failures=1 missing=0 drift=0 evidence=stored
$ temper result 4714
result 4714 RUNNING
```

The verdict's exit code once terminal; 9 while the job is not terminal (the line shows its state); 2 with
`reason=<code>` for a refused job. `failed=` counts failed **plus** error testcases. `missing=` and `drift=` appear
only when the job has a manifest (Gate and module-test). A reason with free text prints as
`reason=manifest detail="effective-pom rc=1"`.

### 3.4 `watch`

```
temper watch [--terminal] --subscriber <name>
```

Prints one line per event for the named subscriber and runs until stopped. `--terminal` delivers only `finished` and
`cancelled` events; without it every kind is delivered (`queued`, `started`, `start-unknown`, `unit-finished`,
`finished`, `cancelled`, `superseded`). The subscriber name matches `[A-Za-z0-9_.-]{1,64}`; the controller creates an
unknown subscriber on its first poll, starting at the current end of the stream. A subscriber with no selectors sees
every job; `subscribe` narrows it (§3.4.1).

```
$ temper watch --terminal --subscriber cto
finished 4711 FAILED sha=0cf5631 gate testcases=21048 failed=1 seq=88
finished 4712 FAILED sha=0cf5631 module-test testcases=12 failed=0 missing=1 seq=91
gap from=92 to=140
```

Each line is printed and flushed before it is acknowledged, so a watch killed in between prints that line again on the
next run with the same `seq=`; drop repeats by `seq`. `gap from=… to=…` says retention deleted events the subscriber
had not read. Failures are retried forever with backoff 1 s doubling to 60 s, except: 401 (exit 77), stdout closed
(exit 5), an undecodable answer or a 4xx outside the route's contract on the poll, such as a controller without the
long-poll routes (exit 69), and a rejected subscriber name (400, exit 64). On an ack, a 404 (the subscriber vanished)
is retried, and any other 4xx is fatal (exit 69), as on the poll (`…/cli/command/WatchCommand.java`, `isFatal`).

#### 3.4.1 `subscribe` and `unsubscribe`

```
temper subscribe <job|sha|ref> --subscriber <name>
temper unsubscribe <job|sha|ref> --subscriber <name>
subscribe cto ADDED job:4711 state=RUNNING selectors=job:4711
subscribe cto ADDED ref:main matching=7 selectors=job:4711,ref:main note="a job submitted by --sha has no ref and never matches ref:main"
job 4790 state=QUEUED recipe=gate sha=5d6e7f8
job 4760 state=PASSED recipe=gate sha=0cf5631
job 4745 state=FAILED recipe=module-test sha=0cf5631
job 4720 state=PASSED recipe=gate sha=1a2b3c4
job 4711 state=FAILED recipe=gate sha=1a2b3c4
+2 more: temper jobs --ref main
unsubscribe cto REMOVED job:4711 selectors=ref:main
```

Adds a selector to, or removes one from, the named subscriber, which is created when absent (at the current end of the
stream, as a first `watch` would), so selectors can be set before the first `watch`. A selector is one of `job:<id>`,
`sha:<40 hex>` and `ref:<name>`, and matches only its own field of an event (`m2-design §2.2`,
`temper-domain/…/domain/subscribe/Selector.java`). The bare argument is classified, and a prefix wins:

| Argument | Selector |
|---|---|
| `job:N`, `sha:S`, `ref:R` | as written (so a ref named `4711` is `ref:4711`) |
| 1 to 19 digits, not starting with 0, at most 9223372036854775807 | `job:N`; `subscribe` needs the job to exist (HTTP 404, exit 64) |
| 40 hex digits | `sha:S`, stored in lower case |
| 7 to 39 hex digits | refused, exit 64: a short SHA matches no event (events carry the full SHA); use `sha:<full>` or `ref:<name>` |
| anything else | `ref:R`, compared with the ref as it was submitted (`main` is not `refs/heads/main`) |

A `ref:` selector matches only jobs submitted with `--ref`: a job submitted with `--sha` has no ref, and the line says so.

**Every line ends with the resulting scope.** No selectors means every job, and `selectors=all` says so. So the first
`subscribe` of a subscriber that saw every job **narrows** it to that one selector, and removing its last selector
**widens** it back to every job; both show on the line. `ADDED` and `REMOVED` name the operation: adding a selector that
is already there, or removing one that is not, changes nothing, and the scope shows it.

**What a change does to delivery.** A poll delivers an event when it is after the subscriber's cursor, its kind is
admitted (`--terminal`) and it matches the selectors that poll read.

A poll that matches nothing moves the cursor past everything it read (a skip-ack, ruling R-M2-12), but only if the
selectors did not change while it read; a change that lands mid-poll stops that move, and the next poll reads again with
the new selectors. The cursor never passes a matching event before `watch` acknowledges it, so every matching event is
still printed at least once. A `--terminal` poll's advance stops below a matching event of another kind (a `queued` or
`started`). The cursor is one per subscriber, though: acknowledging a later `finished` moves it past that event, so a
plain `watch` of the same subscriber is not guaranteed to print it. Use one subscriber per mode. So:

- a selector added later catches the events after the cursor, not the ones a poll already read past. For those, the
  reply shows the state of the jobs the selector matches now, read after the change: `state=` for a `job:` selector;
  for `sha:` and `ref:`, `matching=N` and one `job … state=…` line per job, the five newest, then
  `+K more: temper jobs --sha S` (or `--ref R`) naming the command that lists all of them (§3.4.2);
- a removal applies from the next poll; an event already printed is acknowledged as usual; a poll already waiting keeps
  the selectors it read;
- `gap from=… to=…` names events after the cursor that retention deleted before the cursor passed them. That is
  normally only events no poll read; under `--terminal`, where the advance stops below a matching event of another kind,
  it can also cover events a poll read past.

Exit 0; 64 for usage, an unknown job, a short SHA, or a name or selector the controller rejects (HTTP 400); 69
unreachable; 77 for the read token. `subscribe` reads the jobs' states after the change: if that read fails (exit 69, for
example), the selector has been added anyway, and running the command again changes nothing more.

#### 3.4.2 `jobs`

```
temper jobs (--sha S | --ref R) [--json]
job 4760 state=PASSED recipe=gate sha=0cf5631
job 4711 state=FAILED recipe=gate sha=0cf5631
```

Every job at that SHA (40 hex), or submitted with that ref (`--ref`; a job submitted by SHA has none), newest first, one
line each, with the state every view shows; `--json` prints the job views as one array. It reads the list page by page
(`GET /jobs?…&before=<id>`), so it never stops short. No job prints nothing. Exit 0; 64 for usage or a SHA or ref the
controller rejects (HTTP 400); 69 unreachable. The read token is enough.

### 3.5 `cancel`

```
temper cancel <job> [--json]
```

```
$ temper cancel 4714
cancel 4714 CANCELLING
```

Exit 0 when the cancel took: `CANCELLING` (active units are being killed), `CANCELLED` (nothing had started) or
`SUPERSEDED`. A job that was already terminal prints that state and exits with its code. If the controller answers a
live state (QUEUED, UNKNOWN, RUNNING), the CLI exits 69 rather than claim success.

### 3.6 `log`

```
temper log <job> [--unit U] [--tail N]
```

Prints the last `N` lines (default 200, at most 10 000, and at most 1 MiB) of the unit's Maven log, read live from the
worker host. Without `--unit`, the job's only unit is tailed (every M1 recipe plans one: `gate` for a Gate, `main`
otherwise); a job with several units falls back to `main`. A unit name the job does not have answers 404 (exit 64),
and the message names the job's units, e.g. `unit main (units: gate)` (`temper-controller/…/controller/api/TailLog.java`).
Exit 5 when the host's link is down.

### 3.7 `refusal`

```
temper refusal list [--all]
temper refusal add <code> --matcher <file.json> --reason "…" [--issue N] [--until "…"]
temper refusal expire <code>
```

Tree-specific refusals are data in the controller (`docs/spec.md` §2.5). `list` shows the active ones (every one with
`--all`), one line each, and prints nothing when there are none. `add` registers a new code: it matches
`R-[A-Z0-9-]{1,30}` and must not be a structural code (`R-ENV`, `R-M2`, `R-SHELL`, `R-DISK`); a code that exists is
exit 2. The matcher file holds one of the two kinds the domain can evaluate:

```
{"excludeTestClass":{"simpleClassName":"StreamPublishReshuffleTest","disabledMarker":"#1553","runner":"failsafe"}}
{"itTestRequired":{"moduleSegment":"forge-tests"}}
```

`expire` ends a refusal; codes are not reused.

```
$ temper refusal list
refusal R-1553 ACTIVE matcher=excludeTestClass simpleClassName=StreamPublishReshuffleTest disabledMarker=#1553 runner=failsafe issue=1553 until="until #1553 closes" reason="StreamPublishReshuffleTest is unstable until #1553 is fixed"
refusal R-FAILSAFE ACTIVE matcher=itTestRequired moduleSegment=forge-tests until=permanent reason="forge-tests with -Dtest but no -Dit.test runs every IT"
```

### 3.8 `alarm-check` (maintenance alarm)

```
temper alarm-check
```

One stateless check of the controller's health, meant to run from `ops/temper-alarm.timer` (every 10 minutes) on the
controller host. Installation: the deploy runbook ("Maintenance alarm"). It uses the
controller URL and token of §1.1 and its own `[alarm]` section of `~/.config/temper/config`; for `alarm-check` that file
is parsed as TOML, so the `url` must be quoted (`…/cli/config/AlarmConfig.java`).

| Code | Fires when |
|---|---|
| `controller-down` | 3 reads 15 s apart all get no answer (10 s per request, 20 s per attempt) or a 5xx; then the dump and worker codes are not evaluated |
| `dump-failed` | the latest `pg_dump` run failed (`GET /maintenance`) |
| `dump-stale` | no good dump ended within `stale-after` (`GET /maintenance`) |
| `worker-lost` | a host's link is not UP in `GET /hosts`; no grace period, so a reconnecting link counts |
| `host-alarms` | any host lists a current alarm in `GET /fleet/alarms` (queue-wait, portgroup-held, waiter-gone, host-retiring, clock-skew; `portgroup-long`, the lower-severity age backstop, is listed beside them but never opens the issue alone); the issue body has one line per alarm, `<host> <kind> <ticket\|-> age=<n>m limit=<n>m` (or `days=<d>`, `skew=<s>s`), then the route link. Clear when every host's list is empty; a controller without the route (404) or an answer that does not decode leaves it undetermined (`check-blind`), never clear |
| `disk-low` | a path in `paths` has fewer than `min-free-gib` GiB usable |
| `dump-file-stale` | the newest `temper-*.dump` in `dump-dir` is older than `stale-after`, or there is none (disk only) |
| `check-blind` | another condition could not be evaluated (a 401, a route the controller lacks, an undecodable answer, an unmeasurable path, an expired skip) |

`[alarm]` keys, all optional except `repo` for the GitHub sink (a key outside this list is refused):

| Key | Default |
|---|---|
| `sink` | `"github-issue"`; `"stdout"` prints the decisions only (a dry run) |
| `repo` | none; `"owner/name"` |
| `token-file` | `"~/.config/temper/alarm-token"` (the GitHub token, one line) |
| `github-api` | `"https://api.github.com"` |
| `repeat` | `"6h"`: a firing issue is commented at most once per interval |
| `host` | the first label of this machine's host name |
| `stale-after` | `"26h"` |
| `min-free-gib` | `50` |
| `paths` | `["~/.temper", "~/.local/share/temper/evidence"]` |
| `dump-dir` | `"~/.local/share/temper/evidence/pgdump"` |
| `skip`, `skip-until` | `skip` may name only `dump-failed` and `dump-stale`, and needs `skip-until = "YYYY-MM-DD"` (UTC), at most 7 days ahead. Every run prints `SKIPPED: <codes> until <date>`; past the date the codes are evaluated again, the line reads `SKIP EXPIRED: …`, and `check-blind` fires until the skip is removed |

Output: one line per delivered condition, `alarm <code> FIRING|CLEAR <what the sink did>[: <detail>]`. The GitHub sink
keeps one issue per code and host; an issue is the alarm's when a body line is exactly
`<!-- temper-alarm:<code>:<host> -->`, not by its title or label. If several open issues carry that line, the
lowest-numbered is used and the others are listed on the line (`duplicates for a person to close: #12, #13`); the
checker never writes to them.

Exit codes, in this precedence: **2** the sink failed to deliver (each firing condition is then printed on stderr as
`temper: alarm <code> FIRING UNDELIVERED: <detail>`); **64** a configuration error, or a condition that could not be
evaluated; **3** at least one condition firing and delivered; **0** every condition clear. Firing is 3, not 1, so a JVM
crash (exit 1) is never read as a delivered alarm; the systemd unit treats 0 and 3 as success.

### 3.9 `digest` (for agents)

```
temper digest [--since C] [--max N] [--json]
```

One read of what changed and what stands now, meant for an LLM agent's context: the jobs that finished and the units
that started after the cursor, the other events counted per kind, the queue, the hosts and the latest dumps. One fact
per line, about 20 lines:

```
$ temper digest
digest cursor=1232.1760608790123456 more=true asOf=2026-10-16T10:00:00Z
finished job=54 FAILED(tests) sha=0cf5631 ref=main owner=cto prio=5 testcases=120 failed=2 seq=1230
started job=56 unit=gate sha=0cf5631 ref=main owner=cto prio=5 seq=1232
other 3 events: queued=2 unit-finished=1
queue 1/7 job=57 prio=5 owner=cto sha=0cf5631 ref=main waiting=12m
queue 2/7 job=58 prio=3 owner=v2174 sha=0cf5631 waiting=3m port-group=waiting for=2m
queue 3/7 job=59 prio=3 owner=v2174 waiting=1m BLOCKED port-group 1/1 on bb
queue 4 more not listed
host bb link=UP state=UP cap=5 reactors=3 foreign=1 active=2
dump latest ok ended=6h30m-ago
dump last-good ended=6h30m-ago
more events waiting: run temper digest again (HTTP: since=1232.1760608790123456)
```

- **The cursor** is opaque, `<seq>.<token>`: the `seq` of the last event shown and that event's identity (its
  timestamp in microseconds; `-` when the cursor names no stored event, at the floor or before any event). Hand back
  the one you got; a bare `seq` is refused (exit 64, HTTP 400).
- **The cursor file.** Without `--since`, the cursor is the file `TEMPER_DIGEST_CURSOR` names, else
  `~/.config/temper/digest.cursor` (one per consumer: a shared file hands each event to whichever runs first): read (absent: start at the
  head of the event log, so the first run shows the current state and no history), then the digest is printed, and
  only then the new cursor is written (0600, replaced atomically). A failed request, a refused cursor or a print that
  did not reach stdout leaves the file as it was, so a digest is never recorded as read without being shown; a failed
  write after the print shows it again next time (exit 5). `--since C` reads from `C` and neither reads nor writes the
  file. A file that does not hold a cursor is exit 64, never read as 0.
- **What the cursor guarantees.** Every event with `seq` up to the new cursor's was shown (finished, started, or counted
  in `other`) or reported as `gap A..B deleted by retention`. At most `max` events are shown per call (`--max`, 1..1000,
  default 10); `more=true` says more follow, the text then ends with `more events waiting`, and the next call continues
  right after the last one shown. The CLI reads once and never loops: running it again is the caller's move.
- **What a foreign cursor triggers, exactly.** A cursor whose `seq` is above every committed `seq` is refused as ahead of
  the log (HTTP 400, exit 64). A cursor above the event floor is refused as `CURSOR_FOREIGN` (HTTP 409, exit 2, the file
  left as it is) when the event stored at its `seq` is missing or has another timestamp: that is how a cursor from
  another controller, or from this one before a database restore that has since committed new events, is detected.
  Nothing else is: a cursor at or below the floor reads as pruned (the gap path), and two logs whose events at that
  `seq` carry the same microsecond are not told apart. Delete the cursor file to start at the head.
- **What it does not say.** It never claims an alarm was delivered (`alarm-check` and its GitHub issues own that), and
  it does not report whether a job's ref moved.
- **Why a queued job is not running** (`BLOCKED`, JSON `blocked: [{host, reason, detail}]`). For each configured
  host, in configured order, the grant policy is run on a snapshot taken now (the host's worker facts, its active
  units, the scheduler's starvation state, the clock): one whole round of up to `cap` grants as the scheduler runs it,
  over the units the earlier hosts' rounds would not grant. A job one of whose queued units
  that round would grant somewhere is not blocked (it should start on the next tick); a job with no unit still queued
  is not either (owner ruling 2026-10-18: blocked is per job, not per unit). Otherwise the reasons of its first
  still-queued unit, per host: `cap` (`reactors/cap foreign=N`),
  `memory` (`active+unit>budget` MB), `mem-floor` (`free=M pending=M unit=M floor=MMB`: the host's MemAvailable less
  temper's units it does not reflect yet and this unit would fall below `[[hosts]] memFloorGiB`), `disk-floor` (`free=B floor=B`), `port-group` (`units/maxHolding` waiting for or
  holding the group), `starvation-hold` (it fits, but the head has been blocked past the starvation bound:
  `head=<unit id> since=…`), `no-link` (the host is not UP or has no worker facts, so the scheduler skips it), `busy`
  (the host's previous grant round has not ended, so the tick skips it), `unknown` (its active units could not be read
  within the tick bound; the rest of the digest is still answered, the cause is in the controller log) and `not-read`
  (beyond the 200 queued units one grant reads; a grant that overlaps another host's skips the rows that one holds and
  reads further, so with several hosts a `not-read` unit can still start). Fit reasons are judged after the round's own grants.
  **What it guarantees:** what the policy would decide on those inputs at `asOf`, not what the last tick decided, and
  with several hosts exactly what a tick grants when the hosts' grant transactions run one after another in configured
  order. A real tick runs them concurrently: another host may take a unit first (and because fit depends on the host,
  the set started can then differ too), and a host whose grant transaction overlaps another's skips the rows that one
  locked (all its candidates) and grants them no earlier than the next tick. The
  inputs are read after the digest's database snapshot and outside it, so a grant or a finish in between can make the
  answer disagree with the rest of the digest; the starvation state lives in the controller's memory, so after a
  controller restart no hold is reported until the head is seen blocked again for the bound. A digest with no host
  configured has no reasons at all.
- `queue` lists the waiting jobs in grant order (priority, then submit time): every QUEUED job, and every other job
  with a unit still QUEUED (a RUNNING Gate whose remaining shards wait; owner ruling 2026-10-18), at most 5 in the text, then one line counting
  the rest (`--json` lists up to 100, and `queued` counts them all); a state is printed only when it is not QUEUED
  (`UNKNOWN`: a unit runs whose start was not recorded; `RUNNING`: some units of the job already run). A queue line
  grows with hosts × reasons; it stays one line. `waiting` is the time since submit, `for` the time in
  the port-group state. `host` shows `link` (the live link) and `state` (the stored one); `reactors = foreign + active`.
  The text is at most `11 + max + hosts` lines: 21 plus one per host at the default `max`.
- `--json` prints the `DigestView` instead (no more-waiting line; read `more`). The CLI decodes the controller's JSON
  strictly (an unknown field fails), so a CLI older than its controller's `DigestView` (for example one without
  `blocked`) fails to read it: upgrade the CLI with the controller. A newer CLI reads an older controller's answer,
  whose jobs have no `blocked`, as not blocked.
- **The endpoint** behind it, for agents that call HTTP directly: `GET /api/v1/digest?since=<cursor>&max=N[&format=text]`
  with the bearer token, like every route; the read token is enough (§1.2). Without `since` it starts at the head; `max` is 1..1000 (default 10);
  `format` is `json` (default, the `DigestView`) or `text` (the lines above, `text/plain`). 400 for a parameter that
  does not parse or a cursor ahead of the event log; 409 `CURSOR_FOREIGN` as above. It writes nothing: the reads run in
  one `REPEATABLE READ, READ ONLY` transaction that Postgres itself keeps read-only, and the cursor stays with the caller
  (unlike `GET /subscribers/{name}/next`, which creates its subscriber).

### 3.10 `gate` (the merge question)

```
temper gate <sha> [--wait] [--timeout 6h] [--json]
gate 0cf5631 FAILED job=4711 judge=1a2b3c4 testcases=21048 failed=1 evidence=missing
gate 0cf5631 CONFLICT judge=1a2b3c4 jobs=4711:FAILED,4690:PASSED
gate 0cf5631 PASSED job=4760 judge=5d6e7f8 testcases=21050 failed=0 older=4711:FAILED@1a2b3c4,4690:PASSED@1a2b3c4
gate 0cf5631 RUNNING job=4720 judge=1a2b3c4 state=QUEUED previous=4711:FAILED
gate 0cf5631 PASSED job=4790 judge=5d6e7f8 testcases=21050 failed=0 judge-changed=4770:FAILED@1a2b3c4>5d6e7f8
gate 0cf5631 NONE unjudged=4702
```

`<sha>` is 7 to 40 hex digits; a prefix is resolved against the Gates' SHAs, and one that matches Gates at two SHAs is
refused (HTTP 400, exit 64, the message names both). The answer comes from the Gates at exactly that SHA that were
pushed when submitted, are not superseded and were not refused (`m2-design §1.1`,
`temper-domain/…/domain/gate/GateEvidence.java`):

- **The judge.** Every job and result records the temper build that judged it (`judge`, the build's git commit;
  `unjudged` for a dev build, `pre-judge` for rows older than M2). Only the Gates of the **newest judge** at the SHA (the
  judge of its newest Gate that has a verdict or still runs) decide the answer. The other judges' verdicts are listed as
  `older=<job>:<VERDICT>@<judge7>` and never answer, so a Gate under a new build replaces a disagreement of the old one.
  A Gate under the same build only adds a vote. A resubmit under a newer build runs a new Gate without `--fresh`:
  dedup never reuses another build's job (§6).
- **PASSED, FAILED, EMPTY** (0, 1, 3): the one verdict of that judge's Gates, about the newest of them.
- **CONFLICT** (11): that judge's Gates disagree (EMPTY against FAILED too). Every one is listed, and none is the
  answer; `running=<job>` names a newer one still running. Within one judge a CONFLICT is permanent.
- **RUNNING** (9): that judge's newest Gate has not finished (`state=` QUEUED, UNKNOWN, RUNNING or CANCELLING); the
  verdict it would otherwise be is `previous=`.
- **NONE** (8): no Gate decides anything.
- VOID, ERROR and CANCELLED Gates never answer and are listed as `unjudged=`. A Gate **judged across a deploy** (a unit
  granted under one build and the job finished under another) is listed as `judge-changed=<job>:<VERDICT>@<granted
  judge7>><finishing judge7>` and is left out of the answer and of any CONFLICT; its record carries the annotation
  `judge-changed=<granted judge7>`. The way out is a resubmit under the current build, which runs a new Gate (§6), or
  `--fresh`.

With a verdict the line carries the record's counts and annotations (`KNOWN(#n)`, `evidence=missing`, `superseded`).
`--wait` polls as `wait` does while the answer is RUNNING (every 2 s, doubling to 10 s; up to 5 consecutive transient
failures ridden out), then prints the answer; NONE and CONFLICT return at once. At `--timeout` (default 6 h; only with
`--wait`) it prints the last answer and exits 7. `--json` prints the `GateView` (`GET /api/v1/gate/{sha}`, a READ route
the read token may call).

The answer is a snapshot: a Gate submitted a moment later is not in it. A SHA that left origin after its Gate was
submitted is still answered. A SHA reachable only from a pull request (`refs/pull/*/head`) is answered once the
controller fetches those refs (ruling R-M2-7, a separate change).

### 3.11 `diff`

```
temper diff <jobA> <jobB> [--json]  |  temper diff <job> --base <sha> [--json]
added
  org.x.FooTest#newCase [failed]
removed
newly-failing
  org.x.BarTest#flips
newly-passing
diff 4690 4711 a=PASSED b=FAILED added=1 added-failing=1 removed=0 newly-failing=1 newly-passing=0 skip-changed=0 duplicates=0
```

Side A is `<jobA>`, side B `<jobB>`. With `--base <sha>`, side B is `<job>` and side A is the Gate that `gate <sha>`
answers about (the same selection, §3.10): its answering Gate for PASSED, FAILED or EMPTY, its `previous=` vote for
RUNNING; the counts line then adds `base=<job>`. Any two judged jobs can be compared, whatever their recipes; a VOID,
ERROR or CANCELLED side, A or B, is not judged and is refused (ruling R-M2-13). The rules (`m2-design §3.2`,
`temper-domain/…/domain/diff/TestDiff.java`):

- A test is its `(class, name)`. Its rows on one side fold into one outcome: failed if any row failed or errored, skipped
  if every row was skipped, passed otherwise. `duplicates=` counts the tests with more than one row.
- `added`: on B only, each with B's outcome (`[passed]`, `[failed]`, `[skipped]`), so a new failing test is never hidden
  among additions. `removed`: on A only. `newly-failing`: failed on B and not on A, so a skipped test that fails next
  time is newly failing. `newly-passing`: failed on A, passed on B.
- Passed → skipped, skipped → passed and failed → skipped are in no section: they are counted as `skip-changed=` and
  listed only in `--json` (`skipChanged`), and they never change the exit code. A failing test that is skipped next
  time is not a fix.
- Test names that are not bare are quoted, so each test stays on its own line.

Exit 0 when nothing is newly failing, no added test fails and side B ran tests; 1 otherwise, so a side B that ran no
tests (EMPTY) is never a clean diff. A diff that cannot be exact, or is about a side not judged, is refused with one line
instead, never computed:

```
diff 4690 4711 NONE not-judged=b:VOID detail="side not judged: VOID"      (exit 8; also ERROR, CANCELLED, and REFUSED)
diff 4690 4711 NONE pruned=a                                              (exit 8; retention deleted side A's rows, 90 days)
diff 4690 4711 RUNNING not-terminal=b:QUEUED                              (exit 9; side B has no result yet)
diff - 4711 NONE base=none sha=0cf5631                                    (exit 8; no Gate at that SHA decides anything)
diff - 4711 NONE base=conflict sha=0cf5631 jobs=4711:FAILED,4690:PASSED   (exit 8; the gate is a CONFLICT)
```

An unknown job is 64 (HTTP 404), and so is a `--base` prefix that matches Gates at two SHAs (HTTP 400). Both sides are read
in one snapshot (`GET /api/v1/diff?a=&b=`, or `?a=<job>&baseSha=<sha>` for the base form, a READ route); with `--base`,
the choice of the base Gate is in that same snapshot, so the base and both sides are one committed state. `--json` prints
the answer, `{"diff":{…}}` or `{"refused":{…}}`. The answer is a snapshot: a Gate that finishes a moment later can change
what `gate` answers next, not the diff already printed, which names its base.

### 3.12 `known` (the known-failure registry)

```
temper known add <testId> --issue N --reason "…" --until "<condition>" [--owner O]
temper known list [--all] [--json]
temper known expire <id>
known 12 ACTIVE test=org.x.EmberAmnesiacRestartTest issue=1679 until="#1679 closes" reason="…" by=cto
```

A failure the registry knows is marked on the job's record; it never changes the verdict (`docs/spec.md` §9,
`m2-design §4`). `testId` is a report classname (`org.x.FooTest`, nested `org.x.Outer$Inner`), which covers every case
of that class, or `class#name`, which covers exactly that case (a parameterised invocation is its own name: register the
class to cover them all). Matching is exact: `org.x.Foo` does not cover `org.x.FooBar` or `org.x.Foo$Inner`, and a simple
name covers nothing. When a case entry and its class entry are both active, the case entry's issue is the one shown.

- `add`: `--issue`, `--reason` and `--until` are required (an entry must not outlive its condition). The owner
  (`--owner`, `TEMPER_OWNER` or `BB_OWNER`) is recorded as `by=`: attribution, not authority; anyone with the write token
  can add or expire. One active entry per test: a second is exit 2. A malformed field is exit 64.
- `list` shows the active entries (every one with `--all`), one line each, nothing when there are none.
- `expire <id>` ends an entry (a POST, `POST /api/v1/known/{id}/expire`; nothing is deleted); an expired entry keeps its
  first expiry, and the test can be registered again. An unknown id is exit 64.
- A row the controller cannot read (only a hand edit or an older schema makes one) is listed, never hidden, as
  `known <id> UNREADABLE test=… issue=… until=… reason=… by=… unreadable="<why>"`, and `--json` carries it with
  `"unreadable": "<why>"` (ruling R-M2-15). It never marks a record, so it never masks a FAILED, and the controller logs
  a WARN naming it at each finish. `known expire <id>` removes it as any other entry.
- `known list --ledger` is not available yet (`known - USAGE not in M0`, exit 64): the ledger left M2.

**What a record shows.** When a job finishes, the entries active at that moment mark every failed or error testcase
they cover with `"known": "#<issue>"` in `failed[]`, and the record gets one `KNOWN(#n)` annotation per distinct issue,
sorted; VOID, ERROR and CANCELLED records are marked too. "At that moment" means the entries active at the known read,
one statement inside the completing transaction, after the job lock: an entry added or expired before that read
counts, one after it does not. The `finished`/`cancelled` event lines append the same annotations
(`finished 4711 FAILED sha=0cf5631 gate testcases=16234 failed=1 KNOWN(#1679) seq=88`), as do the digest's finished
and cancelled lines and its JSON `finished[].known` (ruling R-M2-14); the `gate` line shows them.
Records are immutable: an entry added after a job finished does not mark it, and an entry expired later stays on the
records it marked; `result` shows the record as written.

### 3.13 Verbs not available yet

`evidence`, `hosts` and `prio`, and the form `known list --ledger`, are recognised and answer `<verb> - USAGE not in M0`
with exit 64.

### 3.14 `events` (the fleet event stream)

```
temper events --until <kind,…> [--match k=v …] [--after <epoch>:<seq>] [--include-catch-up] [--timeout 30m]
temper events --subscriber <name> --follow [--format text]
temper events --create <name> [--start head|seq:<n>] [--bind-to caller|read]
```

The fleet stream carries kiln's GitHub-derived changes (`pr-head-moved`, `check-cell`, `pr-state`, `check-concluded`,
…), host links (`host-unreachable`, `host-answering`) and the controller's own (`source-stale`, `source-recovered`,
`source-reset`, `gap`, `self-check-failed`, `self-check-ok`, `epoch-changed`), one NDJSON line each, every reply ending in a heartbeat line. Code:
`…/cli/command/UntilCommand.java`, `FollowCommand.java`, `Dependencies.java`, `…/cli/transport/FleetApi.java`.

**Host alarms**. The controller evaluates them on its own data and emits them on
the same stream (`source` `controller`); each fires once per key, and the mark that says so is written in the same
transaction as the event, so a restart never repeats one:

| Kind | Subject | Fires when | Payload (beyond `host`, `ticket`, `owner`, `agent`, `lane`, `pr`, `sessionLabel`, `co`) |
|---|---|---|---|
| `queue-wait` | `<host>/<id>` | a waiting ticket's age (`at − created`, the host's clock) reaches `queueWaitAlarmMin` (default 60), and again at 180 min when that is above the limit | `ageMin`, `limitMin`, `group`, `holder` (a group ticket's running holder), `blockedSince` |
| `portgroup-held` | `<host>/<id>` | a running `group=1` ticket has made no progress for `portGroupStallAlarmMin` (default 30): `at −` its progress time, the listing's `progressAt` (the newest `TEST-*.xml` mtime under `target/surefire-reports` or `target/failsafe-reports` of its checkout, never older than `started`), else `started` when the listing has none (an old host script, or no report yet: the stall clock then runs from the start). Once per stall: a new report clears the mark and the next stall fires again | `stallMin`, `limitMin`, `progressAt` (absent without one), `ageMin` (since `started`), `groupWaiting` |
| `portgroup-long` | `<host>/<id>` | the age backstop: a running `group=1` ticket's age (`at − started`) reaches `portGroupHeldAgeAlarmMin` (default 360), whatever its progress; once. Lower severity: listed in `fleet/alarms` and on the stream, but `alarm-check`'s `host-alarms` does not open an issue for it alone | `ageMin`, `limitMin`, `groupWaiting`, `progressAt` (absent without one) |
| `waiter-gone` | `<host>/<id>` | a running or waiting ticket reads `waiter=gone` in two successful listings at least 30 s apart (host clock) | `dir`, `goneSinceAt` |
| `clock-skew` | `<host>` | the host's `at` lies more than 30 s outside the controller's read interval; once per episode, re-armed at ≤ 20 s | `host`, `skewS` (positive: host ahead) only |
| `host-retiring` | `<host>` | a `[[hosts]]` entry's `retireAt` is 3 days, then 1 day, away (controller clock, checked every 60 s); a controller started inside the last day says `1` only | `host`, `days`, `retireAt`, `remainingH` only |

A `queue-wait` ticket first seen above both of its thresholds fires the higher one only. The first five are evaluated in the
`tickets:<host>` collection round, only when it succeeds, so `--until` with `--match host=<h>` depends on `tickets:<h>`
(exit 4 when it is stale); `host-retiring` is the controller's own and depends on no source. The current alarms, those
whose condition still holds on each host's latest listing, are `GET /api/v1/fleet/alarms` (READ scope; one row per
expected host, in the `fleet/tickets` order). The limits are the controller config's `[fleet] queueWaitAlarmMin`
(default 60), `portGroupStallAlarmMin` (30) and `portGroupHeldAgeAlarmMin` (360), whole minutes from 1 to 10080; anything else refuses the controller's start, and there is no
off switch. `[fleet] laneStaleMin` (default 20, whole minutes from 1 to 10080) is the heartbeat silence after which a LEASED task shows `stale` in `GET /api/v1/tasks`; only a claim or a heartbeat restarts that clock (a linked submit or evidence extends the lease but not this clock). Leases held when the release is installed start from their last claim or heartbeat event. The former `portGroupHeldAlarmMin` is still accepted, warned about at start and ignored. A build log's size or
mtime is never progress, and maven's log lines carry no timestamp, so only report files count: one test class that runs
longer than the stall limit shows as stalled until its report is written. Nobody is paged unless someone follows the stream, watches kiln, or runs `alarm-check`.

**`--until` is the agent form.** Run it as a background job and be woken by its exit; parse nothing. It prints the
first matching event line as the controller sent it and exits 0. A line matches when its `kind` is one of `--until`,
every `--match` pair holds (`subject=…`, or a top-level payload field compared as text, e.g. `conclusion=failure`,
`attempt=2`; repeat `--match` or comma-separate the pairs), and it is not a catch-up row (`catchUp:true`, a replay after
an outage or rebuild) unless `--include-catch-up` is given. The CLI checks each line itself as well as the controller.
The next wait passes the printed line's `epoch` and `seq` as `--after <epoch>:<seq>`; without `--after` it starts at the
head the heartbeat names. There is no cursor and no ack: an agent that dies loses nothing. It long-polls
`GET /api/v1/fleet/events/after` (held up to 55 s, less when `--timeout` has less left), READ scope.

| Exit | `events --until` |
|---|---|
| 0 | the matching line printed |
| 3 | no heartbeat line received for 110 s (2 × 55 s), counted by this process's receipt time: the controller, its database or the network is gone, or replies arrive without their heartbeat. Failures in between (unreachable, 5xx, an undecodable reply) are retried, 1 s doubling to 30 s |
| 4 | a source the wait depends on is stale in the heartbeat, or missing from it: `kiln` and `kiln:github` for every kind kiln produces (a kind this CLI does not know counts as one); `hostlink:<h>` for a host kind, `h` from `--match subject=…` or `host=…`, else every `hostlink:*`; `tickets:<h>` for a ticket kind and for `queue-wait`, `portgroup-held`, `portgroup-long`, `waiter-gone`, `clock-skew`, `h` from `--match host=…`, else every `tickets:*`; nothing for the controller's own kinds (`host-retiring` among them) |
| 5 | `--timeout` reached (and stdout closed while printing, as for every command) |
| 6 | the heartbeat's `selfCheck.ok` is false (`ssh-banner` or `loopback` failed on bigboy); stderr names the probe |
| 7 | the epoch changed: `--after` names an epoch the controller no longer has (a restored or replaced database, HTTP 409 `EPOCH_CHANGED`, or a line of another epoch). Start again without `--after` |
| 13 | the controller is another build than this CLI (D-6) |
| 64 | usage (a kind not in lower kebab case, `--after` not `<uuid>:<seq>`, a `--match` without `=`); HTTP 400 or 404 |
| 69 | any other 4xx (an answer outside the routes' contract) |
| 77 | HTTP 401 or 403 |

The 110 s and `--timeout` bounds run on a monotonic clock (a wall-clock step changes neither), and each request is
bounded, body included, by what is left of them. On macOS that clock pauses while the Mac sleeps: a wait does not exit
at wake; it exits 3 within 110 s after wake if no heartbeat arrives.

**`--follow` is the long-running form**, for a reader that wakes per stdout line (the dispatcher, the Monitor tool).
It reads the named subscriber's server-side cursor (`GET /api/v1/fleet/events`, held up to 55 s), prints each event line
and flushes it, then acks its seq (`POST …/fleet/subscribers/<name>/ack`, READ scope: the token the subscriber was
created with, else HTTP 403). **Delivery is at-least-once to stdout, in seq order, not to an agent:** a process killed
between print and ack prints the line again next time (same seq); a reader that dies loses what was printed and acked.
A repeat is recognised by (epoch, seq): after a restore the new epoch's lines, `epoch-changed` first, are printed even
though their seqs start again lower.
Use `--until` unless something reads stdout line by line. `--format text` prints the contract's text lines instead of
NDJSON. It does not stop on a stale source or a failed self-check (the stream carries `source-stale` and
`self-check-failed` lines); it stops on no heartbeat for 110 s (3; each answered ack while it delivers a batch counts
as an answer), stdout closed (5), an unknown subscriber (HTTP 404
`NO_SUBSCRIBER`, 64), and 401/403 (77); everything else is retried.

**`--create <name>`** creates the subscriber (`POST /api/v1/fleet/subscribers`, WRITE scope: the full token) at the head,
or at `--start seq:<n>`, bound to the creating token, or with `--bind-to read` to the controller's read token (so a
read-only follower can poll and ack; HTTP 400, exit 64, when the controller has none), and prints
`events subscriber <name> created start=<start> bind-to=<caller|read>`. Exit 0; 2 when the name exists
(HTTP 409); 77 for the read token.

**The Mac dead-man** (`ops/mac/temper-deadman`, launchd every 60 s while the Mac is awake) watches the same heartbeat with
no session running: the LAN ssh banner, `GET /api/v1/fleet/heartbeat` within 10 s and `selfCheck.ok`, one macOS
notification per failing condition per hour plus `~/Library/Logs/temper-deadman.log`, one on recovery, and after a sleep
any gap over 15 min in the controller's heartbeat log (`bigboy silent <from>..<to>`). A missing URL or token file is
notified too (`bigboy config: …`, exit 2). The token reaches curl on stdin, never its command line. The install and uninstall commands
are in the script's header.

## 4. Reading `result --json`

The object is the immutable result record (`temper-protocol/…/protocol/api/ResultRecordDto.java`). Absent optional
values are `null`; times are ISO-8601 (`2026-10-13T10:00:00Z`), durations ISO-8601 (`PT36M`).

| Field | Meaning |
|---|---|
| `job` | the job id |
| `verdict` | `PASSED`, `FAILED`, `EMPTY`, `VOID`, `ERROR` or `CANCELLED` |
| `reason` | the verdict's token: `tests`, `rc`, `timeout`, `missing-classes` (FAILED); the empty units' names (EMPTY); `disk`, `port-group-lost` (VOID); `lost`, `report`, `marker`, `manifest`, `plan`, `seed-isolation`, `port-group`, `infra`, … (ERROR) |
| `reasonDetail` | free text after the token, e.g. `effective-pom rc=1` |
| `source` | `{ref, sha}`: the requested ref (if any) and the SHA that was built |
| `recipe` | the recipe as submitted (`{"gate":{}}`, `{"moduleTest":{…}}`, `{"mvn":{"args":[…]}}`) |
| `annotations` | `superseded`, `manifest-drift=<n>`, `evidence=missing`, `judge-changed=<granted judge7>` (a unit granted under another build than the one that finished the job); they never change the verdict |
| `counts.testcases` | the number of `<testcase>` elements: the authoritative count |
| `counts.passed`, `failed`, `error`, `skipped` | rows per outcome |
| `counts.testsAttr` | the sum of the XML `tests=` attributes, kept only for comparison. It does not equal `testcases`: on a pragmatica Gate it was observed lower (18639 against 21313 `<testcase>` elements) |
| `counts.classes` | distinct reported classes |
| `counts.manifest`, `missing`, `drift` | the manifest's required size and the uncapped missing and drift counts; `null` without a manifest |
| `failed` | `[{class, name, known}]`: every failed or error testcase; `known` is `#<issue>` when a known-failure entry covered it at finish (§3.12), absent otherwise |
| `observedFailures` | how many failed or error testcases were seen; filled for VOID and ERROR too |
| `units` | `[{id, rc, host, image, start, end, tcLeftovers, portGroup, attempt, sharedCpu, lostAttempts}]`; `id` is the unit name, `portGroup` `{ticket, heldFrom}` when the unit held the bb-* port group; `attempt` is the attempt whose result this is, `sharedCpu` whether it ran on a shared-CPU host (its wall times and `memory.peak` are then not comparable with a dedicated host's), `lostAttempts` `[{attempt, host, grantedAt, startedAt, endedAt}]` its earlier attempts lost with their host, oldest first; a record written before M4 has no `attempt` or `sharedCpu` and an empty `lostAttempts` |
| `manifest` | `{computed, missing, drift, notReached, unresolvedAnnotations}`; `missing` and `drift` are sorted and capped at 500 entries (see `counts` for the full sizes); `computed: false` means coverage was not judged |
| `evidence` | `{stored, path, bytes}`. `stored: false` occurs in two cases (`temper-store/…/pg/ResultRecords.java`): a unit with a marker has no small tarball on the controller (the record also carries the `evidence=missing` annotation), or no unit wrote a marker at all, so nothing could leave evidence (no annotation). The CLI's one-line output prints `evidence=missing` whenever `stored` is false, in both cases |
| `timings` | `{queued, run}`: first start − submit, last end − first start |
| `judge` | the temper build (git commit) of the controller that wrote the record; `unjudged` from a dev build; absent in a record written before M2 |

The job view (`GET /jobs/{id}`, also what `wait --json` prints at a timeout and what `result --json` prints while the job
runs) is `JobView`: `job`, `state`, `verdict`, `reason`, `submittedBy`, `submittedAt`, `priority`, `keep`, `fresh`, `superseded`, `source`,
`recipe`, `units` (each with `name`, `state`, `reason`, `host`, `rc`, `startedAt`, `endedAt`, `imageId`, `containerId`, `killedBy`, `leaseExpired`,
for a bb-hold unit `portGroup` = `waiting|held`, `portGroupSince`, `ticket`, and since M4 `attempt` (its grant count, 0
before the first grant), `sharedCpu` (absent before a grant), `sideEffects` (`none|external`; an `external` unit is never
requeued after a host loss) and `lostAttempts` as in the result record), `reruns`, `userProperties` and `dedicatedCpu`
(the job's `--dedicated-cpu`; false when absent).

## 5. Owners and priorities

- **Owner tag:** `submittedBy` must match `[A-Za-z0-9_]{1,64}` (`temper-domain/…/domain/model/OwnerTag.java`;
  the `jobs.submitted_by` CHECK in `V1__m0_schema.sql` enforces the same pattern). A dash or a dot is refused (exit 64 from the CLI). The owner does not affect dedup or scheduling.
- **Priority:** −1000..1000, default −20; higher is granted first, then earlier submits (`…/domain/model/Priority.java`).
  For a Gate (or another port-group unit) the priority is also passed to bb-hold. Priority is not part of the dedup
  key, so resubmitting with another `--prio` returns the existing job unless you add `--fresh`.

## 6. Dedup and supersede, briefly

- A submit with the same source SHA, recipe, unit-timeout override and runner version as an existing job in state
  QUEUED, UNKNOWN, RUNNING, PASSED, FAILED or EMPTY returns that job (exit 10). VOID, ERROR, CANCELLED, REFUSED,
  CANCELLING and SUPERSEDED jobs are never reused; `--fresh` always runs a new one.
- The runner version is the controller's whole build version, judge included (`0.1.0-SNAPSHOT+3f9c0a1b2c4d`), so a
  job of another temper build is never reused: after a deploy, the first resubmit of each (SHA, recipe) runs again.
  A `-dirty` or `+unjudged` build never reuses a job of a clean one.
- A submit **by ref** supersedes earlier QUEUED, UNKNOWN or RUNNING jobs of the same ref and recipe at another SHA (the
  recipe with its parameters, so `module-test` jobs with different `--pl` do not supersede each other; `--prio`,
  `--keep` and `--unit-timeout` do not count):
  queued ones are cancelled, running ones are cancelled too unless they have `--keep`, in which case they are only marked
  superseded and run on. Submits by SHA never supersede and are never superseded.

## 7. Common errors

| Symptom | Cause and fix |
|---|---|
| `Token file missing: …/token` (exit 64) | Put the controller's bearer token in `~/.config/temper/token`, mode 0600 |
| exit 77 | The token does not match the controller's `tokenFile` (or its `readTokenFile`); with the read token, the verb writes (`Controller refused this operation for a read-only token`) |
| `Controller unreachable at …` (exit 69) | Check `TEMPER_URL` / `~/.config/temper/config` and that the controller runs |
| `submit needs an owner` (exit 64) | Pass `--owner`, or set `TEMPER_OWNER` or `BB_OWNER` |
| `Invalid submit request` naming `sha` (exit 64) | Give the full 40-hex SHA |
| `REFUSED code=source` | The ref matches no branch or tag, or the SHA is in no branch head on the origin: push it first |
| `REFUSED code=unsupported` | A recipe or source kind not implemented yet |
| `REFUSED code=R-M2` / `R-SHELL` / `R-ENV` | mvn args set `maven.repo.local` or a settings file; a shell-capable goal or `exec.executable`; an `env` field |
| `REFUSED code=R-1553` / `R-FAILSAFE` | The selection could run `StreamPublishReshuffleTest` while it is not `@Disabled("#1553")`; or `-Dtest` without `-Dit.test` on a step that may build `forge-tests` |
| `temper log` exits 64 with `Not found: … unit X (units: …)` | The `--unit` given is not one of the job's units; use a name from the list |
| `log` exits 5 | The job's host link is down; retry once it reconnects |
| `temper digest` exits 64 with `Cursor … is ahead of the event log`, or exits 2 with `Digest cursor in … is not from this controller's event log` | The cursor file came from another controller or from before a database restore: delete `~/.config/temper/digest.cursor` to start at the head |
| `ERROR(manifest: …)` | The build's test configuration is outside what the manifest rules model (`docs/architecture.md` §4.2); the reason names it |
| `evidence=missing` | On the CLI line: `evidence.stored` is false. Either a small tarball could not be pulled (the record carries the annotation too) or no unit ran far enough to write a marker (no annotation). The verdict stands either way |
| a job shows `UNKNOWN` | Its unit runs but the start could not be recorded; it completes from its exit facts |
