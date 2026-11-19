# bb: build once, run many; incremental from diff (design note, s53 GA trial; CTO ACCEPT + items 1-5 folded in)
**Goal:** cut the build minutes per fix. Today every re-run of one test is a full `bb-build` maven pass.
**Home:** ops/bb, because the queue, cap, port group, memory floor and verdict-from-exit-status already live there.
temper controller jobs are unchanged. New code: `bb-test` (bash) and `bb-modules.py`, which reuses bb-portgroup.py's pom
walk and closures. bb-build gains `--since` and the manifest write (§2). Failsafe ITs stay on bb-build until parity is shown.
## 1. Incremental from diff: `bb-build --since <base> <checkout> [goals]`
- Changed paths = `git diff --name-only <base>...HEAD` plus the dirty tree (tracked + untracked, not ignored). Each path
  maps to its nearest pom.xml module. With C = changed modules: S = am(C ∪ amd(C)), passed as an explicit `-pl S` with no
  -am/-amd flags. The upstream closure covers the WHOLE set, so a dependent's other upstreams are also built from source,
  never resolved from .m2-local (the F4 stale-jar trap).
- A change to the root pom, .mvn/ or a parent pom, an unmapped path or an unparsable pom runs the full reactor (fail toward more).
- The `-pl S` list is printed and written to the ticket (`since=<base> modules=<n>`). The dedup key is the expanded args.
## 2. Build once, run many: `bb-test <checkout> <module> <Class[#method]>... [--repeat N]`
- Every green bb-build writes `.bb/cp/<module>.cp` per built module, from `dependency:build-classpath`, with reactor
  modules resolved to `target/classes`. It also writes `.bb/cp/manifest`: head SHA, plus per module its working-tree digest
  and the digest of each reactor jar or classes dir on its classpath. Digest = sha256 over the sorted list of
  JSON `[relative path, mode (exec bit), sha256(file)]` lines (a tab or newline in a name cannot collide; a non-UTF-8 name is refused, rc 12). The working-tree digest covers the module's tracked and untracked non-ignored files. A reactor-root digest (`root` line) covers every file outside the module directories plus every pom.xml (root pom, `.mvn/`, parent poms). The hook is bounded (`BB_CP_RECORD_TIMEOUT`, default 300 s; on timeout no record is left and the rc is unchanged); the classpath files are moved from `target/bb-cp.txt` into `.bb/cp/`.
- Surefire config comes from the module's effective pom, read at build time (`help:effective-pom`) and stored in
  `.bb/cp/<module>.sf`. Supported and reproduced: argLine (with @{…} late replacement resolved), systemPropertyVariables,
  groups/excludedGroups (as tags), configurationParameters, and junit-platform.properties (on test-classes as now).
  forkCount=1 with reuseForks=true is the launcher's own model. Anything else is REFUSED (rc 13, naming the module and the
  key): another forkCount, reuseForks=false, a provider other than junit-platform, an unknown surefire key, javaagent paths
  outside the seed.
- The launch is one queue ticket (cap, port group, netns slot and memory floor all as now). It runs the JUnit Platform
  Console Launcher (pinned version and sha256, from the seed) on `test-classes:classes:cp` with `-Daether.requireDocker=true` unless `BB_REQUIRE_DOCKER=0` is given at enqueue (the same explicit override as bb-build, #31, recorded in the ticket as `requireDocker=0`).
  Verdict = the java exit status. bb-test tickets are never deduped.
- Reports: the launcher's legacy XML (`--reports-dir`, format pinned to that launcher version) goes to
  `target/bb-test-reports/<ticket>/`, never into surefire-reports, so earlier bb-build reports are not double-counted.
  The testcase counter is passed that dir explicitly.
## 3. Stale-jar guard (runs before every bb-test launch; refuse = rc 12, nothing queued, the reason names the module)
- A content check, not mtimes (CTO-accepted): git checkout resets mtimes, so an mtime check can pass on stale content.
- Refuse when: (a) HEAD differs from manifest.head; (b) a module on the classpath, or the reactor root, has a working-tree digest that differs
  from the manifest; (c) a recorded jar or classes-dir digest no longer matches; (d) the classpath holds a reactor artifact
  from outside this checkout (an .m2-local SNAPSHOT jar). `BB_STALE_OK=1` is refused. The only way past is a rebuild.
- Mutation flow: bb-mutate applies the mutant, which changes the module's working-tree digest, so a bare bb-test refuses
  it under (b). The flow is `bb-build --since HEAD` (rebuilds the mutated module plus S, and rewrites the manifest with the
  mutant's digest), then bb-test. Reverting the mutant makes the digest differ again, so a rebuild is needed before any
  further green run.
## 4. Loaded mode: `BB_LOAD=cpu:<n> | mates:<k>` (bb-test only; the value goes on the ticket as `load=`)
- `cpu:<n>`: the launch runs under `systemd-run --user --scope -p CPUQuota=<n*100>%`. The ticket counts as 1 reactor.
  Lingering must be enabled on each host (CTO: yes).
- `mates:<k>`: k concurrent copies of the same selection, each in its own netns slot. The ticket counts as k reactors
  against the cap. Copy i writes reports to `…/<ticket>/mate<i>/`. The verdict is red if any copy is red.
- This is for flake reproduction. A loaded run is never a timing measurement.
## Positive controls (each must go RED when its check is removed: mutation-probe every one)
1. A source edit in module A after the build: refused under (b), naming A. 2. A new commit: refused under (a).
3. A.jar replaced by the same module built at HEAD~1: refused under (c). 4. The cp file is edited to point at
   `.m2-local/.../A-SNAPSHOT.jar`: refused under (d). 5. Parity: on an untouched tree, bb-test and `bb-build -Dtest=` give
   the same per-testcase outcome set (class, name, pass/fail/error/skip), not just the same count, for modules that use
   argLine, systemPropertyVariables and tags. 6. Config refusal: a fixture module with forkCount=2 is refused, rc 13.
7. Counter: a launcher-produced fixture with known pass/fail/error/skip counts is counted exactly. Pointing the counter
   at an empty dir, or at a surefire dir holding a duplicate, makes that control go red.
8. Mutation: a mutant plus bare bb-test is refused (b); a mutant plus `--since HEAD` rebuild plus bb-test runs, and the
   killing test fails.
9. Incremental: a leaf change selects the leaf plus its dependents. For a dependent D of the changed module M, where D
   also has another upstream U, S contains U. A root pom change runs the full reactor. Each case has an expected `-pl` fixture.
