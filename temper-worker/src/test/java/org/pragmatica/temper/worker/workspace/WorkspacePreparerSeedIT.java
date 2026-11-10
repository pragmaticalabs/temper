package org.pragmatica.temper.worker.workspace;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.seed.SeedError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.WorkerPaths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// A13 (m0-design §7.3, §9): workspace preparation with the production [Seeder] on a real POSIX filesystem and a
/// real local git origin. Positive: every `.m2-local` file has link count 1. Negative: a hard-linking seeder (the
/// `cp -al` mistake) makes preparation fail with `seed-isolation`, before any unit could start.
class WorkspacePreparerSeedIT {
    @TempDir
    Path root;

    private WorkerPaths paths;
    private Git git;
    private Sha sha;
    private String origin;
    private final JobId job = ok(JobId.jobId(31));

    @BeforeEach
    void setUp() throws Exception {
        paths = WorkerPaths.workerPaths(root.resolve(".temper"));
        git = Git.git(ProcessRunner.processRunner(),
                      Map.of("PATH", System.getenv("PATH"), "HOME", root.toString()),
                      TimeSpan.timeSpan(60).seconds());

        var repo = Files.createDirectories(root.resolve("origin"));

        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        exec(repo, "git", "init", "-q");
        exec(repo, "git", "add", "pom.xml");
        exec(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        sha = ok(Sha.sha(exec(repo, "git", "rev-parse", "HEAD")));
        origin = repo.toString();

        var seed = Files.createDirectories(paths.seed()
                                                .resolve("org/acme/lib/1.0"));

        Files.writeString(seed.resolve("lib-1.0.jar"), "jar");
        Files.writeString(seed.resolve("lib-1.0.pom"), "pom");
    }

    private String exec(Path directory, String... argv) throws IOException, InterruptedException {
        var process = HermeticProcess.builder(argv).directory(directory.toFile())
                                              .redirectErrorStream(true)
                                              .start();
        var output = new String(process.getInputStream()
                                       .readAllBytes(), StandardCharsets.UTF_8).trim();

        assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + output)
                                     .isZero();
        return output;
    }

    private static int nlink(Path file) throws IOException {
        return (Integer) Files.getAttribute(file, "unix:nlink");
    }

    @Test
    void prepare_copiesTheSeed_soEveryFileHasOneLink() throws IOException {
        var workspace = ok(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                            .prepare(job, sha, origin)
                                            .await());

        assertThat(workspace).isEqualTo(paths.workspace(job));
        assertThat(workspace.resolve("pom.xml")).hasContent("<project/>");
        assertThat(workspace.resolve(".home")).isDirectory();

        var jar = paths.workspaceRepo(job)
                       .resolve("org/acme/lib/1.0/lib-1.0.jar");

        assertThat(jar).hasContent("jar");
        assertThat(nlink(jar)).isEqualTo(1);
        assertThat(nlink(paths.seed()
                              .resolve("org/acme/lib/1.0/lib-1.0.jar"))).isEqualTo(1);
        assertThat(ok(SeedVerifier.linkCounts(paths.workspaceRepo(job)))).allSatisfy(links -> assertThat(links.nlink()).isEqualTo(1))
                                                                       .hasSize(2);
    }

    /// Links internal to the seed are not reproduced: two names of one inode become two files of one link each.
    @Test
    void copying_neverReproducesAHardLinkInternalToTheSeed() throws IOException {
        var seed = Files.createDirectories(root.resolve("linked-seed/org/acme/lib/1.0"));
        var target = root.resolve("copy");

        Files.writeString(seed.resolve("a.jar"), "jar");
        Files.createLink(seed.resolve("b.jar"), seed.resolve("a.jar"));
        assertThat(nlink(seed.resolve("a.jar"))).isEqualTo(2);

        ok(Seeder.copying()
                 .copy(root.resolve("linked-seed"), target)
                 .await());

        assertThat(ok(SeedVerifier.linkCounts(target))).allSatisfy(links -> assertThat(links.nlink()).isEqualTo(1))
                                                       .hasSize(2);
        assertThat(target.resolve("org/acme/lib/1.0/a.jar")).hasContent("jar");
        assertThat(target.resolve("org/acme/lib/1.0/b.jar")).hasContent("jar");
    }

