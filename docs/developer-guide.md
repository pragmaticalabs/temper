# temper developer guide

How to build, test and change temper. Architecture is in `docs/architecture.md`; the contract is `docs/spec.md`; the
M1 work-package design and its rulings are `docs/m1-design.md`.

## 1. Repository layout

| Path | What it holds |
|---|---|
| `pom.xml` | Reactor parent (`org.pragmatica.temper:temper-parent:0.1.0-SNAPSHOT`): Java 25, dependency and plugin versions, the `jbct-check` execution, the `acceptance` profile. It deliberately does not inherit pragmatica's parent |
| `temper-domain/` | Pure rules (no I/O), plus the two parsing boundaries `domain/manifest/read` and `domain/mvn/read` |
| `temper-protocol/` | Frames, messages, HTTP DTOs, the JSON mapper, the process runner, the surefire XML parser |
| `temper-store/` | `store/api` (the port) and `store/pg` (PostgreSQL); migrations in `src/main/resources/db/migration/V<n>__*.sql` |
| `temper-controller/` | The controller; `controller.toml.example` is the annotated config |
| `temper-worker/` | The worker; `src/main/resources/runner/` holds the runner image's `Dockerfile` and `RunSteps.java` |
| `temper-cli/` | The `temper` command |
| `temper-dist/` | Gathers the three deployable jars of one build; `DistJarsIT` checks their versions, `DeployJarsIT` dry-runs `ops/deploy-jars.sh`; `DevPreflightIT`, `WtNewIT`, `WtPruneIT`, `DevHooksIT` test `ops/dev/` |
| `temper-acceptance/` | A11/A12/T1/T2 and sizing tooling; only in the `acceptance` profile (`temper-acceptance/README.md`) |
| `ops/` | Host-side scripts: `worker-launch` (the forced ssh command), `temper` (CLI wrapper), `build-dist.sh` (the root build with its judge, m2-design §1.7), `deploy-jars.sh` (refuses jars without one clean judge), `pg-up.sh`, `seed-host.sh`, the systemd unit; `dev/`, the worktree tools and git hooks (§9) |
| `docs/` | Spec and designs |
| `jbct.toml` | The JBCT lint layering for every temper package |
| `.mvn/maven.config` | `-Dmaven.repo.local=.m2-local` |
| `.github/workflows/ci.yml` | CI (§8) |

## 2. The per-tree Maven repository

`.mvn/maven.config` is tracked and holds `-Dmaven.repo.local=.m2-local`, so each working tree resolves and installs
into its own `.m2-local/` (gitignored), never into `~/.m2`. Do not change or remove it.

`ops/dev/wt-new` (§9) makes a tree with a seeded `.m2-local`. By hand: a fresh tree's `.m2-local` is empty. pragmatica `1.0.0-rc4` (the libraries and `jbct-maven-plugin`) is on Maven Central
since 2026-10-18 (`.github/workflows/ci.yml`), so a build can resolve it from there; to build offline or faster, seed
the tree from a repository that holds rc4 before the first build:

```
cp -a ~/.m2/repository/. .m2-local/
find .m2-local -type f -links +1 | wc -l    # must print 0
ls .m2-local/org/pragmatica-lite/core/1.0.0-rc4/core-1.0.0-rc4.jar \
   .m2-local/org/pragmatica-lite/jbct-maven-plugin/1.0.0-rc4/jbct-maven-plugin-1.0.0-rc4.jar
touch .m2-local/.seed-complete              # only once both checks pass: wt-new seeds only from a marked .m2-local
```

**Copy, never hardlink (`cp -al`).** When Maven installs or overwrites a file that is a hard link, it writes the
shared inode, which changes the seed you copied from. The same rule is built into temper itself: the worker copies the
host seed into every workspace and refuses to run a unit if any file there has a link count above 1 (R-M2, A13;
`temper-worker/…/worker/workspace/Seeder.java`, `SeedVerifier.java`).

## 3. Building

Requirements: Java 25 (`maven.compiler.release` 25), Maven, and a Docker daemon for the Testcontainers tests.

```
mvn install                    # from the root: the whole default reactor, with tests and the jbct check
mvn install -Pacceptance       # also temper-acceptance (unit and docker tiers; the host tier stays excluded)
```

**Build from the root, without `-pl`, to verify a change.** `-pl X -am` builds `X` and the modules `X` depends on,
never the modules that depend on `X`, and a change breaks things in its dependents. A change to a `temper-domain` or
`temper-protocol` type is consumed by the store, controller, worker and CLI; only a root build compiles and tests
those. Use `-pl` to iterate, then run the root build before you call the change done. A removed or renamed symbol also
needs a repository-wide search, because `temper-acceptance` is outside the default reactor and only `-Pacceptance`
compiles it.

