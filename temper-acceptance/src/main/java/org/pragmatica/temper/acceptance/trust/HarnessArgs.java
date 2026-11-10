package org.pragmatica.temper.acceptance.trust;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.option;


/// The command-line values the harnesses share: each a parse of one argument, named for what it yields.
public sealed interface HarnessArgs {
    String HOME = "HOME";
    String TOKEN_FILE = ".config/temper/token";

    /// `$HOME`, or the working directory when the environment has none.
    static Path home(Map<String, String> env) {
        return Path.of(option(env.get(HOME)).or("."));
    }

    static Result<Integer> integer(String text) {
        return Number.parseInt(text);
    }

    static Result<Long> jobId(String text) {
        return Number.parseLong(text);
    }

    static Result<URI> controller(String text) {
        return Network.parseURI(text);
    }

    static Result<LocalDate> day(String text) {
        return DateTime.parseLocalDate(text);
    }

    /// The bearer token: the file's content, trimmed (the CLI's own token file by default).
    static Result<String> token(Path file) {
        return FileOps.readString(file).map(String::strip);
    }

    record unused() implements HarnessArgs {}
}
