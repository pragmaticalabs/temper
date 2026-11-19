# bb: build once, run many; incremental from diff (design note, s53 GA trial)
**Goal:** cut the build minutes per fix. Today every re-run of one test is a full `bb-build` maven pass.
**Home:** ops/bb, because the queue, cap, port group, memory floor and verdict-from-exit-status already live there.
temper controller jobs are unchanged. New code goes in two places: `bb-test` (bash) and `bb-modules.py`, which reuses
bb-portgroup.py's pom walk and -am/-amd closure. bb-build gains `--since`, and nothing else changes in it.
## 1. Incremental from diff: `bb-build --since <base> <checkout> [goals]`
- Changed paths = `git diff --name-only <base>...HEAD` plus a dirty tree (`git status --porcelain`). Each path maps to its
  nearest pom.xml module. Module set = changed modules, `-am` (built from source, never a stale .m2-local jar) and `-amd`.
- A change to the root pom, .mvn/ or a parent pom, an unmapped path or an unparsable pom runs the full reactor (fail toward more).
- The chosen `-pl` list is printed and written to the ticket (`since=<base> modules=<n>`). The dedup key is the expanded args.
## 2. Build once, run many: `bb-test <checkout> <module> <Class[#method]>... [--repeat N]`
- Precondition: a green bb-build at the current HEAD wrote `.bb/cp/<module>.cp`, from `dependency:build-classpath`, with
  reactor modules resolved to their `target/classes` dirs, plus `.bb/cp/manifest` (head SHA, per-module `git rev-parse
  HEAD:<module>` tree hash, sha256 of every reactor jar or classes dir on the classpath).
- The launch is one queue ticket (cap, port group, netns slot and memory floor all as now). It runs JUnit Platform Console
  Launcher on `test-classes:classes:cp`, with `--reports-dir target/surefire-reports`, so testcase counting is unchanged.
  It passes `-Daether.requireDocker=true`. Verdict = the java exit status. bb-test tickets are never deduped.
- Dependency: junit-platform-console-standalone is NOT on bigboy today. It is pinned in the seed by version and sha256.
## 3. Stale-jar guard (runs before every bb-test launch; refuse = rc 12, nothing queued, the reason names the module)
- A content check, not mtimes: git checkout resets mtimes, so "jar older than sources" can pass on stale content.
- Refuse when: (a) HEAD differs from manifest.head; (b) any reactor module on the classpath has a tree hash that differs
  from the manifest, or has dirty files (`git status --porcelain -- <module>`); (c) a recorded jar or classes digest no
  longer matches; (d) the classpath holds a reactor artifact from outside this checkout (an .m2-local SNAPSHOT jar).
- `BB_STALE_OK=1` is refused outright. The only way past the guard is a rebuild.
## 4. Loaded mode: `BB_LOAD=cpu:<n> | mates:<k>` (bb-test only; the value goes on the ticket as `load=`)
- `cpu:<n>`: the launch runs under `systemd-run --user --scope -p CPUQuota=<n*100>%`. The ticket counts as 1 reactor.
- `mates:<k>`: k concurrent copies of the same selection, each in its own netns slot. The ticket counts as k reactors
  against the cap. Copy i writes reports to `target/surefire-reports-mate<i>`. The verdict is red if any copy is red.
- This is for flake reproduction (#flakes-first). A loaded run is never a timing measurement.
## Positive controls (each must go RED; each is a test in BbTestStaleGuardIT)
1. A source edit in module A after the build: refused under (b), naming A. 2. A new commit: refused under (a).
3. A.jar replaced by the same module built at HEAD~1: refused under (c). 4. The cp file is edited to point at
   `.m2-local/.../A-SNAPSHOT.jar`: refused under (d). 5. Control: an untouched tree launches, and its report count equals
   the same selection run through `bb-build -Dtest=`. Mutation: delete each check and the matching test goes red.
- Incremental: a leaf change selects only the leaf plus its dependents; a root pom change runs the full reactor; a change
  in a mid module selects its upstream modules (-am) and its dependents (-amd). Each case has an expected `-pl` fixture.
**Open:** where the console-launcher jar comes from (seed vs a pom plugin dep); whether forge failsafe ITs run under the
launcher unchanged (they use failsafe system properties); `--user` scopes need lingering enabled on the hosts.
