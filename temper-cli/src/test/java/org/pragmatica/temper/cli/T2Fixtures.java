package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.merge.GhMerge;
import org.pragmatica.temper.cli.merge.MergePorts;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

/// The JSON the stub controller serves, field for field (pr-fresh, GO, consume),
/// built as ordered maps so a test changes exactly the field it is about. Also the `GhMerge` spy and the ports.
final class T2Fixtures {
    static final String REPO = "acme/widget";
    static final long PR = 7;
    static final String BASE = "main";
    static final String HEAD_REF = "feature";
    static final String GO_ID = "0b6f7c1e-2a3d-4e5f-8a9b-0c1d2e3f4a5b";
    static final String EPOCH = "6f1c2d3e-4a5b-4c6d-8e7f-901a2b3c4d5e";
    static final String VERDICT_TOKEN = "v3rdict-token";
    /// The digest the stub's `POST fleet/verdicts` 201 answers (the server's row decides what is printed).
    static final String REGISTERED_SHA256 = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12";
    static final String CHECKED_AT = "2026-11-03T10:00:00Z";

    /// `gh pr merge` calls, and the exit every call answers.
    static final class GhSpy implements GhMerge {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile GhExit exit = GhExit.ghExit(0, "");

        @Override
        public Promise<GhExit> merge(String repo, long pr, String sha) {
            calls.add(repo + " " + pr + " " + sha);
            return Promise.success(exit);
        }
    }

