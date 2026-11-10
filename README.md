# temper

temper is a lightweight, self-hosted CI for the pragmatica monorepo. A controller (an HTTP API over a PostgreSQL
store) queues jobs, a worker on each configured build host (one `[[hosts]]` entry per host, reached over ssh) runs their units in a runner
container, and the controller judges
every result from the surefire and failsafe XML itself: `<testcase>` counts, failed methods, and coverage against a
manifest of the test classes the build was expected to run. Each finished job keeps an immutable result record and an
evidence tarball.

## Status

- **Milestone M1** (`docs/m1-design.md`): the unsharded Gate through bb-hold, the manifest from the effective POM, the
  evidence store with `result.json` and a nightly `pg_dump`, retention GC, the disk guard, refusals as data, and the
  maintenance alarm (`temper alarm-check` on a systemd timer, reporting to GitHub issues).
  Mutate, sharded Gates, `Bundle`/`Patch` sources, `diff`, `gate`, the KNOWN registry and multi-host operation
  (the cloudbb hosts, M4; the code already takes a list of hosts, M1 runs one) belong to later milestones (`docs/spec.md` §12).
- **CI** (`.github/workflows/ci.yml`) builds against pragmatica `1.0.0-rc4` (libraries and the `jbct-maven-plugin`),
  resolved from Maven Central, where all of it has been published since 2026-10-18.

## Modules

| Module | What it holds |
|---|---|
| `temper-domain` | Pure domain: values, recipes, planner, refusals, verdict rules, manifest rules, retention plan, exit codes |
| `temper-protocol` | Wire types: controller⇄worker frames and messages, HTTP API DTOs, the shared JSON mapper, process runner, report parser |
| `temper-store` | PostgreSQL schema (versioned migrations) and the store port with its `postgres-async` implementation |
| `temper-controller` | HTTP API, submit and cancel, scheduler, host links over ssh, reconcile, evidence pull, retention, `pg_dump` |
| `temper-worker` | Worker process on a build host: workspaces, runner image, unit containers, bb-hold, report and manifest collection |
| `temper-cli` | The `temper` command, a thin HTTP client of the controller, and `temper alarm-check`, the maintenance alarm |
| `temper-dist` | Copies the controller, worker and CLI jars of one build to `target/dist` and checks their versions agree |
| `temper-acceptance` | Acceptance and trust tooling (A11, A12, T1, T2, sizing); built only with `-Pacceptance` |

## kiln: merge-tree conflict early warning (#100)

kiln (`kiln-core`, `kiln-service`) predicts textual merge conflicts on pragmatica before GitHub shows DIRTY. After every
mirror fetch it runs `git merge-tree --write-tree` on its own bare mirror (no checkout, no build, no GitHub call):
each open PR head against the release tip, each pair of open PRs whose changes can meet (a common path; one adds a
file where the other has a directory; one moves a directory the other adds into), in BOTH landing orders (one PR
squash-merged onto the tip first, then the other merged onto that, so the check shows what the second merge hits; the
order matters because each PR's merge base is its own branch point), and every open PR again when the tip moves.
Objects that merge-tree writes go to a scratch object directory, never into the mirror.

- Events: one tab-separated line per new or cleared prediction, appended to `~/temper/conflicts/events.log`:
  `time  kind(tip|pair)  state(new|cleared)  prs(2713 or 2711+2770)  paths  tip-sha  orders`, where `orders` names the
  landing orders that conflict (`2770>2711` is "#2770 lands first, then #2711 conflicts"; both are comma-separated; empty
  for a tip line). Hot files (`AetherValue.java`, `AetherKey.java`, `SystemTags.java`, `ReplicaFilter.java`,
  `wire-assignment-baseline.txt`, `transport-outcome-allowlist.txt`, `OperatorWarningCode.java`) carry a leading `*`.
  A torn last line (kiln killed mid-append) is tolerated: the next line starts on its own line.
- View: the Conflicts tab and `GET /api/v1/conflicts` list the current predictions and the cost per push (fetch time,
  bytes fetched, check time). A step runs at most 300 uncached pair checks (measured: 40 open PRs, all 780 pairs in both
  orders, 73 s on a loaded bigboy); the rest show as "pairs skipped: N" (`pairsSkipped`) and run in the next steps.
- Config: the watch is OFF unless the config has a `[conflicts]` header; the header alone turns it on with defaults.
  Keys: `branch` (default: the first `[github] bases` entry), `eventsLog` and `scratch`.
- Restart: predictions live in memory and are recomputed from git; the announced set is replayed from the events.log
  tail (its last 5000 lines, read from the end) first, so a restart appends lines only for what changed while kiln was
  down.

**Limit:** merge-tree finds textual conflicts only. A clean textual merge can still break compilation, for example a
new caller of a method signature that another PR changes. A rename git does not detect (below `-M`'s similarity) reads
as a delete and an add, so a directory move made of such renames is not paired with an add into the old directory.

## Quick start (build)

Requirements: Java 25, Maven, and a Docker daemon for the Testcontainers tests (without one, those tests are skipped,
not passed).

```
cp -a ~/.m2/repository/. .m2-local/      # optional seed (saves downloads): a real copy, never cp -al
find .m2-local -type f -links +1 | wc -l  # must print 0
mvn install                               # from the root: the whole default reactor, with tests
```

The tracked `.mvn/maven.config` holds `-Dmaven.repo.local=.m2-local`, so every tree builds against its own repository
and never writes `~/.m2`. Always build from the root. `docs/developer-guide.md` explains why, and covers the layering
gate, the test tiers and the acceptance profile.

## Documentation

| Document | For |
|---|---|
| [docs/user-guide.md](docs/user-guide.md) | Submitting jobs and reading results with the CLI |
| [docs/architecture.md](docs/architecture.md) | Components, job lifecycle, scheduling, judging, evidence, guarantees |
| [docs/developer-guide.md](docs/developer-guide.md) | Building, testing, code style and conventions |
| [docs/spec.md](docs/spec.md) | The contract (spec v0.2) |
| [docs/m1-design.md](docs/m1-design.md) | The M1 implementation design and its rulings |
| the deploy runbook | Deploying the controller, worker and CLI |
| [temper-acceptance/README.md](temper-acceptance/README.md) | Acceptance tiers, T1/T2 harnesses, the host tier |

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Copyright (c) 2026 Pragmatica Labs - Sergiy
Yevtushenko.
