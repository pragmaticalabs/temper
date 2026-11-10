package org.pragmatica.temper.controller.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.submit.PullOriginError;


/// The `owner/name` of a github.com origin URL: `git@github.com:o/r.git`, `ssh://git@github.com[:22]/o/r.git` or
/// `https://[user@]github.com[:443]/o/r[.git]`, the host in any case. Anything else (another host, `www.github.com`,
/// another port, a local path) is [PullOriginError.General#NOT_GITHUB] (fail closed). A name of dots only is refused, so it can never walk the API path.
public record GitHubRepo(String owner, String name) {
    private static final Pattern URL = Pattern.compile("(?:git@(?i:github\\.com):"
                                                      + "|https://(?:[^@/]+@)?(?i:github\\.com)(?::443)?/"
                                                      + "|ssh://(?:[^@/]+@)?(?i:github\\.com)(?::22)?/)"
                                                      + "([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)"
                                                      + "(?:\\.git)?/?");

    private static final Pattern DOTS = Pattern.compile("\\.+");

    public static Result<GitHubRepo> gitHubRepo(String origin) {
        return Option.some(URL.matcher(origin.trim()))
                     .filter(Matcher::matches)
                     .filter(matcher -> !DOTS.matcher(matcher.group(2)).matches())
                     .map(matcher -> new GitHubRepo(matcher.group(1),
                                                    matcher.group(2)))
                     .toResult(PullOriginError.General.NOT_GITHUB);
    }

    /// `owner/name`, as GitHub's `full_name`.
    public String fullName() {
        return owner + "/" + name;
    }
}