    /// A runner that runs every command and then, after the first one whose argv contains `trigger`, runs `after` once
    /// (a push landing between two of the CLI's git calls, G8.f).
    static ProcessRunner interleaved(ProcessRunner runner, String trigger, Runnable after) {
        var fired = new java.util.concurrent.atomic.AtomicBoolean();

        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return runner.run(command)
                             .map(completed -> {
                                 if (command.argv().contains(trigger) && fired.compareAndSet(false, true)) {
                                     after.run();
                                 }
                                 return completed;
                             });
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return runner.spawn(command);
            }
        };
    }

    /// A runner whose every `git` is a real process that never answers (`/bin/sleep 60`, same env and bound), so the
    /// runner's own timeout and kill are what end it. The JDK resolves an executable from the parent's PATH, so a fake
    /// `git` on the child's PATH would never be the one run; the port is the injection point.
    static ProcessRunner hangingGit(ProcessRunner runner) {
        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return "git".equals(command.argv().getFirst())
                       ? Command.command(List.of("/bin/sleep", "60"), command.env(), command.timeout()).async().flatMap(runner::run)
                       : runner.run(command);
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return runner.spawn(command);
            }
        };
    }

    static MergePorts ports(GitOrigin origin, GhMerge gh) {
        return ports(origin, gh, ProcessRunner.processRunner(), MergePorts.BUDGET);
    }

    static MergePorts ports(GitOrigin origin, GhMerge gh, ProcessRunner runner, Duration budget) {
        return ports(origin, gh, runner, budget, Duration.ofSeconds(35));
    }

    /// `call` caps every single HTTP and git call (production 35 s).
    static MergePorts ports(GitOrigin origin, GhMerge gh, ProcessRunner runner, Duration budget, Duration call) {
        LongSupplier nanos = System::nanoTime;

        return MergePorts.mergePorts(origin.base(), runner, gh, budget, nanos, call);
    }

    /// The pull-only reply of `GET fleet/pr-fresh` without `sha` (ruling 11-03 item 2).
    static Map<String, Object> pullOnly(String headSha) {
        var reply = new LinkedHashMap<String, Object>();
        @SuppressWarnings("unchecked")
        var pull = (Map<String, Object>) prFresh(headSha, headSha, null).get("pull");

        reply.put("repo", REPO);
        reply.put("pr", PR);
        reply.put("pull", pull);
        reply.put("freshToken", null);
        return reply;
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String verdictLine(String verdict, String sha) {
        return "VERDICT: " + verdict + " " + REPO + "#" + PR + "@" + sha + " base=" + BASE;
    }

    /// A report whose last line is `VERDICT: ACCEPT <repo>#<pr>@<sha> base=main`.
    static Path report(Path dir, String name, String sha) throws IOException {
        return Files.writeString(dir.resolve(name), "# review round 1\nall good\n" + verdictLine("ACCEPT", sha) + "\n");
    }

    static Map<String, Object> verdict(long id, Path report, String carriedFrom) throws IOException {
        var verdict = new LinkedHashMap<String, Object>();

        verdict.put("id", id);
        verdict.put("verdict", "ACCEPT");
        verdict.put("by", "t2v");
        verdict.put("at", "2026-11-03T09:00:00Z");
        verdict.put("base", BASE);
        verdict.put("reportPath", report.toString());
        verdict.put("reportSha256", sha256(report));
        verdict.put("carriedFrom", carriedFrom);
        return verdict;
    }

    /// The pr-fresh reply for a clean, all-green PR at `sha` on base `main`, kiln's tip `baseTip`, the verdict given.
    static Map<String, Object> prFresh(String sha, String baseTip, Map<String, Object> verdict) {
        var pull = new LinkedHashMap<String, Object>();

        pull.put("state", "open");
        pull.put("headSha", sha);
        pull.put("headRef", HEAD_REF);
        pull.put("headRepo", REPO);
        pull.put("base", BASE);
        pull.put("changedFiles", 1);
        var tip = new LinkedHashMap<String, Object>();

        tip.put("tracked", true);
        tip.put("sha", baseTip);
        tip.put("seenAt", "2026-11-03T09:59:00Z");
        var check = new LinkedHashMap<String, Object>();

        check.put("context", "build");
        check.put("appId", 15368L);
        check.put("outcome", "green");
        check.put("observation", "9001");
        check.put("workflow", "ci");
        check.put("runId", 41L);
        check.put("runNumber", 12L);
        check.put("attempt", 1L);
        check.put("conclusion", "success");
        var reply = new LinkedHashMap<String, Object>();

        reply.put("repo", REPO);
        reply.put("pr", PR);
        reply.put("sha", sha);
        reply.put("checkedAt", CHECKED_AT);
        reply.put("fleet", Map.of("epoch", EPOCH, "seq", 812L));
        reply.put("pull", pull);
        reply.put("base", BASE);
        reply.put("baseTip", tip);
        reply.put("checks", List.of(check));
        reply.put("verdict", verdict);
        reply.put("reasons", new ArrayList<>());
        reply.put("freshToken", "v1.1762163999000.812.c2lnbmF0dXJl");
        return reply;
    }

    static Map<String, Object> reason(String check, String line) {
        return Map.of("check", check, "line", line);
    }

    /// `reasons` set, and `freshToken` null as the controller does when there are any.
    @SuppressWarnings("unchecked")
    static Map<String, Object> withReasons(Map<String, Object> reply, List<Map<String, Object>> reasons) {
        reply.put("reasons", reasons);
        reply.put("freshToken", null);
        return reply;
    }

    static Map<String, Object> goCreated() {
        return Map.of("id", GO_ID, "createdAt", "2026-11-03T10:00:01Z", "expiresAt", "2026-11-03T10:10:01Z");
    }

    static Map<String, Object> consumed(String sha, String baseTip, Path report) throws IOException {
        var reply = new LinkedHashMap<String, Object>();

        reply.put("id", GO_ID);
        reply.put("repo", REPO);
        reply.put("pr", PR);
        reply.put("sha", sha);
        reply.put("base", BASE);
        reply.put("baseTip", baseTip);
        reply.put("verdictId", 5L);
        reply.put("consumedAt", "2026-11-03T10:01:00Z");
        reply.put("invalidated", List.of());
        reply.put("verdict", Map.of("reportPath", report.toString(), "reportSha256", sha256(report)));
        return reply;
    }

    /// A 0600 verdict-token file at `file`.
    static Path tokenFile(Path file, String token) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, token + "\n");
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private T2Fixtures() {}
}
