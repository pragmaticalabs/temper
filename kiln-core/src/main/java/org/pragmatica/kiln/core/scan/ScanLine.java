package org.pragmatica.kiln.core.scan;

import java.time.Instant;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.ScanError.BadField;
import org.pragmatica.kiln.core.scan.ScanError.NotJson;
import org.pragmatica.kiln.core.scan.ScanError.UnknownKind;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.kiln.core.scan.ScanFields.count;
import static org.pragmatica.kiln.core.scan.ScanFields.flag;
import static org.pragmatica.kiln.core.scan.ScanFields.instant;
import static org.pragmatica.kiln.core.scan.ScanFields.list;
import static org.pragmatica.kiln.core.scan.ScanFields.optional;
import static org.pragmatica.kiln.core.scan.ScanFields.sizeOf;
import static org.pragmatica.kiln.core.scan.ScanFields.text;
import static org.pragmatica.lang.Result.all;


/// One JSON line of `kiln-scan scan` (kiln-f1-design §3.2). One object per line, field order free, unknown fields
/// ignored; instants are whole epoch seconds (what `stat` prints); `null` and absent are the same:
///
/// ```
/// {"kind":"checkout","path":"/w/co/a","head":"<sha>"|null,"branch":"main"|null,"remote":"<url>"|null,
///  "branches":[{"name":"main","tip":"<sha>"}],"modified":0,"untracked":0,"stash":0,"op":"rebase"|null,
///  "activityAt":1761820000,"processes":[4242],"maven":false,"commonDir":"/w/co/a/.git","salvageRefs":3|null}
/// {"kind":"stub","path":"/w/co/b","activityAt":1761820000,"commonDir":"/w/co/b/.git"}
/// {"kind":"nonGit","root":"~/work/co/*","path":"/w/co/c"}
/// {"end":true,"count":2,"nonGit":1,"nonGitBytes":null,"home":"/home/builder"}
/// ```
///
/// `head`, `branch`, `remote`, `op`, `salvageRefs`, `nonGitBytes` and `home` are optional; every other field is required,
/// and a required field that is absent, null or of the wrong type fails the line (an optional one given with the wrong
/// type fails it too). `home` is the scanned host's `cd ~ && pwd -P`, the expansion of a `~` root; a trailer from a
/// script that predates it has none.
public sealed interface ScanLine {
    /// A checkout with full facts.
    record CheckoutLine(CheckoutFacts facts) implements ScanLine {}

    /// A checkout listed without facts: path, `activityAt` and common dir (three `stat`s).
    record StubLine(CheckoutPath path, Instant activityAt, String commonDir) implements ScanLine {}

    /// A non-git directory under `root`: counted in the trailer's `nonGit`, not in its `count`.
    record NonGitLine(String root, CheckoutPath path) implements ScanLine {}

    /// The last line: `count` checkout and stub lines, `nonGit` non-git directories, `home` the host's canonical home.
    record Trailer(int count, int nonGit, Option<Long> nonGitBytes, Option<String> home) implements ScanLine {}

    static Result<ScanLine> decode(String line) {
        return ScanFields.MAPPER.readTree(line)
                                .mapError(NotJson.FACTORY)
                                .flatMap(ScanLine::decoded)
                                .mapError(ScanError::first);
    }

    /// A line holding `end` is a trailer; every other line names its `kind`.
    private static Result<ScanLine> decoded(JsonNode line) {
        return line.has("end")
               ? trailer(line)
               : text(line, "kind").flatMap2(ScanLine::ofKind, line);
    }

    private static Result<ScanLine> ofKind(String kind, JsonNode line) {
        return switch (kind) {
            case "checkout" -> checkout(line);
            case "stub" -> stub(line);
            case "nonGit" -> nonGit(line);
            default -> UnknownKind.FACTORY.apply(kind).result();
        };
    }

    private static Result<ScanLine> checkout(JsonNode line) {
        return all(path(line, "path"),
                   optional(line, "head", ScanLine::sha),
                   optional(line, "branch", ScanLine::branch),
                   optional(line, "remote", ScanFields::text),
                   list(line, "branches", ScanLine::branchTip),
                   count(line, "modified"),
                   count(line, "untracked"),
                   optional(line, "op", ScanFields::text),
                   instant(line, "activityAt"),
                   list(line, "processes", ScanLine::pid),
                   flag(line, "maven"),
                   text(line, "commonDir"),
                   count(line, "stash"),
                   optional(line, "salvageRefs", ScanFields::count)).map(CheckoutFacts::new)
                  .map(CheckoutLine::new);
    }

    private static Result<ScanLine> stub(JsonNode line) {
        return all(path(line, "path"), instant(line, "activityAt"), text(line, "commonDir")).map(StubLine::new);
    }

    private static Result<ScanLine> nonGit(JsonNode line) {
        return all(text(line, "root"), path(line, "path")).map(NonGitLine::new);
    }

    /// `end` must be `true`: `false` is no trailer the script writes.
    private static Result<ScanLine> trailer(JsonNode line) {
        return all(flag(line, "end").filter(BadField.FACTORY.apply("end"), Boolean::booleanValue),
                   count(line, "count"),
                   count(line, "nonGit"),
                   optional(line, "nonGitBytes", ScanFields::size),
                   optional(line, "home", ScanFields::text)).map(ScanLine::trailer);
    }

    private static ScanLine trailer(boolean end, int count, int nonGit, Option<Long> nonGitBytes, Option<String> home) {
        return new Trailer(count, nonGit, nonGitBytes, home);
    }

    private static Result<BranchTip> branchTip(JsonNode tip) {
        return all(branch(tip, "name"), sha(tip, "tip")).map(BranchTip::new);
    }

    private static Result<Long> pid(JsonNode pid) {
        return sizeOf(pid, "processes");
    }

    private static Result<CheckoutPath> path(JsonNode line, String field) {
        return text(line, field).flatMap(CheckoutPath::checkoutPath);
    }

    private static Result<Sha> sha(JsonNode line, String field) {
        return text(line, field).flatMap(Sha::sha);
    }

    private static Result<BranchName> branch(JsonNode line, String field) {
        return text(line, field).flatMap(BranchName::branchName);
    }
}
