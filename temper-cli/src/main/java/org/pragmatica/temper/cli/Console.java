package org.pragmatica.temper.cli;

import java.io.PrintStream;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;


/// The CLI's two output channels: stdout carries the stable one-line results (spec §8.1), stderr carries
/// diagnostics. A line counts as printed only after it was flushed without error, which is what lets `watch`
/// acknowledge an event strictly after its line reached the reader (spec §8.3).
public record Console(PrintStream out, PrintStream err) {
    public static Console console(PrintStream out, PrintStream err) {
        return new Console(out, err);
    }

    public static Console console() {
        return console(System.out, System.err);
    }

    /// Prints `text` and a line break to stdout and flushes; fails when stdout is closed (a `PrintStream`
    /// swallows the IOException and only records it).
    public Result<Unit> line(String text) {
        out.println(text);
        out.flush();

        return out.checkError()
               ? CliError.General.OUTPUT_CLOSED.result()
               : unitResult();
    }

    /// Prints a diagnostic line to stderr. Failure to report is not reportable, so nothing is returned.
    public Unit problem(String text) {
        return detail("temper: " + text);
    }

    /// Prints `text` to stderr as-is (the usage text).
    public Unit detail(String text) {
        err.println(text);
        err.flush();

        return unit();
    }

    /// One failure as one line: its message, then the chain of causes it carries in parentheses. Every link
    /// contributes only its first line: an origin converted from a `Throwable` holds the whole stack trace,
    /// whose first line names the exception and its message; the trace stays in the chain for whoever reads
    /// `source()`, it is not output.
    /// A composite (several failures collected by `Result.all`) shows every member, each with its own chain, so no
    /// line reads only "Composite:".
    public static String oneLine(Cause cause) {
        return switch (cause) {
            case Causes.CompositeCause composite -> members(composite);
            default -> cause.source().map(origin -> firstLine(cause.message()) + " (" + chain(origin) + ")").or(() -> firstLine(cause.message()));
        };
    }

    private static String chain(Cause link) {
        return described(link) + link.source()
                                     .map(next -> ": " + chain(next))
                                     .or("");
    }

    /// A composite (several failures collected by `Result.all`) shows each member with its own chain.
    private static String described(Cause link) {
        return switch (link) {
            case Causes.CompositeCause composite -> members(composite);
            default -> firstLine(link.message());
        };
    }

    private static String members(Causes.CompositeCause composite) {
        return composite.stream()
                        .map(Console::chain)
                        .collect(Collectors.joining("; "));
    }

    private static String firstLine(String text) {
        return Option.from(text.lines().findFirst())
                     .map(String::strip)
                     .or("");
    }
}