The deployable jars are the shaded ones: `temper-controller-<ver>-all.jar`, `temper-worker-<ver>-all.jar` and
`temper-cli-<ver>.jar` (the CLI's shaded jar replaces its main jar). `temper-dist/target/dist/` collects the three.
The thin controller and worker jars have no `Main-Class`. the deploy runbook covers deployment.

### 3.1 Build tooling

`ops/dev/build` runs one Maven build in a tree and judges it; use it instead of a bare `mvn` whenever the result is
evidence for someone else.

```
ops/dev/build [--name <log>] [--expect mod1,mod2] -- <maven args>
ops/dev/build --name wp3-install --expect temper-store -- install
```

**What it defends against, and what not.** The tooling closes careless and setup-level false greens: a flag, an
environment variable, a config or rc file, a profile, a pom property, a stale output, a skipped or never-run test, a
second build in the same tree. It does not defend against deliberate subversion of the toolchain or the tests: a
hand-written wrapper around the scripts, a patched Maven inside a listed Maven home, test code that edits its own
reports or system properties. Those are a review matter. The cheap tripwires below (provenance, the Maven-home
allowlist, the tree-state hash) make some of them visible, but completeness there is a non-goal.

Run it from the tree root. Before anything runs, `ops/dev/build-args` reads the command line, `MAVEN_ARGS` and
`.mvn/maven.config` the way Maven's parser does (`-D x`, `--define x`, `--define=x`, abbreviated long options, bundled
short flags such as `-BT4`, attached values such as `-plx`), and the `-D` properties of `MAVEN_OPTS`,
`JAVA_TOOL_OPTIONS` and `.mvn/jvm.config`. How Maven splits and unquotes them was established by running both Maven
versions in use (3.10.0 on macOS, 3.9.12 on bigboy) over the same 60 cases, not by reading their source; the two
agreed on every case, so build-args reads them one way:

- `.mvn/maven.config` holds one argument per line, spaces and all (`-Pquick -q` is `-P` with the value `quick -q`); a
  line starting with `#` is a comment (one with a space before the `#` fails Maven).
- `MAVEN_ARGS` and the command line are one argument list (the launcher passes `${MAVEN_ARGS} -B clean <args>`).
- In each list, and in maven.config separately, a word in double quotes loses them (`"-q"` is `-q`). A word opening
  with `"` but not closing it starts an argument that takes every following word, joined by spaces, up to the next
  word opening with `"`. An option's value loses one balanced pair of double quotes (`-P"quick"` is `-Pquick`), and an
  unbalanced `-D"x=1` is judged as both `"x` and `x` (Maven sets both). Single quotes are kept, and Maven then fails.
- `JAVA_TOOL_OPTIONS` is read with the JVM's quoting (`'...'` and `"..."` anywhere in a word). The launcher passes
  `MAVEN_OPTS` and `.mvn/jvm.config` (where `#` is not a comment) unquoted, so a quoted word there fails the JVM. It refuses (exit 2, naming the flag and where it came from) what makes a run
report less than it did, among them: `-q`, `-fn`, `-T`/`--threads` and `-b` (parallel builds interleave the log, so
per-module jbct counts cannot be attributed), `-l` (output elsewhere), `-s`/`-gs` (other settings, whose profiles can
set any property), and the properties `jbct.skip*`, `maven.test.failure.ignore*`, `surefire.skip*`, `skipITs*`,
`maven.main.skip*` and `maven.resources.skip*` (tests run against stale `target/classes`), `maven.compiler.failOnError*`,
`surefire.rerunFailingTestsCount*` and `failsafe.rerunFailingTestsCount*` (a test that fails, then passes on a rerun,
counts as passed). Refused as well: `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `MAVEN_DEBUG_OPTS` and `MAVEN_BASEDIR` when
non-empty (each adds options or another `.mvn/` this script cannot read; Maven runs under `env -u` of all four anyway,
and with `MAVEN_SKIP_RC=1`, so `~/.mavenrc` and `/etc/mavenrc` are not read), `-Dmaven.repo.local` naming anything but
the tree's own `<tree>/.m2-local` (by realpath, so a symlink to another repo is refused too), and in every channel
above a word that reads more options from a file (`@argfile`, `-XX:VMOptionsFile`, `-XX:Flags=`). It refuses a run
in which no channel sets `-Dmaven.repo.local` at all (Maven would resolve and install into `~/.m2`); the tracked
`.mvn/maven.config` sets it. It refuses an `mvn` on PATH whose realpath lies under no directory that
`ops/dev/maven-homes` lists (a data file, one `<directory> <host: how it was read>` per line; a script first on PATH can
add any flag unseen): on macOS `/opt/homebrew/Cellar/maven`, on bigboy `/home/builder/apache-maven-3.9.12` (no `mvn` on
a login shell's PATH there; `~/tools/maven` links to it). A new host adds its line after reading
`readlink -f $(command -v mvn)`. Homebrew's `bin/mvn` is a wrapper, `JAVA_HOME="${JAVA_HOME:-<default>}" exec
<home>/libexec/bin/mvn`: build-args follows such an `exec` to Maven's own launcher (the script passing
`-Dmaven.home=`), whose realpath must lie under a listed home too. Maven's home is that launcher's directory's parent,
as the launcher itself derives it; on both hosts this equals the `Maven home:` that `mvn -v` prints
(`/opt/homebrew/Cellar/maven/3.10.0/libexec`, `/home/builder/apache-maven-3.9.12`). It refuses a `java` that is not a
native executable (Mach-O or ELF by its first bytes, after realpath): a `#!` script there can add any flag. That java
is `$JAVA_HOME/bin/java`, else the wrapper's default `JAVA_HOME`, else the `java` on PATH, as the launcher picks it;
its realpath goes into the marker. On this Mac the Homebrew default (`/opt/homebrew/opt/openjdk`) does not exist, so a
run needs `JAVA_HOME` set (`/usr/libexec/java_home -v 25`), as `mvn` itself does. It refuses an environment in which
the `env.<name>` activation of a test-selecting profile holds: `env.<name>` (with any value) while the variable is set,
even empty; `!env.<name>` while it is unset; `env.<name>` with value `!<v>` while it is unset or not `<v>`. It refuses
an environment variable that a surefire/failsafe configuration reads as `${env.<name>}`, directly or through a property
it reads. It refuses a test-selecting reactor profile whose activation can hold with nothing in the run naming it:
`<file>`, `<os>`, `<jdk>`, `activeByDefault` true, a negated property (`!<name>`, or value `!<v>`), a property the JVM
or Maven defines in every run (`java.*`, `os.*`, `user.*`, `maven.*` and the like), or any other kind. It refuses such a
profile whether its condition holds on this host or not (fail-safe: whether a file exists or which JDK runs is setup,
not the run's arguments; temper's poms have none). The reactor poms read are the root's and every module that a
`<modules>` names, recursively through each aggregator and in every profile; build-report judges the same modules.

