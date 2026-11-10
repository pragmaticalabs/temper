package org.pragmatica.temper.controller.submit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;


/// A pull request's number, read from its head ref `refs/pull/<N>/head` (m1-design M1-3, R-M2-7).
public record PullNumber(long value) {
    private static final Pattern HEAD_REF = Pattern.compile("refs/pull/([1-9][0-9]{0,9})/head");

    /// None for a name that is not a pull-request head ref.
    public static Option<PullNumber> fromHeadRef(String refName) {
        return Option.some(HEAD_REF.matcher(refName.trim()))
                     .filter(Matcher::matches)
                     .map(matcher -> new PullNumber(Long.parseLong(matcher.group(1))));
    }

    @Override
    public String toString() {
        return "#" + value;
    }
}