    @Test
    void prepare_failsSeedIsolation_whenTheSeedIsHardLinked() {
        var cause = failure(WorkspacePreparer.workspacePreparer(paths, git, hardLinking())
                                             .prepare(job, sha, origin)
                                             .await());

        assertThat(cause).isInstanceOf(SeedError.Hardlinked.class);
        assertThat(WorkerError.code(cause)).isEqualTo("seed-isolation");
        assertThat(((SeedError.Hardlinked) cause).count()).isEqualTo(2);
    }

    @Test
    void prepare_isIdempotent_andNeverRecopiesAnExistingRepository() throws IOException {
        var preparer = WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying());

        ok(preparer.prepare(job, sha, origin)
                   .await());

        var installed = paths.workspaceRepo(job)
                             .resolve("installed-by-a-unit.txt");

        Files.writeString(installed, "x");
        ok(preparer.prepare(job, sha, origin)
                   .await());

        assertThat(installed).exists();
    }

    @Test
    void prepare_checksTheExistingRepositoryEveryTime() throws IOException {
        ok(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                            .prepare(job, sha, origin)
                            .await());
        Files.createLink(paths.workspaceRepo(job)
                              .resolve("linked.jar"),
                         paths.seed()
                              .resolve("org/acme/lib/1.0/lib-1.0.jar"));

        assertThat(failure(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                            .prepare(job, sha, origin)
                                            .await())).isInstanceOf(SeedError.Hardlinked.class);
    }

    @Test
    void prepare_failsSeedMissing_whenTheHostWasNeverSeeded() throws IOException {
        Trees.deleteRecursively(paths.seed())
             .await();

        var cause = failure(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                             .prepare(job, sha, origin)
                                             .await());

        assertThat(WorkerError.code(cause)).isEqualTo("seed-missing");
    }

    @Test
    void prepare_failsGit_forACommitTheOriginDoesNotHave() {
        var unknown = ok(Sha.sha("0".repeat(40)));
        var cause = failure(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                             .prepare(job, unknown, origin)
                                             .await());

        assertThat(cause).isInstanceOf(WorkerError.MissingCommit.class);
        assertThat(WorkerError.code(cause)).isEqualTo("git");
    }

    /// F1 (M1 integration verification): a mirror made from another origin (a host whose live worker ran) is pointed
    /// at the job's origin before the fetch, so a commit only the job's origin has is found.
    @Test
    void prepare_fetchesFromTheJobsOrigin_whenTheMirrorWasMadeFromAnother() throws IOException, InterruptedException {
        var other = Files.createDirectories(root.resolve("other"));

        Files.writeString(other.resolve("README"), "other");
        exec(other, "git", "init", "-q");
        exec(other, "git", "add", "README");
        exec(other, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "other");
        exec(root, "git", "clone", "-q", "--mirror", other.toString(), paths.mirror().toString());

        var workspace = ok(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                            .prepare(job, sha, origin)
                                            .await());

        assertThat(workspace.resolve("pom.xml")).hasContent("<project/>");
        assertThat(exec(root, "git", "--git-dir", paths.mirror().toString(), "config", "--get", "remote.origin.url")).isEqualTo(origin);
    }

    /// A commit only `refs/pull/7/head` of the origin holds (m1-design M1-3, R-M2-7).
    private Sha pullHeadOnly() throws IOException, InterruptedException {
        var repo = Path.of(origin);

        exec(repo, "git", "checkout", "-q", "--detach");
        Files.writeString(repo.resolve("PR"), "seven");
        exec(repo, "git", "add", "PR");
        exec(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "pr 7");
        exec(repo, "git", "update-ref", "refs/pull/7/head", "HEAD");

        var pull = exec(repo, "git", "rev-parse", "HEAD");

        exec(repo, "git", "checkout", "-q", "-");
        assertThat(exec(repo, "git", "branch", "--contains", pull)).as("the PR head is on no branch")
                                                                  .isEmpty();
        return ok(Sha.sha(pull));
    }

    /// The worker's `clone --mirror` and its configured `+refs/*:refs/*` fetch carry `refs/pull/*/head`.
    @Test
    void prepare_checksOutAPullHeadOnlyCommit_onFirstUse() throws IOException, InterruptedException {
        var pull = pullHeadOnly();
        var workspace = ok(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                            .prepare(job, pull, origin)
                                            .await());

        assertThat(workspace.resolve("PR")).hasContent("seven");
    }

    @Test
    void prepare_checksOutAPullHeadOnlyCommit_fetchedIntoAnExistingMirror() throws IOException, InterruptedException {
        ok(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                            .prepare(job, sha, origin)
                            .await());

        var pull = pullHeadOnly();
        var workspace = ok(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                            .prepare(ok(JobId.jobId(32)), pull, origin)
                                            .await());

        assertThat(workspace.resolve("PR")).hasContent("seven");
    }

    /// The `cp -al` mistake: directories mirrored, files hard-linked.
    private static Seeder hardLinking() {
        return (seed, target) -> Promise.lift(thrown -> WorkerError.Io.FACTORY.apply("link", org.pragmatica.lang.utils.Causes.fromThrowable(thrown)),
                                              () -> Files.walkFileTree(seed, new SimpleFileVisitor<>() {
                                                  @Override
                                                  public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                                                      Files.createDirectories(target.resolve(seed.relativize(directory)
                                                                                                 .toString()));
                                                      return FileVisitResult.CONTINUE;
                                                  }

                                                  @Override
                                                  public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                                                      Files.createLink(target.resolve(seed.relativize(file)
                                                                                          .toString()), file);
                                                      return FileVisitResult.CONTINUE;
                                                  }
                                              }))
                                        .mapToUnit();
    }

    @Test
    void linkCounts_listsOnlyRegularFiles() throws IOException {
        var repository = Files.createDirectories(root.resolve("repo/a"));

        Files.writeString(repository.resolve("f"), "x");
        Files.createSymbolicLink(repository.resolve("s"), repository.resolve("f"));

        assertThat(ok(SeedVerifier.linkCounts(root.resolve("repo")))).extracting(links -> links.relativePath())
                                                                     .isEqualTo(List.of("a/f"));
    }

    @Test
    void prepare_failsSeedIsolation_whenTheRepositoryHoldsASymlink() throws IOException {
        Files.createSymbolicLink(paths.seed()
                                      .resolve("org/acme/lib/1.0/link.jar"),
                                 paths.seed()
                                      .resolve("org/acme/lib/1.0/lib-1.0.jar"));

        var cause = failure(WorkspacePreparer.workspacePreparer(paths, git, Seeder.copying())
                                             .prepare(job, sha, origin)
                                             .await());

        assertThat(cause).isInstanceOf(WorkerError.SymlinkedSeed.class);
        assertThat(WorkerError.code(cause)).isEqualTo("seed-isolation");
    }

    @Test
    void prepare_aRetryJoinsThePreparationInFlight() throws Exception {
        var copies = new java.util.concurrent.atomic.AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        Seeder slow = (seed, target) -> Promise.lift(thrown -> WorkerError.Io.FACTORY.apply("wait", org.pragmatica.lang.utils.Causes.fromThrowable(thrown)),
                                                     () -> release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                               .flatMap(_ -> {
                                                   copies.incrementAndGet();
                                                   return Seeder.copying()
                                                                .copy(seed, target);
                                               });
        var preparer = WorkspacePreparer.workspacePreparer(paths, git, slow);
        var first = preparer.prepare(job, sha, origin);
        var second = preparer.prepare(job, sha, origin);

        release.countDown();

        assertThat(ok(first.await())).isEqualTo(ok(second.await()));
        assertThat(copies.get()).isEqualTo(1);
    }
}