It judges the settings files Maven reads by default (`-s`/`-gs` are refused): `<Maven home>/conf/settings.xml`, and
`.m2/settings.xml` under the account's home, under `$HOME`, and under every `-Duser.home` a channel gives (Maven
reads one of them; all are judged). It never writes them. A settings profile that can be active (`activeByDefault`
true, any other activation condition, or named in `<activeProfiles>`) and sets a refused or test-selecting property
is refused; so is an `<activeProfile>` naming a test-selecting reactor profile. A settings profile that sets such a
property but cannot be active is a test selection when `-P` names it. Every other settings property is not judged (the
`activeByDefault` profile on this Mac sets only `gpg.*`). The marker records each file's sha256. `-Duser.home` itself
stays outside the allowlist (PARTIAL). The property lists
live in `ops/dev/build_rules.py`, shared with build-report, which applies them to what each test JVM recorded (below). These are the lists the script knows (its docstring has
them in full), not every way to bend Maven; what they miss is left to the report's evidence below. `-DskipTests` and
`-Dmaven.test.skip` are allowed and make the run COMPILE-ONLY.

It also names what selects a subset, which makes the run PARTIAL at best: modules (`-pl`, `-rf`, `-am`, `-amd`, `-N`,
`-f`) and tests (`-Dtest`, `-Dit.test`, `-Dgroups`, `-DexcludedGroups`, `-Dsurefire.failIfNoSpecifiedTests`,
`-Dfailsafe.failIfNoSpecifiedTests`, the surefire/failsafe includes/excludes files and JUnit engine filters,
`-Dacceptance.groups`/`-Dacceptance.excludedGroups`). It reads the reactor poms too: a `-D` of a property that a
surefire/failsafe configuration reads as `${name}` (`-Dworker.excludedGroups`, `-Dtemper.judge`), a `-D` that activates
a profile which configures surefire/failsafe or sets such a property (`-Dtemper.host` turns on `host-tier`), and a `-P`
of such a profile are test selections. So is a profile whose `<properties>` set a surefire/failsafe parameter directly:
any name on the test-selection list, `includes`, `excludes`, `skip*`, or a refused property. Every other `-D` and `-P` is PARTIAL too ("outside the allowlist"), unless
build-args lists it as harmless, with its reason: `-Dmaven.repo.local=.m2-local` (the tree's own repo, as the tracked
`.mvn/maven.config` sets it), a pom-read property at the value that every pom whose surefire/failsafe configuration
reads it declares as its effective value (its own `<properties>`, else the root pom's when that is its parent; the root
pom's configuration reaches every module), as a run without it, also for a name on the test-selection list
(`-Dacceptance.excludedGroups=host -Pacceptance`, the declared default),
`-DskipTests` and `-Dmaven.test.skip` (COMPILE-ONLY), and `-Pacceptance` (adds temper-acceptance, selects nothing else).
Options that are neither `-D` nor `-P` and not refused (`-B`, `-e`, `-U`, `-o`, ...) are not judged.

The run holds the tree's build lock, `.wp-logs/build.lock`, taken, extended and released by `ops/dev/build-lock`.
The contract, shared with tree preflight and prune:

```
.wp-logs/build.lock/pid      one integer per line: the wrapper's pid first, maven's second
.wp-logs/build.lock/lstart   one start time per line, same order as pid (`ps -o lstart=`, LC_ALL=C TZ=UTC)
.wp-logs/build.lock/info     start and args (optional)
.wp-logs/build.guard         flock(2) target for every take, add and release
```

The lock is held while ANY listed pid (a bare integer > 0) is alive by `kill(pid, 0)` (EPERM, another user's process,
counts as alive; `ps` is not asked, since it can fail), except a pid whose start time `ps` reports now and differs from
the one in `lstart`: that pid was reused by another process. A start time that is not known (no `lstart` line, an empty
or malformed one, or `ps` failing now) cannot show reuse, so the pid holds the lock: the reader fails closed, and the
writer records an empty line, never a made-up value, when `ps` cannot tell it. `BuildToolingIT` runs both readers
(`lib.sh`'s `lock_held` and `build-lock`) over one table of cases and requires the same verdict. So a `kill -9` of the wrapper leaves the lock held until Maven
ends; Maven's process records itself in the lock before it starts. A lock directory without a usable pid is a lock
still being written: held while the directory is younger than 10 s (mtime), stale after. A held lock refuses the run
(exit 3, naming the live pids); a stale one is taken over, and said so. Takeovers are serialised by the guard's flock,
which the kernel drops when its holder dies; the new lock is written complete (pid and lstart) in a unique directory
and renamed onto `build.lock`, which fails instead of nesting if a lock appeared meanwhile. Losing any race exits 3.
The wrapper removes the lock at the end only while its `pid` file still names the wrapper first. Maven runs in the
background; INT or TERM to the wrapper is forwarded to it before the lock is released.

The wrapper refuses (exit 2) a tree where git is told not to see changes to a tracked file (`git ls-files -v` shows an
assume-unchanged or skip-worktree entry), except `.mvn/maven.config`, skip-worktree by convention, whose content is
hashed into the tree state instead. Before Maven starts, it writes `.wp-logs/<name>.marker`: HEAD, a dirty flag (git
status, or a gitignored file under a module's `src/` or a pom), the tree state (a hash of `git status --porcelain`,
`git diff HEAD`, the untracked files' contents, those ignored sources and `.mvn/maven.config`, `.wp-logs` left out),
the args, its own pid and start time, the `mvn` it runs (realpath), `mvn -v`'s first line, `JAVA_HOME`, and the
selection. `clean` is always the first goal: the wrapper prepends it when the args do not start with it, because classes
compiled from another state of the sources sit in `target/`, outside the tree state. That costs a full compile of every
module on each run (the run takes as long as a build from scratch); an incremental build is not evidence. It logs `mvn -B clean <args>` to `.wp-logs/<name>.log`, runs
`ops/dev/build-report` while still holding the lock, and releases the lock after it. The report can be run on its own,
but its verdict is then RED on provenance: it requires every marker field, and that the lock's first entry is the
marker's wrapper (same pid and start) and that this wrapper is its live parent. The JSON records the provenance (the
wrapper's pid and start, the lock entry, the report's parent pid, the log's sha256) and the launcher (`mvn`,
`maven_version`, `java_home`):

```
ops/dev/build-report --tree <root> --marker <file> --log <file> [--expect ...] [--mvn-exit N]
```

The report reads, per module of the root pom (including profile modules): from the log, whether the run built it, its
reactor status, jbct's `Check results (checked N file(s))`, and each surefire `test` / failsafe `integration-test`
execution that ran and whether it said `Tests are skipped.`; from the module's pom, the test executions it declares, or
inherits from a root pom that is its parent, and their `reportsDirectory` (a module without `src/test`, such as the root
pom, is not judged by its executions); from `target/surefire-reports*` and `target/failsafe-reports*`, only the `TEST-*.xml` newer
than the marker, counting `<testcase>` elements (§5.2) and those with a failure, error or skipped child, per module and
per report directory (so the controller's `one-carrier` execution, §5.1, is checked on its own). It writes
`<name>.json` next to the log and prints at most 20 lines, with each module's skipped count and a total.

| Verdict | When | Exit |
|---|---|---|
| GREEN | mvn exit 0 and BUILD SUCCESS; every built module SUCCESS with no failure or error; each built module with `src/test`, and each test execution its pom declares, has at least one fresh testcase that was not skipped; jbct checked at least one file in each built module with `src/main/java`; every `--expect` module built and ran a non-skipped testcase; HEAD and the tree state unchanged since the marker; every default-reactor module built; no module or test selection | 0 |
| RED | a failure: mvn exit, a failed module, a test failure or error, a refused property in a test JVM's XML properties, a testcase rerun after failing (`<flakyFailure>`, `<flakyError>`, `<rerunFailure>`, `<rerunError>`), a marker field missing, provenance (the report not run under the marker's wrapper and lock), jbct examined nothing, an `--expect` module without a non-skipped testcase, HEAD or the tree changed during the build, fresh reports in a module the log did not build (another build wrote into the tree); and missing tests (0 fresh testcases, or all skipped, in a module or declared execution) unless the run selected tests | 1 |
| COMPILE-ONLY | no RED reason, but the run skipped tests (`-DskipTests`, `-Dmaven.test.skip`) | 1 |
| PARTIAL | no RED or COMPILE-ONLY reason, but only a subset ran: a module or test selection (also when only a test JVM's XML shows it), a `-D` or `-P` outside the allowlist, a skipped testcase or a test class that ran no testcase which `ops/dev/known-skips` does not list for this platform, a default-reactor module not built, a declared test execution that did not run (its phase not reached, e.g. `mvn test`) | 1 |

A test selection excuses missing tests, not failures: `-Dtest=X` with a failing X is RED. A module selection excuses
nothing in the modules it built. Exit 2 is invalid input (no pom, missing marker or log, a log older than the marker,
an `--expect` module the root pom does not name). Modules the run did not build are listed by name, so
`temper-acceptance (profile acceptance)` shows up whenever `-Pacceptance` was left out. Skipped tests are counted and
shown (`SKIPPED <n>`). A skipped testcase is a test that did not run: the run is PARTIAL ("N testcases skipped", by
class), so the Postgres ITs skipping without Docker (§5.1) can no longer pass as GREEN, unless the class is listed in
`ops/dev/known-skips` for this platform. So is a test class that ran no testcase at all: an assumption failing in
`@BeforeAll` (TaskApiIT and BuildIdentityIT through `Pg.freshStore()` without Docker) leaves a report without a
`<testcase>`. Each class is judged per reports directory (one class can run in two executions, as temper-controller's
StartFailureLivenessTest does in `default-test` and `one-carrier`), against the testcases with its classname there. A
log line `Tests run: N ... in <class>` with N > 0 and no such testcase is a class that ran nothing. Surefire counts an
outer class's own testcases into the line of one of its `@Nested` classes: PlannerTest's 34 show up as
`Tests run: 42 ... in PlannerTest$RerunUnits`, whose own testcases are 8, and PlannerTest logs 0. The XML attributes
each testcase to its true classname, and per top-level class the log and XML totals agree (all 254 classes of a full
install). So a `@Nested` class whose `@BeforeAll` assumption failed (ManifestOracleIT without Docker) logs either 0 or
the outer class's count, and has no testcase. A line with N = 0, or a report without `<testcase>`, is a class that ran
nothing too, unless testcases of its `<class>$...` inner classes ran in that directory: an outer `@Nested` class logs
`Tests run: 0` (RefusalsTest and 17 more in a full install).
`ops/dev/known-skips` is checked in; each line is `<module> <class>[#method] <Linux|Darwin|any> <reason>` (the module's
artifactId; the testcase's classname as the report writes it; optionally one testcase's name, without which the entry
covers every testcase of the class and the class running none; the platform as Python's `platform.system()` names it;
free text to the end of the line). A malformed line (a bare class without its module) is invalid input; an entry for a
module this reactor lacks matches nothing. It starts with one entry, `temper-worker JobGcTest#aDeleteFailingMidway...`
on Linux (that case needs macOS `uchg`); a full install on macOS skips nothing.

Each fresh report's `<properties>` hold the system properties of the test JVM that wrote it, whatever set them (a
launcher script, a wrapper `mvn` first on PATH, a profile): build-report judges them with the same lists. A refused
property there is RED, a test selection (`test`, `it.test`, `groups`, a pom-read property at another value than the
poms declare, a profile's activation property) is PARTIAL; any other property is not judged (the JVM has many). A name
the poms' own `systemPropertyVariables` or an `-D` in their `argLine` give the test JVM is not judged as a selection
there: its recorded value is interpolated (`temper.cli.jar=${project.basedir}/...` under `-Pacceptance`) and never
equals the pom's text. The command line setting it is still judged. A module or declared execution whose fresh
testcases are all skipped is RED.

Known limits, outside the threat model above. The profiles Maven activated are not in its default log; only
`help:active-profiles` (a second Maven run) or `-X` shows them, so a profile activated by a channel the lists miss is
judged only by its effects in the XML. Test code can rewrite its own system properties before surefire records them
(at the end of the class), so the XML judgement can be blinded from inside a test. The java check reads magic bytes:
a native wrapper binary in a JDK home passes it. The settings judgement reads property names and values as written:
a settings property whose value interpolates another, or a `-Dmaven.conf` pointing at other global settings, is not
followed. The quoting rules above are those of the two Maven versions run; a new Maven version is run over the
same cases (quoted, unterminated and `#`-led words in each channel) before it is listed in `ops/dev/maven-homes`. `BuildToolingIT` (temper-dist) tests the scripts against a fake `mvn`.

## 4. Code style: JBCT and the jbct-check gate

All code follows JBCT (Java Backend Coding Technology): values parsed through factories that return `Result`, I/O
behind interfaces that return `Promise`, pure domain logic, no exceptions for control flow. The root pom binds
`jbct-maven-plugin:check` (execution `jbct-check`) in every module; the goal's default phase is `verify`, so
`mvn test` does not run it and `mvn install` does. It runs the format check and the lint over `src/main/java`
(`jbct.includeTests=false`) and fails the build on errors. The plugin also has a `format` goal
(`mvn org.pragmatica-lite:jbct-maven-plugin:1.0.0-rc4:format`).

`jbct.toml` assigns every package to a layer:

| Layer | Packages (abridged) | Rule of thumb |
|---|---|---|
| domain | `domain.**`, and the fallback `org.pragmatica.temper.**` | pure: no JDK I/O imports, no adapter imports, no `lift(` |
| application | `store.api.**`, `controller.{cancel,flow,policy,result,signal,submit}.**` | use cases and policies over domain and store-port types; never a wire type |
| adapter | `protocol.**`, `cli.**`, `domain.manifest.read.**`, `domain.mvn.read.**`, `store.pg.**`, the controller's I/O packages, every worker package, `acceptance.**` | where I/O and `lift(...)` belong |
| bootstrap | `controller` (Main, Controller) | wiring only |

The fallback glob makes the check fail closed: a package listed nowhere is judged as **domain**, the strictest
layer. `JBCT-ARCH-02` (a textual `lift(` outside adapter code) is set to `error`. The header of `jbct.toml` lists what
the lint cannot see (a `ProcessBuilder`, a fully qualified `java.nio.file.Files`, a `Result::lift` method reference).

## 5. Tests

### 5.1 Tiers

| Tier | Where | Runs in `mvn install` | Needs |
|---|---|---|---|
| unit | every module, `*Test` (surefire) | yes | nothing |
| it-pg | `temper-store` and `temper-controller`, `*IT` run by surefire, tag `pg` | yes | Docker for `postgres:16` |
| worker ITs | `temper-worker`, `*Test` and `*IT` (surefire) | yes; tag `docker` needs a daemon | a local filesystem and git; Docker for `docker` |
| dist | `temper-dist`, failsafe (`DistJarsIT`, `DeployJarsIT`, `BuildToolingIT`) | yes | the jars of the same build |
| acceptance unit / docker | `temper-acceptance` | only with `-Pacceptance` | Docker, network for Maven Central |
| host | `temper-acceptance`, tag `host` | only with `-Pacceptance -Dtemper.host=<host>`, on the host | `temper-acceptance/README.md` |

- The parent's surefire excludes the JUnit tag `host` everywhere.
- **Without Docker, the Postgres ITs are skipped, not passed** (`Assumptions.assumeTrue(... isDockerAvailable())` in
  `temper-store/…/PgFixture.java`, `temper-controller/…/it/Pg.java`). A green build on a machine without Docker has not run them; read the skipped count.
- In `temper-worker`, `-Dworker.excludedGroups=host,docker` leaves out the tests that need a Docker daemon.
- `temper-controller` runs `DirectStartOrderingTest` and the `directStart*` cases of `StartFailureLivenessTest` in a
  second surefire execution (`one-carrier`) with `-Djdk.virtualThreadScheduler.parallelism=1`, reporting to
  `target/surefire-reports-one-carrier`. `default-test` excludes only `DirectStartOrderingTest`, so the `directStart*`
  cases run in both executions.

### 5.2 Counting tests

Count `<testcase>` elements in the XML reports, never the `tests=` attribute or surefire's "Tests run" summary line.
The attribute sum undercounts `@Nested` classes: on the S1 XML it is 177 against 293 `<testcase>` elements
(`docs/m1-design.md` §9.3). temper itself judges by `<testcase>` and keeps `tests=` only as `testsAttr`. For one
module:

```
find temper-domain/target/surefire-reports -name 'TEST-*.xml' -exec grep -o '<testcase ' {} + | wc -l
```

Include `target/failsafe-reports` for the dist and acceptance modules. For the controller, `target/surefire-reports-one-carrier`
holds `DirectStartOrderingTest` (run only there) plus a second run of the `directStart*` cases, which are already in
`target/surefire-reports`; count those cases once.

### 5.3 Making a test prove something

A test that passes against broken code proves nothing. For load-bearing behaviour, break it on purpose and watch the
test go red, then restore it. `docs/m1-design.md` §9 and `temper-acceptance/README.md` ("Mutation probes") list the
probes each criterion was checked with.

## 6. Adding a module

1. Create `temper-<name>/pom.xml` with `temper-parent` as its parent and add `<module>temper-<name></module>` to the
   root `<modules>` (or to the `acceptance` profile if it must stay out of the default reactor).
2. Use the versions managed in the root `<dependencyManagement>`; add any new one there.
3. Classify each new package in `jbct.toml`. An unlisted package is judged as domain, so I/O code fails the build until
   it is listed as adapter.
4. Decide where its `*IT` classes run: surefire's default includes do not match `*IT`; `temper-store`,
   `temper-controller` and `temper-worker` add `**/*IT.java` to their surefire includes, `temper-dist` and
   `temper-acceptance` use failsafe.
5. If the module ships a runnable jar and another module depends on it, attach the shaded jar as `-all`
   (`shadedArtifactAttached`, as `temper-controller`, `temper-worker` and `temper-acceptance` do), so the main jar keeps
   only the module's own classes and cannot shadow sibling modules on a test classpath.
6. Run the root build.

## 7. Commits

- One line, conventional prefix: `feat:`, `fix:`, `refactor:`, `chore:`, `docs:`, `test:`. No body, no trailers.
- Say in the line what a change governs when it matters, for example the property a test pins
  (`test: pin held ModuleTest bridged network beside held Gate host network`).

## 8. CI

`.github/workflows/ci.yml` runs on pushes to `main` and `m1` and on pull requests: checkout, Temurin Java 25, a cache
of `.m2-local` keyed by the poms, then `mvn -B -ntp install` from the root, the same command as a local build. On
failure it uploads `**/target/surefire-reports*/` (which includes the controller's `surefire-reports-one-carrier`) and
`**/target/failsafe-reports/`. `temper-acceptance` does not run
there.

**It is green.** temper builds against pragmatica `1.0.0-rc4`, which is on Maven Central since 2026-10-18, so a hosted
runner resolves it from there (first green run 39100157883, `.github/workflows/ci.yml`). The workflow is
deliberately not pointed at any other repository.

## 9. Worktrees and hooks

Make, check and remove working trees with `ops/dev/`, not by hand. Each tool states its exit codes in its header.

```
ops/dev/wt-new <name> [--base <ref>] [--branch <b> | --detach]   # ${TEMPER_WT_ROOT:-~/temper-wt}/<name>
ops/dev/preflight [<tree>]                                       # before a build: disk, docker, .m2-local, lock
ops/dev/wt-prune [--apply] [--include-dirty] [--keep <name>,...] # dry run unless --apply
ops/dev/land-pr <pr> --approved [--flake <class.method>]...      # merge a PR the owner approved, once green
```

- **`wt-new`** refuses a name or branch that exists and a volume with less than `TEMPER_MIN_FREE_GIB` (default 20) GiB
  free, fetches `origin`, adds the worktree (branch `<name>` from `origin/main` by default, `--no-track`, so a push
  names its remote branch), and seeds `.m2-local` with `cp -a` from `TEMPER_M2_SEED` or the most recently modified
  complete `.m2-local` of the existing worktrees whose build lock is not held. Complete means a directory, not a
  symlink, holding `.seed-complete`.
  Trees made before wt-new wrote that marker lack it, so name one of them in `TEMPER_M2_SEED`. A symlinked
  `TEMPER_M2_SEED` and a seed with a hard-linked file are refused (§2). The copy goes to `.m2-local.partial`. Only when
  it holds no hard-linked file does it become `.m2-local`, with `.seed-complete` written last, so a copy cut short is
  never taken for a seed. Any failure after the worktree exists, an interrupt included, removes the tree and the
  branch wt-new made, and exits 4. It creates `.wp-logs/`, adds `.wp-state.md` and `.wp-logs/` to `.git/info/exclude`,
  and sets `core.hooksPath` to `ops/dev/hooks`. The path is relative and the setting is shared by every worktree of the
  repository, so each tree runs the hooks of its own checkout, and a tree on a branch older than the hooks runs none.
- **`preflight`** prints one `ok:`/`FAILED:` line per check and exits 1 if any failed: free disk; docker (the CLI on
  `PATH` and `docker info` answering: `PgDumpIT` and the worker exec `docker`, so a socket alone is not enough;
  without the CLI the line says whether the socket of `DOCKER_HOST`, colima's or `/var/run/docker.sock` answers); the
  docker host Testcontainers will use (`tc.host`, else `DOCKER_HOST`, else `docker.host` in
  `~/.testcontainers.properties`, else a default socket), which must be the one `docker info` used (`DOCKER_HOST`, else
  the current docker context), or the ITs reach another daemon than the one checked. Unix socket paths are compared
  with their symlinks resolved, so a link to colima's socket is colima. With a tree: `.m2-local` a
  directory, not a symlink (`find` does not descend a symlinked root, so the hard-link check would pass it), without
  hard links; and `.wp-logs/build.lock` not held. The lock is a directory made by `mkdir`, and its `pid` file lists one
  pid per line (the build's, then maven's); a line that is not only digits, such as `host-1: 42`, is no pid. An
  optional `lstart` file records each pid's start time (`LC_ALL=C TZ=UTC ps -o lstart=`), line for line, and a pid
  whose process started at another time counts as dead, since the pid was reused. The lock is held while any listed
  pid is alive (`ps -p`), or, when it has no usable pid yet, while the directory is younger than 10 seconds, because
  its owner may still be writing the pid. A docker that is down fails here, before a build turns it into dozens of IT
  errors.
- **`wt-prune`** fetches `origin` and removes a worktree only when all of these hold: its HEAD is an ancestor of
  `origin/main`; no live build lock is held; it is clean, meaning no tracked change and no untracked file (ignored ones
  included) other than `.m2-local/`, `.wp-logs/` and the `target/` of a Maven module (a directory beside a `pom.xml`);
  it is idle, meaning no file outside `.m2-local/` was modified within `TEMPER_PRUNE_IDLE_HOURS` (default 2) hours,
  with `.wp-state.md`, `.wp-logs/` and module `target/` dirs counting as activity (a build run without a lock writes
  only there); it is not
  locked or named in `--keep`; and its branch has no open PR (`gh pr list --head`; without `gh`, or when `gh` fails,
  the answer is unknown and the tree is kept). A tree with no commit of its own is an ancestor of `origin/main` as soon
  as main moves on, so cleanliness and activity are what keep a tree in use. `--include-dirty` drops the cleanliness
  rule; activity still applies. Before removing it copies the untracked files, ignored ones included but not
  `.m2-local/`, `.m2-local.partial/` or module `target/` dirs, and the modified tracked files to `<root>/_archive/<name>/`, with `HEAD` and
  `tracked.diff`; a failed archive keeps the tree. Right before `git worktree remove` it checks again that no lock was
  taken, that the tree is still clean (unless `--include-dirty`) and that no file changed since the activity check. A
  clean tree is removed without `--force`, so git refuses one that turned dirty in the last instant; that relies on
  `.wp-logs/` being ignored, which wt-new sets up. It never deletes a branch. It prints a table of what it kept or
  removed, and why.
- **`land-pr`** does the mechanics of a merge the owner approved; it never decides one. Without `--approved` it
  refuses (exit 2) before reading anything. It refuses a PR that is not open, not based on `main`, a draft, from a
  fork, or whose branch on origin is not at the PR's head, and any commit of `origin/main..head` that breaks the
  one-line rule (§7; the same check as `pre-push`, `message_fault` in `ops/dev/lib.sh`). A head that does not contain
  `origin/main` gets `chore: merge main into <branch>`, made in a temporary detached worktree and pushed with a plain
  push, never forced; a conflict is aborted, leaves the branch as it was and names the files. Then it waits for CI on
  that exact SHA, reading its check runs and commit statuses, all pages (the repository has no branch protection, so
  nothing else holds this): green is every check named in `ops/dev/required-checks` reported, all completed, each
  `success`, or `neutral`/`skipped` for a check named in `--allow-neutral`. A required check not reported, or no check
  at all, counts as pending. It watches the SHA's running workflow run (`gh run watch`), else polls every 30 s, up to
  `--timeout` seconds in all (default 3600). On red it reruns the failed jobs once per SHA only
  when every failed check is an Actions job concluded `failure` whose `--log-failed` log shows at least one failed
  test and nothing else: each `<<< FAILURE!`/`<<< ERROR!` line parses as `[ERROR] <class>.<method>` (a parameterised
  `(<params>)[<n>]` dropped), each is listed whole in `ops/dev/known-flakes` (one per line with its issue) or given by
  `--flake`, and no `COMPILATION ERROR` or failed goal other than surefire's or failsafe's test failures shows. A
  cancelled job, a check with no job log, a failed commit status, or a red after the rerun (at once, while rerun jobs
  still run) stops it, printing the failing checks and tests. After CI it reads the PR again and refuses a head that
  moved, and fetches `origin/main` again: when main moved meanwhile it merges main in again and waits for CI on the new
  SHA, within the same timeout and at most 5 times (then exit 6). A required check is met only by a GitHub Actions
  check run of that name, never by a commit status or another app's check run. Rerunning means one `gh run rerun <run>
  --failed` per Actions run; a job counts as failed by flakes only when its failed-test lines match the `Tests run: ...,
  Failures: F, Errors: E` summaries and no `##[error]` comes from a step other than the one whose tests failed. Both
  config files take one entry per line; CR is stripped, blank and full-line `#` lines are ignored, and a line with
  trailing content (an inline comment) is refused with exit 3, naming file and line. The known limit: `main` can still
  move between the last fetch and `gh pr merge`; `--match-head-commit` pins the PR's head, not `main`, so the merge can
  join a head tested against a `main` one commit older. Only branch protection ("require branches to be up to date")
  closes that window, and this repository's plan has none. It merges with `gh pr merge <pr> --merge --subject "chore: merge <title without its
  prefix> (#<pr>)" --body "" --match-head-commit <sha>`, fetches and prints the merge commit, and fast-forwards the
  repository's main checkout when it is on `main` with no tracked change. One `ok:` line per step; exit codes 3
  refused, 4 conflict merging main, 5 CI red, 6 CI timed out, 7 a git or gh command failed.

The hooks:

- **`commit-msg`** accepts exactly one line `<feat|fix|refactor|chore|docs|test>[(<scope>)]: <subject>` (§7), with at
  most one `(<scope>)`. Any further non-comment line is refused, a `Co-Authored-By` or other trailer included. Git's
  default merge and revert messages fail it too, so give those `-m "chore: merge ..."`. It is an early warning only:
  `git commit -m "# ..."` stores a `#` line it took for a comment, as do `--cleanup=verbatim` and another
  `core.commentChar`.
- **`pre-push`** checks what git stored: the message of every pushed commit that no remote branch holds yet
  must be that one line and nothing else, with no carriage return. What the remote holds is asked of it
  (`git ls-remote --heads <remote>`), since a remote-tracking ref may be stale; when that fails, the local
  remote-tracking refs stand in, with a warning. Merge commits with a conforming line pass. A branch deletion pushes nothing and
  passes. Reword a refused commit with `git commit --amend -m` or `git rebase -i`.
- **`pre-commit`** runs only when `.java` files are staged: `mvn -B jbct:format-check` on the modules that hold them
  (`-Pacceptance`, so `temper-acceptance` resolves too). A module is one the root pom declares, profiles included. A
  file in none of them is not checked, and a fixture project with its own `pom.xml` inside a module is not a module:
  the module holding it is checked. An unformatted file fails the commit with `run mvn -B jbct:format`. The hook never
  rewrites a file or the index. On macOS an unset `JAVA_HOME` is taken from `/usr/libexec/java_home -v 25`; when mvn
  cannot start (no mvn, or the launcher's first line says there is no java) it refuses with one line, `pre-commit: cannot run mvn (JAVA_HOME unset or no
  java): <first line mvn printed>`. The check reads the working tree, so the hook names any staged file that also has
  unstaged changes.

`git commit --no-verify` skips both commit hooks; `pre-push` still checks the message. `git push --no-verify` skips
`pre-push`; do not use it.
