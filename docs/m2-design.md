# temper — M2 implementation design (draft, revision 4)

**Status.** DRAFT, input for `jbct-coder` agents. Revisions 2 and 3 fold in the maintainer's scope rulings of 2026-10-18 (s39),
recorded in §13 as R-M2-1 to R-M2-6. Revision 4 folds in the s40 rulings on revision 2 (R-M2-7 to R-M2-10) and the
s41 judge ruling (R-M2-11), which settle every open question. Each ruling replaces the questions it names. The contract is `docs/spec.md` v0.2;
M1's structure and rulings are `docs/m1-design.md` (cited as m1 §n, rulings R-n). This document says what M2 adds and
changes against the code on `main` (771c1c7) as read on 2026-10-18. It does not restate the spec.

**Markers** (as in m0/m1): **[V]** verified by reading code or docs in this tree; **[U]** unverified, the coder or a
probe must confirm it first; **[P]** a temper proposal; **[G]** a guess forced by an underdetermined spec. **Q-M2-n**
are questions for the maintainer (§11); the design proceeds on each marked guess until ruled. Question numbers are stable:
a ruled question keeps its number in §13 and leaves §11.

**M2 scope after R-M2-1..4:** `gate` (with conflicts), `subscribe`/`unsubscribe`, `diff`, the known-failure registry,
and an explicit `temper rerun <job>`, in that order. **Out of M2:** the ledger (R-M2-3), the `Patch` and `Bundle`
sources (R-M2-2), automatic reruns (R-M2-4); see §12 "Deferred to M3". The recorded spec deviations are listed in
§10. **Exit gate** (spec §12): CLI tests; one real `diff`
cross-checked by hand. M2 exits without the ledger, by ruling.

**Binding rulings carried in:**
- **R-12** (owner): no backward compatibility until Aether GA. Wire, schema and stored rows change incompatibly where
  that is simpler; no shims. Every schema change below says what it does to existing rows.
- **R-10:** `store.api` names no protocol type. New views (`GateView`, `DiffView`, `KnownDto`) are protocol DTOs mapped
  in the controller (or `store.pg.WireRecords`), never returned by a store port.
- **R-4:** every new package is classified in `jbct.toml` in the commit that creates it, so the layering check
  examines it.

---

## 0. Findings from the code and docs that drive this design

Each row is a place where code, spec and design disagree or where the code constrains M2. None is resolved silently:
the ones that need a ruling are Q-M2-n (§11).

