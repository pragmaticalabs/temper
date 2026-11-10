package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// T1 step 1 for one SHA (m1-design §8.1): the checkout's HEAD is the SHA; `.bb/args` is empty or `clean install`
/// (bb-build's default); `.bb/rc` exists; report XML newer than `.bb/run.marker` exists. A SHA failing any check is
/// not comparable. `head` is the full SHA git answered (the maintainer names SHAs by a prefix). A missing `.bb/args` fails
/// its check: the run's args cannot be shown to be the Gate's.
public record Preflight(String sha,
                        Option<String> head,
                        boolean headMatches,
                        boolean defaultArgs,
                        boolean rcPresent,
                        boolean reportXmlPresent) {
    public static final Set<String> DEFAULT_ARGS = Set.of("", "clean install");
    private static final TimeSpan GIT_TIMEOUT = timeSpan(1).minutes();

    public static Preflight preflight(String sha,
                                      Option<String> head,
                                      boolean defaultArgs,
                                      boolean rcPresent,
                                      boolean reportXmlPresent) {
        return new Preflight(sha,
                             head,
                             head.map(full -> full.startsWith(sha)).or(false),
                             defaultArgs,
                             rcPresent,
                             reportXmlPresent);
    }

    public boolean comparable() {
        return headMatches
               && defaultArgs
               && rcPresent
               && reportXmlPresent;
    }

    /// Every check of `checkout`. A check that cannot be made (git failing, `find` failing) fails, never passes.
    public static Promise<Preflight> check(Processes processes, String sha, BbCheckout checkout) {
        return Promise.all(head(processes, checkout), reportXmlPresent(processes, checkout)).map((head, xml) -> preflight(sha,
                                                                                                                          head,
                                                                                                                          defaultArgs(checkout.bb()
                                                                                                                                              .resolve(BbCheckout.ARGS)),
                                                                                                                          Files.isRegularFile(checkout.bb()
                                                                                                                                                      .resolve(BbCheckout.RC)),
                                                                                                                          xml));
    }

    /// FER (degrade forward): a HEAD git cannot read is none, which fails `headMatches`; the SHA is then reported as
    /// not comparable instead of ending the run.
    private static Promise<Option<String>> head(Processes processes, BbCheckout checkout) {
        return processes.output(List.of("git",
                                        "-C",
                                        checkout.root().toString(),
                                        "rev-parse",
                                        "HEAD"),
                                GIT_TIMEOUT)
                        .map(output -> some(output.strip()))
                        .recover(_ -> none());
    }

    /// FER (degrade forward): a `find` that fails reads as no report XML, so the SHA is reported as not comparable.
    private static Promise<Boolean> reportXmlPresent(Processes processes, BbCheckout checkout) {
        return checkout.reportXml(processes)
                       .map(files -> !files.isEmpty())
                       .recover(_ -> false);
    }

    /// `.bb/args` as bb-build wrote it, whitespace-normalised; a file that is absent or unreadable is not the default.
    private static boolean defaultArgs(Path args) {
        return FileOps.readString(args)
                      .map(text -> String.join(" ",
                                               text.strip().split("\\s+")))
                      .map(DEFAULT_ARGS::contains)
                      .or(false);
    }
}