| # | Finding (file) | Consequence for M2 |
|---|---|---|
| F1 | `cli/command/CommandLine.LATER_VERBS` = `diff, gate, subscribe, unsubscribe, known, evidence, hosts, prio` [V] | M2 removes `diff, gate, subscribe, unsubscribe, known` and adds `rerun`; `known` joins `GROUPS` next to `refusal`. `evidence, hosts, prio` stay later |
| F2 | `api/ApiRoutes.routes()` has no `gate`, `diff`, `known`, `rerun` or `PUT subscribers/{name}/selectors` route [V]. Events are the long-poll `GET subscribers/{name}/next` + `POST …/ack` (m0 §6.5 fallback), not spec §7's WS route [V]. No route in the tree uses `HttpMethod.PUT` or `DELETE` [V] | New route classes per feature (as `DigestRoutes`). Whether `Router`/rc4 dispatch PUT is **[U]**; if not, the selectors route becomes `POST subscribers/{name}/selectors` (§2.3) |
| F3 | `events/EventFeed.selected`: a selector is an **untyped string** compared for equality with the event's job id, `payload.sha` and `payload.ref` [V] | A ref named `4711` matches job 4711; a short SHA matches nothing (payloads carry 40 hex); a ref selector misses every job submitted by SHA (`jobs.ref` is null, `Submission.ref`). M2 types selectors (§2.2) |
| F4 | Empty selectors = every job (spec §8.3 and `EventFeed.selected`) [V] | The first `subscribe` on the maintainer's all-jobs subscriber **narrows** it to one job; removing the last selector **widens** it back to everything. Design §8 reads `subscribe` as additive ("adds a selector… so the maintainer can follow work"). Q-M2-9 |
| F5 | `EventFeed.next` reads the subscriber's selectors once per poll; the cursor moves only on the ack of a delivered event [V] | The exact delivery rule under a selector change follows from this (§2.1); no extra mechanism is needed |
| F6 | Supersede victims are matched on `(ref, recipe_kind)` (`store/pg/PgJobStore.VICTIMS`) [V]; spec §2.4 says `(R, recipe)` | For `gate` the two agree (one canonical recipe). For `module-test` they do not: `--pl core` and `--pl aether/ember` on one ref cancel each other. **Ruled R-M2-8:** the key becomes `(ref, canonical recipe incl. params)`, fixed as a separate M1-defect PR (branch `fix-supersede-key`), not M2 work. `gate` is unaffected either way |
| F7 | `SUPERSEDED` is never written (architecture §2.3); the record of a supersede is `jobs.superseded`/`superseded_by`, set on every victim (`Cancellation`) [V] | "Non-superseded" (spec §8.2, design R5) means `NOT jobs.superseded`. A victim ends CANCELLED unless it was `keep` and running, so the only *judged* Gates with `superseded = true` are `keep` Gates that ran on after their ref moved (Q-M2-3) |
| F8′ | *(Replaces revision 1–3's F8, which was false.)* The dedup key's runner component is `${project.version}`, filtered into `controller-version.properties` (`config/ControllerVersion`, `temper-controller/pom.xml` resources) [V]. It is `0.1.0-SNAPSHOT` in every module pom [V], so it is the same across every deploy; live containers carry `temper.version=0.1.0-SNAPSHOT`. No job row stores a build identity [V V1–V4]. *(Since owner decision D-6 A1, 2026-10-24, the runner component is the whole build version, judge included: dedup no longer spans deploys. Spec §2.3.)* | **Dedup returns the old job across deploys.** A second Gate at a SHA exists only through `--fresh`, or after a VOID/ERROR/CANCELLED one (which never deduplicates). Several Gates at one SHA are therefore deliberate, and CONFLICT is not a frequent case. Nothing records which build judged a job; R-M2-10's `judge` adds that (§1.7) |
| F9 | `verdict/Verdicts` evaluates primary units only (`primaryUnits` drops `rerun`); `units.rerun` and `testcases.rerun` exist since V1 [V] | These columns were laid down for reruns *inside* the failed job. Under R-M2-4 a rerun is its own job (§5), whose units are primary for it, so M2 leaves both columns `false`. They stay unused, not dropped [P] |
| F10 | `results` is write-once (trigger `temper_write_once`); `Finishing.finish` computes every annotation and writes the record once, in the transaction that ends the last unit [V] | KNOWN marks must exist before that single write (§4.3). An explicit rerun is asked for after the parent's record exists, so its outcome **cannot** be an annotation on the parent's record, as spec §5.3/§9 put it. It goes into the rerun job's own record, and the parent is linked on the read side (§5.3, Q-M2-25) |
| F11 | `ResultRecordDto.FailedDto.known` exists and is always none [V] | KNOWN fills it; no wire change for the per-test mark |
| F14 | `Message.PrepareJob(job, sha, originUrl)`: the worker fetches the SHA from origin into one `ws` per job [V] | A rerun job gets its own workspace through the normal path, which is spec §9's "fresh checkout (unsharded)"; no worker change |
| F15 | maintainer ruling M1-3: "Pushed reachability is extended to `refs/pull/*/head`". `controller/git/GitCli` fetches only `refs/heads/*` and `refs/tags/*` and checks `for-each-ref --contains <sha> refs/heads` [V]; `M1-WP1-verification` lists the refspec as a WP5 obligation [V] | The ruling is not implemented. **Ruled R-M2-7:** implement the `refs/pull/*/head` fetch and reachability as a separate PR (branch `fix-pull-refs`), built now. It is a **prerequisite of WP-A**, not WP-A work (§1.6 pins its acceptance) |
| F16 | Retention deletes `testcases` and `manifests` at 90 d and never `results` (`RetentionPlan`, architecture §5.2) [V] | `gate` answers forever (from `results`); `diff` of a pruned job must say so instead of reporting every test as added or removed (§3.2); a rerun of a job past 90 d has no module for its classes (§5.1) |
| F17 | Spec §8.2's `gate` line lists `PASSED\|FAILED\|NONE\|RUNNING` | The newest Gate can be EMPTY, VOID, ERROR or CANCELLED, and R-M2-5 adds CONFLICT: none of these is in the spec's line (§1.1, Q-M2-2, Q-M2-22) |
| F18 | Spec §8.2 conflicts: "terminal Gates at that SHA disagree in verdict (VOID, ERROR and CANCELLED excluded)"; design R5: the answer is the latest *non-superseded* Gate; R-M2-5: disagreeing non-superseded Gates print CONFLICT | The conflict set is the non-superseded judged Gates **of the newest judge** (R-M2-10). Superseded `keep` Gates are left out (Q-M2-3's guess, accepted in R-M2-9) |
| F19 | Spec §7 lists `GET/POST/DELETE /known`; spec §9 keeps entries with `expiredAt`; the refusal surface uses `POST refusals/{code}/expire` (m1 §6) [V] | A DELETE that only sets `expired_at` would misname what it does. §4 follows the refusal precedent (Q-M2-11) |
| F22 | `domain/exit/ExitCode.NONE(8)` exists and is produced by nothing (user guide §2) [V]; the spec §8.1 table has no code for a conflict | `gate` and `diff --base` produce 8; CONFLICT needs a code (Q-M2-22) |

F12, F13, F20 and F21 of revision 1 (Patch/Bundle sources, upload caps, rerun workspace, rerun delay) concern scope
that R-M2-2 and R-M2-4 removed. F12 and F13 are kept in §12 for M3; F20 and F21 no longer apply, because a rerun is a
separate job.

---

## 1. WP-A: `temper gate <sha>`, the merge question (R5)

### 1.1 Selection (domain, pure; `domain/gate/GateEvidence.java` **+**)

The candidate set for a SHA `S` is every job with **all** of:
- `recipe_kind = 'gate'` (a rerun job, kind `rerun`, is never a candidate, §5.3);
- `source_sha = S` and the source is `Pushed`. That is `NOT jobs.unpushed`; nothing sets `unpushed` in M2 (F12 is
  deferred), so the filter is a guard for M3, and it is probed with a row inserted directly;
- `NOT jobs.superseded` (F7; Q-M2-3);
- not `REFUSED` (it never ran).

```java
// domain/gate
enum GateAnswer { PASSED, FAILED, EMPTY, CONFLICT, NONE, RUNNING }
record Judge(String value) {}                             // 40-hex build commit, or Judge.PRE for rows older than V5 (§1.7)
record GateCandidate(JobId job, Judge judge, JobState state, Option<Verdict> verdict, Option<Counts> counts,
                     List<String> annotations) {}
record GateEvidence(Option<Judge> judge,                  // the newest judge at the SHA; none when no candidate decides one
                    GateAnswer answer,
                    Option<GateCandidate> answering,      // the job a PASSED/FAILED/EMPTY/RUNNING answer is about
                    Option<GateCandidate> previous,       // RUNNING only: the same-judge judged candidate it would otherwise be
                    List<GateCandidate> conflict,         // CONFLICT only: every judged candidate of the newest judge, newest first
                    Option<JobId> running,                // CONFLICT only: a newer non-terminal candidate of that judge
                    List<GateCandidate> older,            // judged candidates of older judges, newest first: history, never the answer
                    List<GateCandidate> judgeChanged,     // judged across a deploy (R-M2-11): history, never the answer
                    List<JobId> unjudged) {               // VOID/ERROR/CANCELLED candidates, newest first
  static GateEvidence select(List<GateCandidate> candidates);   // pure; candidates in any order
}
```

**Rules, first match wins.** "Latest" is the highest job id (a `bigserial` assigned at submit).
1. **Judged** = verdict in {PASSED, FAILED, EMPTY}, and the candidate is not `judge-changed` (R-M2-11, §1.7).
   - VOID, ERROR and CANCELLED "describe the environment, not the code" (spec §2.3's dedup reasoning) and never
     answer; they are listed as `unjudged` (R-M2-9, Q-M2-2).
   - A judge-changed candidate is listed as `judge-changed=<id>:<VERDICT>`. It never answers, never counts for
     CONFLICT, and never decides the newest judge.
2. **The newest judge (R-M2-10):** the judge of the highest-id candidate that is judged or non-terminal [G]. Unjudged
   candidates never decide the judge: a VOID under a new build does not erase the old build's answer. A candidate's
   judge is the judge on its result record when it is terminal, and the one on its job row otherwise (§1.7). Every
   rule below looks only at **the newest judge's candidates**. Judged candidates of older judges are listed as
   `older=<id>:<VERDICT>@<judge7>` and are never the answer.
3. **CONFLICT (R-M2-5):** the newest judge's judged candidates carry more than one distinct verdict → `CONFLICT`,
   listing them all. The latest verdict is **never** printed as the answer. EMPTY against FAILED is a disagreement,
   since the verdicts are distinct. A newer non-terminal candidate of that judge is shown as `running=<id>`; it does
   not change the answer, because its result can only add a vote.
4. **RUNNING:** the newest candidate of that judge is non-terminal (QUEUED, UNKNOWN, RUNNING, CANCELLING) and newer
   than every judged one → `RUNNING`. The judged one, if any, is still reported as `previous=<id>:<VERDICT>`, so a
   running re-check never hides the evidence that exists (R-M2-9, Q-M2-4).
5. **Verdict:** otherwise, the one distinct judged verdict of that judge, about its newest judged candidate.
6. **NONE:** no judged candidate and none running (unjudged and `older` may still be listed).

**How a CONFLICT ends (R-M2-10).** `results` and `jobs` are never deleted, and a new Gate under the *same* judge only
adds a vote. A Gate under a *newer* judge replaces the answer: the old judge's disagreeing Gates move to `older`.
Dedup is unchanged (it keys on `project.version`, F8′), so getting a newer judge's answer for a SHA takes
`--fresh`. *(Amended by owner decision D-6 A1, 2026-10-24: dedup keys on the whole build version, judge included, so
a resubmit under a newer build is a new job without `--fresh`. Spec §2.3.)* A rerun never changes the gate answer (R-M2-10 option (i)), and KNOWN does not either, since KNOWN never
changes a verdict. Within one judge, a CONFLICT is permanent: the way out is a new build or a new SHA.

### 1.2 Guarantee and mechanism

| Situation | What `gate S` returns | Mechanism |
|---|---|---|
| normal | per §1.1, over the pushed, non-superseded Gate candidates at exactly `S`, with counts from the immutable records | one read in one `REPEATABLE READ, READ ONLY` transaction (`Sql.snapshot`, as `PgDigestStore`): candidates joined with `results` |
| a supersede commits concurrently | either the pre-supersede world (old job active, new job absent) or the post-supersede one (old job `superseded`, new job present), never a mix | the submit transaction inserts the new job and sets the victim's `superseded` + state in one commit (`PgJobStore.submitIn`) [V]; the read is one snapshot |
| a Gate finishes concurrently | the job before (RUNNING) or after (verdict + record); a finish that disagrees turns the answer into CONFLICT in the same way | `Finishing.writeResult` sets `jobs.state` and inserts `results` in one transaction [V] |
| controller restart | the same answer; nothing is held in memory except the UNKNOWN overlay, which only changes `state=` on a RUNNING answer | stateless query; `Views.jobState` derives the shown state as every other view does |
| evidence missing | the verdict stands; the line adds `evidence=missing` | the record's `evidence.stored` (m1 §3.2) |
| after retention | unchanged for good: `results` and `jobs` are never deleted | `RetentionPlan` [V] |
| the SHA left origin later (branch deleted, force-push) | still answered: "pushed" means it passed the Pushed check **at submit** | spec §2.2 check inside the submit call; temper does not re-check origin at query time [G] |
| a PR-only SHA | answered like any other once the R-M2-7 prerequisite (`fix-pull-refs`) is merged; NONE before it (F15) | `refs/pull/*/head` in the mirror fetch and in the reachability check |
| a deploy of a new build | old Gates keep their judge; a `--fresh` Gate under the new build becomes the answer once judged, and the old ones show as `older=` | `judge` recorded per job and per result (§1.7) |

What it does **not** guarantee: that no newer Gate is being submitted at this instant (the answer is a snapshot), or that
a single judged verdict is not flaky. Flakiness shows up as CONFLICT across Gates of one judge, or through KNOWN and an
explicit rerun (§5).

### 1.3 Schema

The read needs no new index: `jobs_sha ON jobs (source_sha)` (V1) serves it [V]. The `judge` columns are §1.7's.

### 1.4 HTTP

`GET /api/v1/gate/{sha}` (READ route) → `GateView`:

```json
{"sha": "<40 hex>", "judge": "<40 hex>", "answer": "FAILED", "job": 4711, "state": "FAILED",
 "older": [{"job": 4650, "verdict": "PASSED", "judge": "<40 hex>"}],
 "counts": {…record counts…}, "annotations": ["KNOWN(#1679)"],
 "previous": {"job": 4690, "verdict": "PASSED"},          // RUNNING only
 "conflict": [{"job": 4711, "verdict": "FAILED"}, {"job": 4690, "verdict": "PASSED"}],   // CONFLICT only
 "running": 4720,                                         // CONFLICT only, when present
 "reruns": [{"job": 4740, "state": "PASSED", "outcomes": {"org.x.FooTest": "PASSED"}}],  // §5.3
 "unjudged": [4702], "evidence": "stored|missing"}
```

`{sha}` is 7–40 lowercase hex [P]. A prefix is resolved against the gate candidates' `source_sha`; a prefix that
matches two SHAs is **400** listing them (exit 64). A 40-hex SHA with no candidate is a NONE answer, not a 404.

### 1.5 CLI and exit codes (spec §8.1)

```
temper gate <sha> [--wait] [--timeout 6h] [--json]
gate 0cf5631 FAILED job=4711 judge=1a2b3c4 testcases=21048 failed=1 KNOWN(#1679)
gate 0cf5631 CONFLICT judge=1a2b3c4 jobs=4711:FAILED,4690:PASSED
gate 0cf5631 PASSED job=4760 judge=5d6e7f8 testcases=21050 failed=0 older=4711:FAILED@1a2b3c4,4690:PASSED@1a2b3c4
gate 0cf5631 RUNNING job=4720 judge=1a2b3c4 state=QUEUED previous=4711:FAILED
gate 0cf5631 FAILED job=4711 judge=1a2b3c4 testcases=21048 failed=1 rerun=4740:PASSED
gate 0cf5631 NONE unjudged=4702
```

| Answer | Exit |
|---|---|
| PASSED | 0 |
| FAILED | 1 |
| EMPTY | 3 (the spec's line omits EMPTY, F17; R-M2-9) |
| CONFLICT | **11**, a new code, so a script can never read a flipping gate as either green or a plain red (R-M2-9, Q-M2-22; spec §8.1 is amended) |
| NONE | 8 |
| RUNNING (any non-terminal state, shown as `state=`) | 9 |
| `--wait` deadline | 7 |
| bad SHA / ambiguous prefix | 64 |
| unreachable | 69 |

- `--wait` polls as `wait` does (2 s doubling to 10 s, 5 transient failures ridden out) while the answer is RUNNING.
  NONE and CONFLICT return at once: there is nothing to wait for. `--timeout` is an addition to spec §8.2; its default
  is 6 h, as for `wait` (R-M2-9, Q-M2-7).

### 1.6 Tests (owner WP-A) and mutation probes

| Tier | Test | Probe → must go RED |
|---|---|---|
| unit | `GateEvidenceTest`: a table over candidate sets. No candidates; one PASSED, one FAILED, one EMPTY; VOID-only → NONE + unjudged; newest RUNNING over an older PASSED → RUNNING + previous; PASSED then FAILED → CONFLICT, both listed; FAILED, FAILED → FAILED; PASSED vs FAILED plus a newer RUNNING → CONFLICT + running; VOID newest over PASSED → PASSED | treat VOID as judged (the VOID-newest row goes red); **answer with the latest verdict when judged candidates disagree (R-M2-5's row goes red)**; answer by `submitted_at` instead of id, with an equal-time fixture; let RUNNING beat CONFLICT |
| unit | `GateEvidenceJudgeTest` (R-M2-10): **two judges at one SHA, the older FAILED and the newer PASSED → PASSED with `older=` naming the FAILED**; two same-judge Gates disagreeing → CONFLICT; an old-judge CONFLICT plus one newer-judge PASSED → PASSED, both old ones in `older`; a newer-judge VOID over an older-judge PASSED → PASSED (unjudged never decides the judge); a newer-judge RUNNING over an older-judge FAILED → RUNNING with no `previous` and the FAILED in `older`; pre-V5 rows (`Judge.PRE`) are older than any judge | **pick the answer across all judges** (the two-judge row answers CONFLICT: red); **let an unjudged candidate decide the judge** (the VOID row answers NONE: red); order judges by commit hash instead of by candidate id |
| it-pg | `GateQueryIT`: a pushed gate, plus at the same SHA a row inserted with `unpushed = true`, a superseded `keep` gate, a REFUSED gate, a module-test and a rerun job | **drop `NOT unpushed`; drop `NOT superseded`; drop `recipe_kind = 'gate'` (the rerun job's PASSED answers)**: each is its own red row |
| it-pg | `GateSnapshotIT`: 200× concurrent by-ref submit (superseding) + `gate` read; every answer is one of the two consistent worlds | read candidates and results in two transactions → the loop observes a mixed world (probabilistic; loop) |
| cli | `ExitCodeContractTest` rows: gate 0, 1, 3, 11, 8, 9, 7 (`--wait` against a fake that stays RUNNING), 64 (ambiguous prefix 400) | map CONFLICT to 1 → its row goes red |
| it (git fixture), **prerequisite** | `PullRefGateIT` (R-M2-7; runs once `fix-pull-refs` is merged): a SHA reachable only from `refs/pull/<n>/head` of the fixture origin is **submittable** by `--sha` and, after its Gate finishes, **gate-answerable** | revert the refspec → the submit is `REFUSED(source)`: red |

The `fix-pull-refs` PR (R-M2-7) carries its own tests and verifier; WP-A only depends on it and pins the end-to-end
acceptance above.

**Verifier hook:** an independent agent gets the probe list above, applies each mutation on a scratch branch, and records
red/green per probe in `M2-WPA-verification-<date>.md`. A probe that stays green is a finding.

### 1.7 `judge`: which build judged a Gate (R-M2-10; WP-A)

**What it is.** `judge` is the git commit of the temper build that produced the jars, filtered into them at build time
and recorded on every job and every result. It exists because nothing identifies a build today (F8′):
`project.version` is `0.1.0-SNAPSHOT` for every deploy.

**Where the commit comes from (no new plugin).** Maven core exposes no git property, and every git-aware route
(`buildnumber-maven-plugin`, `git-commit-id-maven-plugin`) is a new plugin. The design therefore uses what the build
already does, resource filtering, fed a user property:
- The root pom gets `<temper.judge>unjudged</temper.judge>` as a default property.
- `controller-version.properties` and the worker's `worker-version.properties`, already filtered for `version=`
  (`temper-controller/pom.xml`, `temper-worker/pom.xml` resources [V]), gain the line `judge=${temper.judge}`.
- A new `ops/build-dist.sh` runs `git rev-parse HEAD` and `git status --porcelain`. It then runs the root build with
  `-Dtemper.judge=<commit>`, or `-Dtemper.judge=<commit>-dirty` when the status is non-empty.
- **[U]**: that a `-D` user property overrides the pom property inside resource filtering is standard Maven behaviour,
  but unverified here. WP-A's first task builds three times (the wrapper on a clean tree, the wrapper on a touched
  tree, a plain `mvn install`) and reads `judge=` out of each jar: it must be HEAD, HEAD`-dirty` and `unjudged`.
- `.mvn/maven.config` is `skip-worktree` (global rules), so `git status` never reports its local repo-path edit. That
  file sets only `maven.repo.local`, which does not change what is built. Whether `.m2-local/` is ignored, and so
  invisible to the status, is **[U]**; if it is not, every build reads as dirty and the probe above shows it.

**Read at start.** `ControllerVersion` gains `judge()`, which reads the same resource. The accepted alphabet is
`^([0-9a-f]{40}(-dirty)?|unjudged)$`. An unfiltered `${…}` placeholder fails start, exactly as an unfiltered version
does today (`versionOf` [V]). Dev and test builds run as `unjudged`.

**The dirty refusal is at deploy.** `ops/deploy-jars.sh` gains one check, run with the others before anything is
copied:
- it reads `judge=` from both jars (`unzip -p <jar> …/controller-version.properties`, and the worker's resource);
- it refuses with exit 3, nothing copied, when the line is missing, `unjudged`, ends in `-dirty`, is not 40 hex, or the
  two jars disagree;
- it prints the judge with the sha256s for the record.

The worker that runs is the deployed jar: the launcher's `run` checks the sha256 of the jar the controller installs
from `[worker] jar`. So the worker's judge equals the controller's, and the protocol handshake needs no judge field
[U: confirm `run` checks the configured jar's sha256].

**Honest limits.**
- The judge is the commit the wrapper saw when the build started. An edit during the build is not detected.
- A jar built by hand with a wrong `-D`, or copied into place without `deploy-jars.sh`, bypasses the refusal, as it
  bypasses every other deploy check.
- What the check does establish: every jar deployed through the documented path names a clean commit, and that commit
  is the same in both jars.

**Schema (WP-A's migration).** The column is added with a default and then has its default dropped. That writes the
value without an UPDATE, so the write-once trigger on `results` never fires [U: PG16 fills it as a catalog default;
the IT below proves it].

```sql
ALTER TABLE jobs    ADD COLUMN judge text NOT NULL DEFAULT 'pre-judge'
  CHECK (judge ~ '^([0-9a-f]{40}(-dirty)?|unjudged|pre-judge)$');
ALTER TABLE jobs    ALTER COLUMN judge DROP DEFAULT;
ALTER TABLE results ADD COLUMN judge text NOT NULL DEFAULT 'pre-judge'
  CHECK (judge ~ '^([0-9a-f]{40}(-dirty)?|unjudged|pre-judge)$');
ALTER TABLE results ALTER COLUMN judge DROP DEFAULT;
```

- Existing rows read as `pre-judge` (`Judge.PRE`). R-12 needs no conversion, and they are older than any judge,
  because the newest judge is chosen by candidate id (§1.1 rule 2).
- `jobs.judge` is the submitting controller's judge, written by `INSERT_JOB`, REFUSED rows included.
- `results.judge` is the **finishing** controller's judge, written by `Finishing.writeResult` and carried in the record
  JSON and `result.json` as `"judge"`. The verdict, KNOWN marks and coverage are computed by that controller [G].
- **Judged across a deploy (R-M2-11).** "Across a deploy" means a unit's **grant-time** judge differs from the
  finishing judge. The submit-time judge does not count (main-thread refinement, a marked guess sent to the maintainer): a
  job that only *waited* in the queue across a deploy ran entirely under the new build.
  - Such a job's record gets `judge-changed=<grant judge7>`. Its Gate is shown in the answer's history, and it is
    **excluded** from the answer and the CONFLICT set. The way out is `--fresh`.
  - **Where the grant-time judge lives:** a column on `units`, `units.granted_judge text`, set by the existing `GRANT`
    statement (`PgUnitStore.GRANT`: `UPDATE units u SET state = 'GRANTED', host = $2, granted_at = now(), …` [V]) as
    one more `SET` term.
  - **Why `units` and not `jobs.granted_judge`:** the grant transaction locks and updates unit rows only. Writing the
    job row there would add a `jobs` row lock to every grant, contending with submit, cancel and supersede, which
    lock jobs.
  - A requeue (GRANTED → QUEUED) leaves the old value until the next grant overwrites it. A unit never granted keeps
    null and is ignored.
  - The rule is "any unit whose non-null `granted_judge` ≠ the finishing judge". It covers the first unit's start and
    M3's multi-unit Gates alike.
  - Schema: `ALTER TABLE units ADD COLUMN granted_judge text CHECK (granted_judge ~ '^([0-9a-f]{40}(-dirty)?|unjudged)$')`.
    Existing rows get null, so pre-judge jobs are never judge-changed (R-12, no conversion).

**Tests and probes.**

| Tier | Test | Probe → RED |
|---|---|---|
| build | the three-build probe above (WP-A's first task) | — (it validates the instrument: the **[U]** on `-D` filtering) |
| unit | `ControllerVersionTest`: a 40-hex judge, `-dirty`, `unjudged` accepted; an unfiltered placeholder fails start | accept the placeholder → red |
| ops | `deploy-jars` test (fixture jars): a dirty, `unjudged`, missing or non-hex judge, or a controller/worker mismatch → exit 3 and nothing copied; a clean, equal pair is copied and its judge printed | **drop the `-dirty` check → the dirty fixture is copied: red**; compare one jar only → the mismatch fixture is copied: red |
| it-pg | `JudgeMigrationIT`: on a V4 database with results, the migration sets `pre-judge` without the trigger firing; a later UPDATE of `results.judge` is still refused; a submit writes `jobs.judge`; a finish writes `results.judge` | write `results.judge` by UPDATE after the insert → trigger error: red |
| it-pg | `GateJudgeIT`: two controller instances with different judges finish Gates at one SHA → the second's answer, the first's in `older` | read the judge from `jobs` for terminal candidates → a mid-job deploy fixture answers under the submitting judge: red |
| it-pg | `JudgeAcrossDeployIT` (R-M2-11), case 1: **queued under X, granted and finished under Y** → no `judge-changed`, counts under Y and answers | **compare the submit-time judge instead of the grant-time one → case 1 is marked judge-changed and excluded: red** |
| it-pg | `JudgeAcrossDeployIT`, case 2: **granted under X, finished under Y** → `judge-changed=X7` on the record, shown in history, excluded from the answer and from CONFLICT (with a same-judge Gate of the opposite verdict at the SHA, the answer is that Gate's verdict, not CONFLICT) | **drop the exclusion → case 2 makes the answer CONFLICT: red**; drop the `granted_judge` write from `GRANT` → case 2 is not marked: red |
| unit | `GateEvidenceJudgeTest` (cont.): a judge-changed candidate never answers, never counts for CONFLICT, and is listed as `judge-changed=<id>` | treat it as judged → red |

---

## 2. WP-C: `subscribe` / `unsubscribe` (R6)

### 2.1 The delivery rule, made exact (R-M2-12)

For a subscriber with cursor `c`, a poll delivers an event `e` iff all three hold:
- `e.seq > c` **at that poll**;
- `e`'s kind is admitted (`--terminal`);
- `e` matches the selector set **read by that same poll**.

**Two ways the cursor moves:**
- **Ack:** the ack of a delivered event moves the cursor to that event's `seq`, as today.
- **Skip-ack (R-M2-12):** a poll that scanned up to `S` and delivered nothing advances the cursor to `S`. The advance
  is computed against the selector set the poll scanned with. If that set changed while the poll was scanning, the
  poll does not advance.
  - **Mechanism:** `subscribers.selectors_version`, bumped by every selector change (§2.3). The poll reads the
    version together with the selectors, then runs
    `UPDATE subscribers SET cursor = GREATEST(cursor, $S) WHERE name = $1 AND selectors_version = $v`.
  - A mismatch updates no row, so an event that a newly added selector would match is never skipped by a scan that
    could not have seen that selector.
  - **Why:** without it, a narrow subscriber's cursor stays put, and every poll rescans every event since the last
    match (revision 1's risk R1). It also lets `gap` mean only "deleted unseen" (below).

**Consequences, each pinned by a test:**
- **A selector added later catches only events after the cursor.** Events that earlier polls scanned are behind a
  skip-acked cursor, so they are not replayed.
- **`subscribe` prints the current state** of what it matches, so nobody waits for an event that already went past:
  - `job:` prints that job's state;
  - `sha:` and `ref:` print the state of every job they match, newest first, at most 20 [P bound]. When truncated, the
    reply ends with a line that **names the command listing the rest** (R-M2-12, s42 follow-up):
    `+K more: temper jobs --sha <sha>` (or `--ref <ref>`).
- **`temper jobs (--sha S | --ref R) [--json]`** (#26) is that command: read-only, over the existing
  `GET /jobs?sha=&ref=&limit=` route (`ApiRoutes.listJobs` [V]), with no new endpoint.
  - It prints one line per job, newest first: `job <id> <STATE|VERDICT> recipe=<kind> sha=<7> ref=<ref>`.
  - It is paged through the route's existing `before`-id paging: 50 per page by default, `--limit` 1..1000.
  - `--sha` takes a 40-hex SHA, case-insensitive and lowercased as in §2.2.
  - Exit 0; 64 for usage; 69 unreachable.
- Removing a selector takes effect at the next poll; an event already delivered and not yet acked is acked normally.
- **`gap` fires only for events retention deleted before any poll scanned them.** Scanned events are behind the
  cursor, and the floor check (`floor_seq > cursor`) cannot fire for them.
- At-least-once delivery of matching events and the one-line repeat are unchanged (architecture §9, events row).

### 2.2 Typed selectors

A selector is stored as one of `job:<id>`, `sha:<40 hex>`, `ref:<name>` [P]. Matching:
- `job:` against `events.job_id`;
- `sha:` against `payload.sha`;
- `ref:` against `payload.ref`, the requested ref. A job submitted by SHA has none and never matches a `ref:`
  selector; the reply says so.

CLI classification of the bare argument; an explicit prefix wins [P]:
- SHA hex is **case-insensitive and normalised to lowercase** (R-M2-12), both bare and after `sha:`. The patterns
  below match case-insensitively.
- `job:N`, `sha:S`, `ref:R` are taken as written (S lowercased);
- `^[1-9][0-9]{0,18}$` → job. **Only `subscribe` checks that the job exists** (404 → exit 64); `unsubscribe` is
  idempotent and removes a selector whether or not its job exists or the selector was present (R-M2-12);
- `^[0-9a-fA-F]{40}$` → sha;
- `^[0-9a-fA-F]{7,39}$` → **refused** as ambiguous (exit 64: "use sha:<full> or ref:<name>"), since a short SHA matches no
  payload (F3);
- anything else → ref (validated by `Ref.ref`).

### 2.3 HTTP and store

`PUT /api/v1/subscribers/{name}/selectors`, body `{add: [...], remove: [...]}` → `{name, selectors, cursor}` (WRITE).
If PUT does not reach the router (F2, **[U]**), the route is `POST …/selectors` with the same body.
- It creates the subscriber if absent, with the long-poll's cursor rule (`GREATEST(max(seq), floor)`, Q-14), so
  selectors can be set before the first `watch`.
- One `UPDATE … SET selectors = <(old ∪ add) − remove, sorted, distinct>, selectors_version = selectors_version + 1`
  statement. Concurrent changes serialise on the row lock, so neither loses the other's change. A change that
  leaves the set equal still bumps the version, which is harmless: at worst one skip-ack is lost.
- `EventStore` gains `selectors(String name, List<Selector> add, List<Selector> remove) → Promise<SubscriberRow>` and
  `skipAck(String name, long upTo, long version) → Promise<Boolean>`. `SubscriberRow` gains `selectorsVersion`.
- `EventFeed`: when a poll's scan ends with no match (`batch.size() < page`, nothing selected), it calls `skipAck` with
  the highest `seq` scanned and the version it read, then waits as today.

**Schema (the next free migration number):**
- `ALTER TABLE subscribers ADD COLUMN selectors_version bigint NOT NULL DEFAULT 0`.
- `subscribers.selectors` keeps its jsonb string array; its content becomes the typed form. Under R-12, `UPDATE subscribers SET selectors = '[]' WHERE selectors <> '[]'` resets existing rows
instead of converting them. No verb could set them, so this is expected to touch nothing [U: check bigboy's table].

### 2.4 CLI

```
temper subscribe <job|sha|ref> --subscriber <name>     temper unsubscribe <job|sha|ref> --subscriber <name>
subscribe cto ADDED job:4711 state=RUNNING selectors=job:4711
subscribe cto ADDED ref:rewrite/x jobs=4730:RUNNING,4711:FAILED selectors=job:4711,ref:rewrite/x
unsubscribe cto REMOVED job:4711 selectors=ref:rewrite/x
unsubscribe cto ABSENT job:9999 selectors=ref:rewrite/x
```

Every reply prints the resulting scope, `selectors=all` when the set is empty, so F4's narrowing and widening are on
screen (Q-M2-9 asks whether they should exist at all). Exit 0; 64 for usage, an unknown job, an ambiguous short SHA
or a bad name (400); 69 unreachable.

### 2.5 Tests and probes

| Tier | Test | Probe → RED |
|---|---|---|
| unit | `SelectorTest`: the classification table, incl. a ref named `4711` given with the `ref:` prefix | classify 7-hex as sha |
| unit | `EventFeedSelectorTest` (fake store): typed matching; a ref selector against a by-SHA job | untyped equality (F3) → the `ref:4711` vs job 4711 row goes red |
| it-pg | `SelectorDeliveryIT`: §2.1's consequences end to end through `next`/`ack`; 100× concurrent add/remove from two clients → the final set equals the serial result | read-modify-write in two statements → a lost update in the loop |
| it-pg | `SkipAckIT` (R-M2-12): (1) a poll with no match advances the cursor to the last scanned `seq`; (2) a selector added during the scan (a test hook between the read and the skip-ack) → no advance, and the next poll delivers the matching event; (3) after a skip-ack, retention deleting the scanned events → no `gap`; deleting unscanned events → `gap` | **skip-ack without the version predicate → case 2 loses the event: red**; drop skip-ack → case 1's cursor stays: red; compare the floor to the scanned `seq` instead of the cursor → case 3 is a spurious gap: red |
| unit | `SelectorTest` (cont.): `SHA:0CF5…` and a bare uppercase 40-hex are stored lowercase; `unsubscribe job:9999` is 0 with `ABSENT` and makes no 404 lookup | case-sensitive match → red; existence check on unsubscribe → red |
| cli | `subscribe sha:`/`ref:` reply lists matched jobs' states, capped at 20; the truncation line names `temper jobs --sha …`/`--ref …` | drop the command from the line → red |
| cli | `JobsAgainstControllerTest` (#26): `temper jobs --sha`/`--ref` lines, newest first, paged across two fake pages; `--json`; exit 0/64/69 | stop after the first page → red |
| cli | `SubscribeAgainstControllerTest`; ExitCodeContract rows 0/64 | — |

---

## 3. WP-B: `temper diff` (R4)

### 3.1 Sides

- `diff <a> <b>`: side A = job `a`, side B = job `b`.
- `diff <job> --base <sha>`: side B = `<job>`; side A = the Gate that `gate <sha>` (§1.1) answers about:
  - for PASSED, FAILED or EMPTY, the answering job;
  - for RUNNING, its `previous` [G];
  - for NONE, there is no base: exit 8;
  - for **CONFLICT**, also exit 8, `base=conflict jobs=…` [G] (Q-M2-26). Picking either job would print the latest,
    which R-M2-5 forbids for the merge question, and a diff against an arbitrary side of a flip says nothing.

  Reusing `GateEvidence` means the two verbs never disagree about what "the gate at S" is. The line names the base
  (`base=<id>`).
- Both sides must be **terminal** (a `results` row exists): testcases arrive per unit, so a running job's rows are
  partial. A non-terminal side → exit 9 [G].
- Any recipe pair is allowed (a rerun job against its parent included).
- **A VOID, ERROR, CANCELLED or REFUSED side, A or B, is refused** with exit 8: `side not judged: <verdict>`
  (R-M2-13, amended s44: a REFUSED job never runs, so "not terminal" (9) would wait forever).
  Revision 1–4's guess of diffing it with `a=VOID` printed is overruled.
- **An EMPTY B** is a B with 0 testcase rows, whatever its verdict (so every A key is `removed`). It is reported normally
  and exits 1 (R-M2-13, amended s44): a run that judged nothing is not clean, so a PASSED B with 0 rows also exits 1.

### 3.2 Semantics (domain, pure; `domain/diff/TestDiff.java` **+**)

The identity key is `(class, name)` as the report names them (`TestcaseRow.className`, `name`).

Per side, the rows of each key are folded into one outcome. A key can repeat: dynamic tests share a display name, or
one class is run by two units. The fold:
- **failing** if any row is `failed`/`error`;
- else **skipped** if every row is `skipped`;
- else **passed**.

`duplicates=N` on the counts line says how many keys had more than one row [P].

| Section | Rule |
|---|---|
| `added` | key in B, not in A. Each entry carries B's folded outcome, `class#name [failed]`, so a new failing test is never hidden in a list of additions |
| `removed` | key in A, not in B |
| `newly-failing` | in both; failing in B; not failing in A. This **includes skipped → failing** (R-M2-13) |
| `newly-passing` | in both; failing in A; **passed** in B |

The transitions passed → skipped, skipped → passed and **failing → skipped** are in none of these sections. They are
counted as `skip-changed=N` and listed in `--json` only, and they do not affect the exit code (R-M2-13). A failing →
skipped transition is not a fix and must not read as one. Skipped → failing is not here: it is newly failing (above).

**Pruned sides (F16).** A side whose stored rows number fewer than its record's `counts.testcases` has been pruned by
retention. A write-once complete transaction rules out "never stored" [V]. Such a diff is refused with exit 8 and
`pruned=a|b` [G], never computed over half-deleted rows.

**Guarantee:** for two terminal, unpruned jobs, the four sets are exactly the set algebra above over the rows stored when
each job completed.

**Mechanism:**
- Both jobs' rows and records are read in one `REPEATABLE READ, READ ONLY` transaction. In the `--base` form the base
  selection (`GateEvidence` over the gate read) is made in that same transaction (maintainer s44), so nothing moves between
  choosing the base and reading the sides.
- Rows of a terminal job are write-once, so the snapshot holds the complete set. A retention delete that commits during
  the read is either fully seen (→ pruned) or not seen at all.
- The diff is computed in memory: about 2 × 21 k rows for two Gates (T1 sizes [V]).

### 3.3 HTTP

`GET /api/v1/diff?a=<id>&b=<id>` or `?a=<id>&baseSha=<sha>` (READ). The base form puts the base on side A and `a` on
side B, matching the CLI.
- 200 → `DiffView{a: {job, verdict, recipe}, b: {…}, added: [{class, name, outcome}], removed: [...],
  newlyFailing: [...], newlyPassing: [...], skipChanged: [...], counts: {added, addedFailing, removed, newlyFailing,
  newlyPassing, skipChanged, duplicates}}`.
- 404 for an unknown job.
- 200 with a typed `DiffRefusal` body (`pruned`, `noBase`, `baseConflict`, `notJudged{side, verdict}`,
  `notTerminal`), so the CLI maps them to 8/9 without parsing text.

### 3.4 CLI

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

The sections come first and the counts line last (spec §8.2). Exit codes (R-M2-9 for Q-M2-6, R-M2-13 for Q-M2-30):
- **0** nothing newly failing (skipped → failing included), no added failing test, and B not EMPTY. `skip-changed`
  never makes it non-zero;
- **1** otherwise, including an EMPTY B;
- **8** no base, a conflicting base, a pruned side, or a side not judged (VOID/ERROR/CANCELLED);
- **9** a side not terminal;
- **64** usage, or an unknown job (404, as for every job verb);
- **69** unreachable.

### 3.5 Tests and probes

| Tier | Test | Probe → RED |
|---|---|---|
| unit | `TestDiffTest`: each section; duplicates folded; failing → skipped is not newly-passing; an added failing test is marked | key by class only; newly-passing accepts skipped |
| unit | `DiffBaseTest`: `--base` over each `GateEvidence` answer, incl. CONFLICT → 8 | use the latest judged job on CONFLICT → red |
| it-pg | `DiffIT`: two Gates' rows; a pruned side (the retention door opened in the test) → pruned | drop the pruned check → the pruned job's tests all show as removed: red |
| unit | `DiffExitTest` (R-M2-13): skipped → failed exits 1; failed → skipped exits 0 with `skip-changed=1`; EMPTY B exits 1; VOID, ERROR or CANCELLED on either side → 8 `side not judged: <verdict>` | **treat skipped → failed as skip-changed → exit 0: red**; let skip-changed raise the exit → red; exit 0 on EMPTY B → red; diff a VOID side instead of refusing → red |
| cli | `DiffAgainstControllerTest` + `ExitCodeContractTest` rows 0/1/8/9 | exit 0 on an added failing test → red |
| **exit gate** | **one real diff, cross-checked by hand** (§10) | the hand instrument is validated on a doctored pair first |

---

## 4. WP-D: known-failure registry, `KNOWN(#n)` (R7)

### 4.1 What is reused from `refusal`, and what is not

| Part | Reused? | Why |
|---|---|---|
| surface shape: `list [--all]`, `add`, `expire`; one line per row; 409 → exit 2; 404 → exit 64 | **yes**: the same grammar-group mechanism (`CommandLine.GROUPS`), the same `Lines` quoting helpers, the same route pattern `POST …/{id}/expire` | the shapes match exactly; a second idiom would be a second thing to learn |
| store pattern (`list`, `add`, `expire` keeping the first expiry) | **yes**, as a sibling `KnownStore` with the same method contract | same lifecycle: rows are never deleted (F19) |
| the table and the matcher | **no** | refusals are keyed by a code and match *submit shapes* before anything runs; known entries are keyed by a generated id, match *testcase rows* at finish, and need an issue number for `KNOWN(#n)`. One table would carry columns that are null for half its rows and a matcher union that no consumer reads whole |

### 4.2 Data

```sql
CREATE TABLE known (
  id bigserial PRIMARY KEY,
  test_id text NOT NULL CHECK (test_id ~ '^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(#.+)?$'
    AND (position('#' in test_id) = 0 OR length(test_id) - position('#' in test_id) <= 512)),
  -- not `#.{1,512}`: Postgres refuses a regex repetition count above 255, which would fail every insert (PR #28)
  issue int NOT NULL CHECK (issue > 0), reason text NOT NULL, until_text text NOT NULL,
  created_by text NOT NULL CHECK (created_by ~ '^[A-Za-z0-9_]{1,64}$'),
  created_at timestamptz NOT NULL DEFAULT now(), expired_at timestamptz);
CREATE UNIQUE INDEX known_active ON known (test_id) WHERE expired_at IS NULL;
```

- `testId` is `class#name` or `class`, where `class` is the report's classname (nested: `Outer$Inner`). Matching is
  exact string equality [P]. A class entry covers every case of that class; a `class#name` entry covers exactly that
  case. Parameterised invocations are distinct names, so register the class to cover them all [G].
- One active entry per `testId` (409 on a second) [P], so a failure maps to one issue.
- `created_by` is the CLI's owner tag: **attribution, not authority**. With one write token (spec §7), anyone holding
  it can add or expire. The spec's "the maintainer expires the entry" is a convention the token does not enforce.

### 4.3 Where KNOWN is applied

In `Finishing.finish`, inside the completing transaction:
- the active `known` rows are read in that transaction;
- each failed/error row in the record (`failed[]`, and the observed failures of a VOID/ERROR record) gets
  `known = "#<issue>"`;
- one annotation `KNOWN(#n)` is added per distinct issue, sorted.

The verdict is not touched: `Verdicts` never sees the registry. The event line appends the annotations (spec §8.3
example). A rerun job's record is marked the same way.

**Guarantee:** a record, and the `finished`/`cancelled` event written with it, carry KNOWN for exactly the entries active
at the `known` read. That read is one statement inside the completing transaction, after the job lock (READ COMMITTED: an
add that commits after that statement but before the transaction commits is not marked, although its `created_at` may be
earlier than `finished_at`).

**Not guaranteed:** an entry added after a job finished does not annotate that job (records are immutable, R3), and an
entry expired later stays on old records. `temper result` shows the record as written [G]; a live overlay would make the
cited record and the printed one differ (Q-M2-10).

### 4.4 HTTP and CLI

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/known?all=true\|false` | — | `[KnownDto{id, testId, issue, reason, until, createdBy, createdAt, expiredAt?}]` |
| POST | `/known` | `KnownAddRequest{testId, issue, reason, until, createdBy}` | 201 · 400 · 409 (an active entry for that testId) |
| POST | `/known/{id}/expire` | — | 200 · 404 |

```
temper known add <testId> --issue N --reason "…" --until "<condition>" [--owner O]
temper known list [--all]          temper known expire <id>
known 12 ACTIVE test=org.x.EmberAmnesiacRestartTest issue=1679 until="#1679 closes" reason="…" by=cto
```

- `--until` is required: spec §8.2 lists it, and the registry exists so that an entry does not outlive its condition.
- Exit codes: 0; 2 for a 409; 64 for usage, a 404 or a 400; 69 unreachable.
- `known list --ledger` stays a later form (`known - USAGE not in M0`, exit 64), pinned by a CLI test, since the ledger
  left M2 (R-M2-3).

### 4.5 Tests and probes

| Tier | Test | Probe → RED |
|---|---|---|
| unit | `KnownMatchTest`: class entry, case entry, nested `Outer$Inner`, no prefix matching (`Foo` must not cover `FooBar`) | prefix match; match on the simple name |
| it-pg | `KnownFinishIT`: entry active at finish → `failed[].known`, `KNOWN(#n)`, verdict FAILED; expired entry → none; entry added after finish → record unchanged | **let KNOWN change the verdict (all failures known → PASSED) → red**; read `known` outside the completing transaction (a fixture commits an add in between) |
| it-pg | `KnownStoreIT`: 409 on a second active entry; expire keeps the first expiry | — |
| cli | `KnownAgainstControllerTest`, ExitCodeContract rows, the `--ledger` later-form row | — |

---

## 5. WP-E: explicit rerun, `temper rerun <job>` (R7; R-M2-4)

There is no automatic trigger: a rerun runs only when a client asks for one. It is a **new job**, so the parent's
`finished` is never delayed and its write-once record is never touched. Spec §9's trigger conditions become the
**preconditions** of the request.

### 5.1 Preconditions

These are checked on the submit path. Each failure is `REFUSED(rerun)` with a detail, persisted like every refusal:
1. The parent exists (else 404 → exit 64), is terminal, and its recipe is `gate` or `moduleTest` (R-M2-4). This widens
   spec §9 ("Gate only"), a recorded deviation (§10). A rerun job is never a parent: no rerun of a rerun.
2. The parent's verdict is `FAILED(tests)` [G]. `missing-classes` is a crash, and `rc`/`timeout` are not flakes.
3. The **target classes** are the classes of the parent record's `failed[]` rows that are not all marked `known`. There
   must be 1 to 3 of them (spec §9, maintainer-accepted ≤ 3):
   - 0 → "all failing classes are KNOWN";
   - more than 3 → "N failing classes (max 3)".

   The KNOWN marks are read from the parent's immutable record, so the target set is fixed and citable, and a repeat
   request deduplicates [G] (Q-M2-24 asks about the current registry and a `--class` choice instead).
4. Each target class has a module and runner in the parent's `manifests` rows. Absent (pruned at 90 d, F16, or a drift
   class the manifest never expected) → refused with "no module for <class>".

### 5.2 The rerun job

- **A new job, linked to its parent.** The recipe is `rerun{of: <parent>, classes: [...]}` (canonical), with
  `recipe_kind = 'rerun'`.
  - Its source is the parent's resolved Pushed SHA, taken as is and not re-checked against origin [G]: the parent
    already passed the check.
  - `jobs.ref` is null, so it never supersedes and is never superseded.
  - `jobs.rerun_of` holds the parent.
  - The priority is the parent's unless `--prio` is given; the owner comes from the CLI.
- **Dedup:** the key is (SHA, canonical rerun recipe, runner version), so a second `temper rerun 4711` returns the
  first rerun job (exit 10). `--fresh` asks for another.
- *Superseded by R-M2-16: one `rerun` unit runs every target class (a job's units share one workspace). The text below is kept as revision 4 wrote it.*
- **Units:** one `rerun-<k>` unit per class, k = 1..3, of kind `UnitKind.RERUN` (30 min, spec §4.3; the enum value
  exists [V]). Each is a ModuleTest-shaped plan in the rerun job's **own fresh checkout**, which is spec §9's
  "fresh checkout (unsharded)" through the normal `PrepareJob` path (F14):
  - step 1: `mvn -B -pl <module> -am install -DskipTests`;
  - step 2: `mvn -B -pl <module> test -Dtest=<class>` for surefire, or `verify -Dit.test=<class>` for failsafe.

  A `@Nested` class reruns its outermost class's selection and is judged on its own rows [U: surefire's `-Dtest` with
  `Outer$Inner`]. Refusals run over the args as for every unit (D5), and `PortGroupRule` decides bb-hold. Whether
  30 min covers an `-am install` for `aether/ember` is **[U]** (Q-M2-25).
- **Evidence first holds by construction.** The rerun job writes only into its own `jobs/<rerun>/` directories. The
  parent's unit directory and workspace are never touched, and the parent's small evidence was pulled before the
  parent's record was written (m1 §3.2), which says `stored` or `evidence=missing`. No ordering check is needed.
- **Cancel during a rerun, re-checked under this shape.**
  - Cancelling the rerun job ends that job CANCELLED. Its record still carries the parent's original verdict, and each
    class ends `NOT-RUN(cancelled)`.
  - The parent cannot be cancelled: a rerun is accepted only against a terminal parent. Its record and verdict are
    never in play.
  - Supersede cannot touch either: the parent is terminal (supersede acts on QUEUED/UNKNOWN/RUNNING only, `VICTIMS`
    [V]), and the rerun job has no ref.

  Revision 1's problem (a cancel during in-job reruns turning FAILED into CANCELLED, through the job-level
  `VerdictInput.cancelled`) therefore cannot arise, and `Verdicts` needs no change.

### 5.3 Verdict, record and link

- The rerun job is judged by the normal `Verdicts`: its units are primary for it, and `units.rerun` stays `false`
  (F9). For recipe `rerun`, `Finishing` adds:
  - `rerun-of=<parent>`;
  - `original: {job, verdict, reason, failed: [{class, name, known}]}` for the target classes, copied from the parent's
    immutable record at the rerun's submit (it is part of the rerun recipe's canonical form, so it cannot drift);
  - per class, `rerun(<class>)=PASSED|FAILED|NOT-RUN(<reason>)`:
    - PASSED: the unit exited rc 0 and reported ≥ 1 row for the class, none failing (*superseded by R-M2-16: ≥ 1 row of the class, none failing; the unit's rc is shared*);
    - FAILED: a failing row for the class;
    - NOT-RUN(reason): VOID, ERROR, EMPTY, timeout, refused or cancelled [P] (Q-M2-15). The spec names only
      PASSED|FAILED; reporting a rerun that never ran as either would be false.
- **The parent's record is never changed** (R3, F10). This deviates from spec §5.3/§9, which put `rerun PASSED|FAILED`
  on the failed job's record. It is forced by the explicit trigger: the parent's single write happened before anyone
  asked.
- **The verdict stays FAILED**, as the spec intends. The mechanism: the parent's record is immutable, and `gate` admits
  only `recipe_kind = 'gate'` (§1.1, mutation-probed). A rerun's PASSED can therefore never answer the merge question.
- **The link is on the read side [P]** (Q-M2-25):
  - `GET /jobs/{id}` (`JobView`) gains `reruns: [jobId]` from `jobs.rerun_of`;
  - `GateView` lists the answering job's reruns with their per-class outcomes;
  - the `gate` and `result` lines add `rerun=<id>:<STATE|VERDICT>`.

  These are live views over immutable records, and nothing cited changes.
- **No FLAKY in M2** (R-M2-9 accepts Q-M2-27's guess; R-M2-10 option (i)). The rerun record carries both facts, the
  original outcome in `original` and the rerun outcome per class, and draws no conclusion from them. A rerun never
  changes the gate answer: the `gate` line shows `rerun=<id>:<VERDICT>` as information only.
  - *Revision 3 had derived FLAKY on read, following the s39 relay ("FLAKY annotation as derived when both are read").
    s40 accepted Q-M2-27's "no FLAKY" guess, made against revision 2, and that later ruling governs. Revision 4
    removes the derivation.*
- **The maintainer's marked guess for later (R-M2-4):** if reruns become automatic, the shape is both outcomes plus FLAKY.
  The `original` + per-class outcomes in M2's record are already the two halves of it.

### 5.4 Schema

`ALTER TABLE jobs ADD COLUMN rerun_of bigint REFERENCES jobs(id); CREATE INDEX jobs_rerun_of ON jobs (rerun_of) WHERE
rerun_of IS NOT NULL;`. Existing rows get null, which is true of them (R-12 needs no conversion). `recipe_kind` has
no CHECK (V1) [V].

### 5.5 HTTP and CLI

`POST /api/v1/jobs/{id}/rerun` (WRITE), body `{submittedBy, priority?, fresh?}`. It builds a `SubmitJob.Request`,
so dedup, refusal persistence and the queued event go through the one submit path. → 200 `SubmitResponse` · 422
`RefusedResponse` · 404.

```
temper rerun <job> [--prio N] [--fresh] [--owner O] [--json]
rerun 4740 QUEUED of=4711 classes=org.x.FooTest,org.x.BarTest sha=0cf5631 deduplicated=false
temper wait 4740
wait 4740 PASSED reason=… testcases=14 failed=0 rerun-of=4711 rerun(org.x.FooTest)=PASSED rerun(org.x.BarTest)=PASSED
```

Exit codes, as for submit: 0 accepted, 10 deduplicated, 2 refused (`reason=rerun detail="…"`), 64 usage or unknown
job, 69 unreachable.

### 5.6 Tests and probes

| Tier | Test | Probe → RED |
|---|---|---|
| unit | `RerunPreconditionsTest`: each refusal; 3 classes accepted, 4 refused; KNOWN-covered classes excluded; an `mvn` parent refused | `<= 3` → `< 3`; count cases instead of classes; ignore the KNOWN marks; accept `FAILED(missing-classes)` |
| unit | `RerunOutcomeTest`: PASSED/FAILED/NOT-RUN per class from unit states and rows; no annotation other than `rerun-of` and `rerun(<class>)=…` is produced | call a VOID unit FAILED; call 0 rows PASSED; emit FLAKY → red |
| unit | `RerunPreconditionsTest` (cont.): moduleTest parent accepted; rerun parent refused; non-terminal parent refused | accept a non-terminal parent → red |
| it-pg | `RerunCancelIT`: cancel the rerun job mid-run → CANCELLED, `original` intact, every class NOT-RUN(cancelled); parent record byte-identical; `gate` answer unchanged | — |
| it-pg | `RerunSubmitIT`: a repeat deduplicates; `--fresh` makes a new job; the parent's `results.record` is byte-identical before and after the rerun finishes; `gate` still answers FAILED | **drop the `recipe_kind = 'gate'` filter → the rerun's PASSED answers `gate`: red** (shared with §1.6) |
| docker | `RerunIT` (acceptance): a fixture Gate with one deterministic red class A and one class B that fails only on its first run (a marker file) → `rerun(A)=FAILED`, `rerun(B)=PASSED`, `original` = the parent's FAILED, the parent's record, `finished` time and `gate` answer unchanged | run the rerun without `-Dtest` (the whole module) → the rows-per-class assertion goes red |
| cli | `RerunAgainstControllerTest`, ExitCodeContract rows 0/10/2/64 | — |

---

## 6. Honest guarantees for the new operations

| Operation | Guarantee | Mechanism | Where it stops holding |
|---|---|---|---|
| `gate <sha>` | One consistent snapshot of the pushed, non-superseded Gates at exactly that SHA. Among the newest judge's Gates: their single verdict, or CONFLICT naming every disagreeing judged Gate, never the latest one alone. Older judges' Gates are listed as `older=` | one `REPEATABLE READ, READ ONLY` read; supersede and finish each commit atomically; `judge` on every job and result | A SHA that passed the Pushed check and later left origin is still answered; PR-only SHAs need R-M2-7's PR; within one judge a CONFLICT is permanent; the judge is only as honest as the deploy path (§1.7); the answer can be stale by the time it is printed |
| selectors | A selector change applies from the next poll; events above the cursor are matched against the set that poll reads; a poll with no match advances the cursor only if the selector set it scanned with is still current | selectors and `selectors_version` read per poll; cursor moves on ack or on a version-guarded skip-ack; one-statement update | Events the cursor has passed (delivered or scanned) are never replayed; `gap` covers only events deleted unscanned |
| `diff` | For two terminal, unpruned jobs, the sets are exact over the rows stored at completion | write-once rows; one snapshot; pruned detection against the record's count | Pruned, non-terminal or conflicting-base sides are refused, not approximated |
| KNOWN | A record's KNOWN marks (and its event's) are exactly the entries active at the one `known` read inside its completing transaction, after the job lock; the verdict is unchanged | registry read inside `Finishing`; `Verdicts` never reads it | Entries added or expired later do not alter old records |
| `rerun` | A rerun runs the parent's non-KNOWN failing classes (≤ 3) in a fresh checkout of the same SHA. Its own record holds the original verdict and the per-class outcomes. It never changes the parent's record, its `finished` time or the `gate` answer | separate linked job; dedup on (SHA, rerun recipe); write-once results; `gate` filters `recipe_kind` | A rerun that cannot run is `NOT-RUN(reason)`; reruns of jobs past 90 d are refused (no manifest) |

---

## 7. Changes per module (owner WP in brackets; **+** new)

| Module | Changes |
|---|---|
| build and ops | root pom `temper.judge` default; `judge=${temper.judge}` in both version resources; **+** `ops/build-dist.sh`; `ops/deploy-jars.sh` judge check [A, §1.7] |
| temper-domain | **+** `gate/GateEvidence`, `gate/Judge` [A]; **+** `subscribe/Selector` [C]; **+** `diff/TestDiff` [B]; **+** `known/KnownEntry`, `known/KnownMatch` [D]; **+** `rerun/RerunRequest` (preconditions), `rerun/RerunOutcome` [E]; `model/Recipe` + `Rerun`, `plan/Planner` rerun units [E]; `exit/ExitCode` + `CONFLICT(11)` [A] |
| temper-protocol | **+** `GateView` [A], `SelectorChange` [C], `DiffView`, `DiffRefusal` [B], `KnownDto`, `KnownAddRequest` [D], `RerunRequest` [E]; `RecipeDto` + `rerun` [E]; `JobView` + `reruns` [E] |
| temper-store | `JobStore.gateCandidates(sha)`, `jobs.judge`/`results.judge` migration, `INSERT_JOB` and `Finishing.writeResult` write the judge [A]; `EventStore.selectors` [C]; `ResultStore.diffSides(a, b)` [B]; **+** `KnownStore`, `PgKnownStore`, `Finishing` KNOWN marks [D]; `Finishing` rerun annotations, `jobs.rerun_of` [E]; one migration per WP that needs one (C, D, E), numbered at merge |
| temper-controller | **+** `api/GateRoutes`, `SubscriberRoutes`, `DiffRoutes`, `KnownRoutes`, and a rerun route on the jobs routes (one file per WP, so `ApiRoutes` is not a shared hot file); `submit/SubmitJob` rerun request; `config/ControllerVersion.judge()` [A]; `Controller` wiring |
| temper-worker | `worker-version.properties` gains the `judge=` line only [A]; no code |
| temper-cli | `CommandLine` verbs and grammar, incl. the read-only `jobs (--sha S \| --ref R) [--json]` verb (#26, over the existing `GET /jobs`) [C]; `Lines` renderers; `transport/Controller` calls; `ExitCodeContractTest` rows |

---

## 8. Work packages and order (R-M2-1)

The letters are **kept** from revision 1 (A gate, B diff, C subscribe, D known, E rerun), so the probe tables,
verification-file names and Q references stay stable. Only the order changes. Revision 1's WP-F (Patch/Bundle) is cut
(§12).

```
WP-A gate ──► WP-C subscribe ──► WP-B diff (reuses GateEvidence) ──► WP-D known ──► WP-E rerun (uses KNOWN marks)
```

| WP | Content | Size (main + test LOC, est.) |
|---|---|---|
| **A** gate | §1, incl. §1.7 `judge`. Prerequisites: `fix-pull-refs` (R-M2-7) merged; `fix-supersede-key` (R-M2-8) is independent | **M**: ~0.6k + 0.8k + ops scripts |
| **C** subscribe | §2 | **S**: ~0.3k + 0.4k |
| **B** diff | §3 + the hand cross-check (§10) | **S**: ~0.4k + 0.5k |
| **D** known | §4 | **M**: ~0.6k + 0.6k |
| **E** rerun | §5 | **M**: ~0.6k + 0.7k + docker fixture |

- **Merge order is the ruled order.** Work on a later WP may start early where it does not depend on an earlier one
  (C does not depend on A), but it merges in order.
- **Shared files** are `CommandLine.java`, `Lines.java`, `ExitCodeContractTest`, `Controller.java` wiring and the
  migration number. Each WP edits only its own entries and rebases on merge.
- **Handoff contract** (as m1 §10):
  - `jbct-reviewer` reviews each WP;
  - an **independent verifier, never the author**, runs the WP's probe table red/green and records it in
    `M2-WP<x>-verification-<date>.md`;
  - the build is a root `mvn install` through `build-runner`, which never writes `~/.m2`;
  - host-tier runs happen only in maintainer windows, under the suite lock.

---

## 9. What M2 does not change

- The §12.1 sizing report comes **after** M2, and M4 is not pulled ahead: the cloud hosts are busy with rc5 (R-M2-6).
  M2 adds no host-tier work beyond the rerun docker IT and the hand `diff` check on bigboy.
- The worker's code and the worker protocol are unchanged; the worker jar only carries the `judge=` resource line.
- Dedup is unchanged (R-M2-10): its runner component stays `project.version`. *(Amended by owner decision D-6 A1,
  2026-10-24: the runner component is the whole build version, judge included. Spec §2.3.)*

---

## 10. Exit gate

1. **CLI tests:** `ExitCodeContractTest` gains a row per new exit path, each a whole CLI run against the fake
   controller:
   - gate 0/1/3/11/7/8/9/64;
   - subscribe 0/64;
   - diff 0/1/8/9/64;
   - known 0/2/64, and the `--ledger` later form 64;
   - rerun 0/10/2/64.

   Probe: change one mapping in `ExitCode` or in the command → its row goes red.
2. **One real `diff`, cross-checked by hand,** by an independent verifier:
   - Pick two real terminal Gates on bigboy that differ. T1 red `scratch/temper-t1-red` against its green parent is the
     natural pair **[U: still within 90-day testcase retention]**.
   - Export both sides with `GET /jobs/{id}/testcases`, then per side
     `jq -r '.[] | "\(.class)#\(.name)\t\(.outcome)"'` (`TestcaseDto` wire names [V]). Fold duplicates by hand per
     §3.2, `sort`, and derive the four sets with `comm`/`join`.
   - **Validate the instrument first:** run the same pipeline on a doctored copy (one row deleted, one outcome flipped
     to `failed`, one row added) and confirm it reports exactly those three.
   - Compare the hand sets with `temper diff --json` member for member. Record both, the commands and the verdict in
     `M2-diff-crosscheck-<date>.md`.
3. **Recorded spec deviations at M2 exit** (the maintainer amends spec §12 and the named sections when M2 closes):
   - **The ledger is not delivered** (R-M2-3). Spec §12 lists it as M2 content. M2 exits without it, and it becomes a
     separate later item. `known list --ledger` stays a pinned later form (exit 64).
   - **`Patch`/`Bundle` are not delivered** (R-M2-2): spec §12's M2 row; revisit in M3.
   - **Rerun is explicit and a separate job** (R-M2-4). Spec §9 describes an automatic, Gate-only rerun annotated on the
     failed job's record. M2 has `temper rerun <job>` against a Gate or ModuleTest, and the outcomes go on the rerun's
     own record.
   - **CONFLICT is an answer, judged per build** (R-M2-5, R-M2-10). Spec §8.2 says the latest result is still the
     answer and that conflicts span all terminal Gates. M2 answers CONFLICT among the newest judge's Gates, and lists
     older judges' Gates as `older=`. Spec §8.1 gains exit 11.
   - **`/known` expires by `POST …/expire`**, not spec §7's `DELETE` (R-M2-9, Q-M2-11).

---

## 11. Open questions for the maintainer

**No open questions.** Q-M2-1 to 30 are ruled (§13), except 16, 17, 18 and 21, which are notes for M3 (§12). One
marked guess is with the maintainer: R-M2-11's reading that "across a deploy" means the grant-time judge.

<details><summary>Q-M2-28 as asked (ruled R-M2-11)</summary>

| # | Question | Guess |
|---|---|---|
| **Q-M2-28** | **`judge` mechanics** (§1.7, all [G]): (a) the newest judge at a SHA is the judge of its highest-id judged or running candidate, so unjudged candidates never decide it, and after a rollback a `--fresh` Gate under the old build makes that build newest again; (b) a terminal candidate counts under its **finishing** controller's judge, and a job that spans a deploy gets `judge-changed=`; (c) dev and test builds run as `unjudged`, and only `deploy-jars.sh` refuses it; (d) the handshake gets no judge field, relying on the launcher's sha256 check | As stated |

</details>

<details><summary>Revision 2–3 open questions, now ruled (kept for the record)</summary>

| # | Question | Guess |
|---|---|---|
| **Q-M2-19** | **TOP. maintainer ruling M1-3 (`refs/pull/*/head`) is not implemented** (F15, confirmed on disk). Until it is, `gate` answers NONE for every PR head not on a branch. That matters beyond M2: it decides whether temper can post rc5 PR statuses, which the maintainer is weighing | **Recommendation: implement in WP-A.** Add `+refs/pull/*/head:refs/pull/*/head` to the mirror fetch, and run `for-each-ref --contains` over `refs/heads` and `refs/pull`. A PR SHA is still on origin per `ls-remote`, so D7's evidence rule holds. Probe: drop the refspec → the PR-only-SHA submit test goes red. **Ruled R-M2-7: implemented as a separate PR, a prerequisite of WP-A** |
| **Q-M2-2** | **`gate` when the newest Gate is VOID, ERROR, CANCELLED or EMPTY** (F17) | VOID/ERROR/CANCELLED never answer (listed `unjudged`); EMPTY answers EMPTY, exit 3, and counts as a distinct verdict for CONFLICT |
| **Q-M2-3** | **Superseded `keep` Gates** (F7, F18). R-M2-5 closed the scope among non-superseded Gates. Still open: a superseded `keep` Gate ran to the end at that SHA, and its verdict is evidence about it (supersede is about the ref moving). Leave it out entirely, or count it? | Leave it out, per R5's letter |
| **Q-M2-4** | **A running re-check over a single verdict:** answer RUNNING (spec "latest") with `previous=`, or the existing verdict with `running=`? | RUNNING + `previous=` |
| **Q-M2-6** | **`diff` exit codes** (spec §8.1 has none): 0 clean, 1 newly failing or added failing, 8 no base / conflicting base / pruned, 9 not terminal | As stated |
| **Q-M2-7** | **`gate` SHA prefixes** (7–40 hex, ambiguity → 64) and `--timeout` on `gate --wait` | Yes, both |
| **Q-M2-8** | **`diff` folding:** key `(class, name)`; failing if any row fails; failing↔skipped is `skip-changed`, not a fix | As stated |
| **Q-M2-9** | **Empty selectors = all jobs** (F4): the first `subscribe` narrows an all-jobs watch to one job, and removing the last selector widens it back. Keep (spec), or add an explicit `all` selector and make empty mean nothing? | Keep, and print the resulting scope on every reply |
| **Q-M2-10** | **KNOWN on old records:** records keep the registry as of their finish; no live overlay on `result` | Yes (R3) |
| **Q-M2-11** | **`/known` routes:** `POST /known/{id}/expire` (the refusal precedent) instead of spec §7's `DELETE` (F19) | Yes; amend spec §7 |
| **Q-M2-15** | **Rerun outcomes beyond PASSED/FAILED:** `NOT-RUN(<reason>)` for VOID, ERROR, EMPTY, timeout, refused, cancelled | Yes |
| **Q-M2-20** | **Supersede by `recipe_kind`** (F6) differs from spec §2.4's `(R, recipe)` for module-test | Report only; fix outside M2 unless you want it here. **Ruled R-M2-8: fixed outside M2** |
| **Q-M2-22** | **Exit code for CONFLICT** (R-M2-5 adds an answer spec §8.1 has no code for) | A new code, 11; amend spec §8.1 |
| **Q-M2-23** | **CONFLICT is permanent** (§1.1): results are never deleted, so a SHA that once flipped answers CONFLICT for good. Intended, or should someone be able to discount one job (a recorded, attributed ruling)? | Intended for M2; the way out is a new SHA. **Not accepted: R-M2-10 (`judge`) ruled instead** |
| **Q-M2-24** | **Rerun target classes:** from the parent record's KNOWN marks (fixed, dedup-stable), or from the registry at request time; and a `--class C` option to choose a subset? | Parent record's marks; no `--class` in M2 |
| **Q-M2-25** | **Rerun link and timeout:** the parent is linked only on the read side (`reruns` on `JobView`/`GateView`, `rerun=` on the lines), a deviation from spec §5.3/§9 forced by write-once records; and is the 30 min `RERUN` bound enough for an `-am install` of `aether/ember`? | Accept the read-side link; measure the bound on the first ember rerun and raise it if needed |
| **Q-M2-26** | **`diff --base` on a CONFLICT SHA:** refuse (exit 8), or diff against the newest judged job? | Refuse, since R-M2-5 forbids presenting the latest |
| **Q-M2-27** | **FLAKY on explicit reruns:** should an explicit rerun whose classes all pass carry `FLAKY`? (revision 2) | No; M2 records the outcomes only |

</details>

---

## 12. Deferred to M3 (and later)

- **`Patch` and `Bundle` sources** (R-M2-2; revisit in M3; verifiers use their own clones). Notes kept for that design:
  - **F12:** `SourceDto.Bundle/Patch` parse to `REFUSED(unsupported)`; `jobs.unpushed` exists and is never set; spec
    §10's `blobs` table does not exist.
  - **F13:** request bodies are capped server-wide at 1 MiB (`Controller.MAX_CONTENT_LENGTH`) and frames at 16 MiB, so
    an inline patch is bounded by the former. A 256 MiB bundle needs a per-route body cap (rc4 support **[U]**) and a
    fourth `worker-launch` verb.
  - **F14:** `PrepareJob(job, sha, originUrl)` fetches from origin, so a patch must ride the frame and a bundle needs
    its own channel to the worker.
  - **Q-M2-16, upload caps:** Patch inline ≤ 1 MiB, or the brief's 16 MiB with the cap raised; Bundle 256 MiB only
    through a per-route cap. Revision 1's guess: keep 1 MiB for Patch; Bundle waits for a per-route cap.
  - **Q-M2-17, a `worker-launch blob <sha256>` verb** (stdin, sha-checked, capped). It widens the forced command and
    needs the approval Q-M1-2 needed. Guess: approve, confined to `~/.temper/blobs`.
  - **Q-M2-18, a Patch job's `source_sha` = `baseSha`:** it then shows in `jobs?sha=` and matches `sha:` selectors.
    Every "verdict of this commit" reader filters `unpushed` (`gate` already does, §1.1).
  - **Q-M2-21, Bundle `sha` optional** when the bundle has one head (spec §2.1 makes it mandatory). Guess: yes.
  - Revision 1's §6 sketch (apply check against the mirror through a temporary index, the worker apply, blob retention)
    is in this file's history at commit 244f58b.
- **The ledger** (R-M2-3): a separate later item. No data is lost meanwhile: it is a view over `testcases`, `units` and
  `results`, all written already, and it can compute back as far as testcase retention reaches (90 d, Q-M1-11).
  Rerun outcomes are identifiable by `recipe_kind = 'rerun'` and `rerun_of`.
- **Automatic reruns** (R-M2-4): if they come, the maintainer's marked guess is that the shape stays: both outcomes plus
  `FLAKY`; M2's rerun record already carries both outcomes (§5.3). The V1 `units.rerun`/`testcases.rerun` columns (F9) would then be the in-job alternative to weigh
  against linked jobs.
- **§12.1 sizing and M4** (R-M2-6): after M2; M4 is not pulled ahead.

---

## 13. maintainer rulings (2026-10-18/19, s39 to s43)

| # | Ruling | Source | Replaces |
|---|---|---|---|
| **R-M2-1** | Order: (a) gate query, (c) subscribe/unsubscribe, (b) diff, (d) known registry, (e) rerun | maintainer, 2026-10-18 (s39) | the brief's proposed order a–f (§8) |
| **R-M2-2** | `Patch`/`Bundle` are **cut from M2**; verifiers use their own clones; revisit in M3. Mentioned in "Deferred to M3" only (§12) | maintainer, 2026-10-18 (s39) | Q-M2-16, Q-M2-17, Q-M2-18, Q-M2-21; revision 1's WP-F |
| **R-M2-3** | The ledger leaves M2; M2 exits without it; it is a separate later item | maintainer, 2026-10-18 (s39) | Q-M2-1 |
| **R-M2-4** | Rerun is **explicit only** (`temper rerun <job>`): a new job, submitted by a client against a FAILED Gate or ModuleTest with ≤ 3 failing classes. It never delays the original's `finished`. The rerun's record holds the original verdict plus the rerun outcome. (The s39 relay also said "FLAKY derived when both are read"; R-M2-9/R-M2-10 later settled "no FLAKY in M2".) maintainer's marked guess: if it becomes automatic later, both outcomes plus FLAKY is the shape | maintainer, 2026-10-18 (s39) | Q-M2-14; makes Q-M2-12 (workspace) and Q-M2-13 (delay) moot, since a rerun is its own job with its own checkout |
| **R-M2-5** | More than one non-superseded judged Gate at one SHA disagreeing → **CONFLICT**, never the latest | maintainer, 2026-10-18 (s39) | Q-M2-5 (whose guess, "keep the latest as the answer", is overruled) and the non-superseded part of Q-M2-3; opens Q-M2-22, Q-M2-23, Q-M2-26 |
| **R-M2-6** | The §12.1 sizing report comes after M2; M4 is not pulled ahead (cloud hosts busy with rc5) | maintainer, 2026-10-18 (s39) | — |
| **R-M2-7** | Implement the `refs/pull/*/head` fetch and reachability. It is built now as a **separate PR** (branch `fix-pull-refs`); WP-A references it as a **prerequisite**, not WP-A work. Test: a PR-only SHA is submittable and gate-answerable (§1.6 `PullRefGateIT`) | maintainer, 2026-10-18 (s40) | Q-M2-19 |
| **R-M2-8** | The supersede key becomes `(ref, canonical recipe incl. params)`. It is fixed now as a **separate M1-defect PR** (branch `fix-supersede-key`), not M2 work (F6) | maintainer, 2026-10-18 (s40) | Q-M2-20 |
| **R-M2-9** | The marked guesses are accepted, as written in §11's record table, for Q-M2-2, 3, 4, 6, 7, 8, 9, 10, 11, 15, 22, 24, 25, 26 and 27. In particular: CONFLICT exits 11; superseded `keep` Gates are left out; `POST /known/{id}/expire`; the rerun link is read-side only; no FLAKY on explicit reruns | maintainer, 2026-10-18 (s40) | Q-M2-2, 3, 4, 6, 7, 8, 9, 10, 11, 15, 22, 24, 25, 26, 27 |
| **R-M2-10** | *(The maintainer labelled it "R-M2-7 (amended)"; renumbered because R-M2-7 is Q-19's.)* **`judge`**: the temper build's git commit, filtered into the jar at build time and recorded on every job; a dirty build is refused at deploy. The gate answer and CONFLICT are computed **only among the Gates of the newest judge** at that SHA; older-judge Gates are printed as `older=` and stay in history. Dedup is unchanged (`project.version`); a newer judge's answer for a SHA takes `--fresh`. Rerun option (i): a rerun never changes the gate answer; Q-M2-27 stands (no FLAKY). **History:** the maintainer first ruled "CONFLICT only among same-controller-version Gates; a newer version's Gate supersedes older ones for the answer". The team lead pushed back that `${project.version}` is `0.1.0-SNAPSHOT` for every deploy and no job row stores a version (F8′), and proposed the `judge` column. The maintainer accepted. Mechanics: §1.7. *Amended by owner decision D-6 A1 (2026-10-24): dedup keys on the whole build version, judge included, so a newer judge's answer needs no `--fresh`* | maintainer, 2026-10-18 (s40) | Q-M2-23; corrects revision 1–3's false F8 (now F8′) |
| **R-M2-11** | Q-M2-28: (a) newest judge by candidate id, (c) `unjudged` dev builds refused only at deploy, and (d) no handshake field are accepted as guessed. (b) is **changed**: a Gate judged across a deploy is marked `judge-changed=`, shown in history, and **excluded** from the answer and the CONFLICT set; the way out is `--fresh`. *Main-thread refinement, a marked guess sent to the maintainer:* "across a deploy" means grant-time judge ≠ finishing judge, not submit-time. The grant-time judge is `units.granted_judge`, written by the existing `GRANT` statement (§1.7) | maintainer, 2026-10-18 (s41) | Q-M2-28 |
| **R-M2-12** | Subscribe semantics. **Skip-ack:** a poll that scans to S and delivers nothing advances the cursor to S, computed against the selector set it scanned with; a selector-version mismatch means no advance. `subscribe` with `sha:`/`ref:` prints the current state of every job it matches (bounded), as `job:` does. A selector added later catches only events after the cursor. `gap` fires only for events deleted before any poll scanned them. SHA hex is case-insensitive and normalised to lowercase. Only `subscribe` checks job existence; `unsubscribe` is idempotent. Mechanics: §2.1–§2.3 (`selectors_version`, `skipAck`). **Follow-up (s42):** the "+K more" line of a truncated reply names the command that lists the rest, `temper jobs (--sha S \| --ref R) [--json]` (#26: read-only, newest first, paged) | maintainer, 2026-10-19 (s42) | Q-M2-29 (raised on the main thread); replaces revision 1–4's "no new mechanism" delivery rule |
| **R-M2-13** | `diff` exits: skipped → failed is **newly failing** (exit 1); failed → skipped stays `skip-changed` (exit 0); a VOID/ERROR/CANCELLED side, A or B, is **refused** with exit 8, `side not judged: <verdict>`; an **EMPTY B** is reported and exits 1. Mechanics: §3.1, §3.2, §3.4 | maintainer, 2026-10-19 (s43) | Q-M2-30 (raised on the main thread); overrules §3.1's earlier guess that a VOID/ERROR/CANCELLED side is diffed |
| **R-M2-13 amendment** | A **REFUSED** side is also "not judged" (exit 8). **EMPTY B** means 0 testcase rows whatever the verdict (the parenthesis wins over the text): a PASSED B with 0 rows exits 1. `diff --base` makes the base selection and both side reads in **one** `REPEATABLE READ, READ ONLY` transaction, pinned by a test (§3.2) | maintainer, 2026-10-19 (s44) | PR #27's contradictions C1–C3 (marked guesses 1, 2 and 8) |
| **R-M2-14** | WP-D (#28): the finishing transaction stays READ COMMITTED; KNOWN is the entries active at the `known` read, one statement inside the completing transaction after the job lock, and a test pins lock-then-read (§4.3, §6). §4.2's CHECK is `(#.+)?` plus a length bound. The digest's Finished view carries the KNOWN annotations exactly as the event line does (Q-M2-31). Accepted guesses: a case entry beats a class entry; KNOWN follows the evaluation's own annotations; the event line carries only the KNOWN annotations; VOID/ERROR/CANCELLED records are marked; `createdBy` falls back to TEMPER_OWNER/BB_OWNER, else exit 64; old events read as no KNOWN | maintainer, 2026-10-19 (s45) | PR #28's guesses and contradictions; Q-M2-31 |
| **R-M2-15** | An entry that can affect a verdict is never invisible. A `known` row the domain cannot read (only manual SQL can make one): Finishing's active read **skips** it with a WARN naming the id, so an unreadable entry never masks a FAILED; `known list` **shows** it, marked UNREADABLE with its id and reason, so the operator can find it and expire it | maintainer, 2026-10-19 (s46) | PR #28's list-skips / active-uses divergence |
| **R-M2-16** | WP-E: §5.2's one unit per class is replaced by **ONE `rerun` unit** for the 1–3 target classes (`-pl <modules> -am install -DskipTests`, then test/verify with fully qualified, comma-separated `-Dtest`/`-Dit.test` and `-Dsurefire.failIfNoSpecifiedTests=false`/`-Dit.failIfNoSpecifiedTests=false`, built so that neither plugin runs its whole suite). Reason: a job's units share one workspace and report collection is by mtime, so per-class units collect each other's reports, and a worker change is out of M2 (§9). Per-class outcome from the class's own rows (nested included): PASSED ≥ 1 row none failing; FAILED any failing row; 0 rows → NOT-RUN(empty) at rc 0, NOT-RUN(rc) at a non-zero or unknown rc, never PASSED; a VOID/ERROR/timeout/refused/cancelled unit → every class NOT-RUN(<reason>). Rows of non-target classes are ignored and counted (`extra-rows=N`). Classes of one module share a surefire fork, so the outcome text says `co-run in one fork with: <classes>`. Tests: a 2-module, 2-class rerun gives PASSED per class (not "No tests matching"); a 0-row target is NOT-RUN(empty). The 30 min RERUN bound is kept (risk noted, Q-M2-25). Per-step report collection is deferred to M3 (§12) | maintainer, 2026-10-19 (s47) | WP-E RerunIT contradiction with §5.2 |
| **R-M2-16 amendment** | (a) A row counts only for its **most specific** target: with Outer and Outer$Inner both targets, Outer$Inner's rows judge only Outer$Inner, and Outer is judged on its own rows plus nested classes that are not targets. (b) Recorded limits: rows carry binary names (a top-level `My$Class` is attributed to target `My`) and no module (one FQCN in two modules is judged on the same rows). (c) `-Dmaven.test.failure.ignore=true` in the rerun step is accepted, pinned by a docker case where a target's fork dies (System.exit/halt): that target is NOT-RUN, never PASSED; and a compile failure gives NOT-RUN(rc) for every target. Host tier and Q-M2-25 timing wait for the owner's window | maintainer, 2026-10-19 (s48) | PR #29: double targets, the a5f4283 verifier's limits, the failure-ignore flag |
