package org.pragmatica.temper.ghapp;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;


/// What may reach a log line or an error message about a mint (never a token or a JWT). A failure is
/// described by its class and, for the few client-side network failures whose text is made by the JDK, its first line;
/// every other exception contributes its class name only, because its text may quote what the server sent. Anything
/// shaped like a JWT or a GitHub token is masked in all text that passes here, and the result is capped.
sealed interface Redact {
    int REASON_CAP = 200;
    String MASK = "***";

    Pattern SECRET = Pattern.compile("eyJ[A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]*)*"
                                    + "|gh[pousr]_[A-Za-z0-9_]+"
                                    + "|github_pat_[A-Za-z0-9_]+"
                                    + "|(?i:Bearer)\\s+\\S+");

    Set<Class<? extends Throwable>> OWN_TEXT = Set.of(ConnectException.class,
                                                      HttpConnectTimeoutException.class,
                                                      HttpTimeoutException.class,
                                                      UnknownHostException.class);

    /// The reason to store for `thrown`: at most [#REASON_CAP] characters, one line, no secret shapes.
    static String reason(Throwable thrown) {
        var cause = unwrapped(thrown);
        var name = cause.getClass().getSimpleName();

        return capped(OWN_TEXT.stream().anyMatch(type -> type.isInstance(cause)) && cause.getMessage() != null
                      ? name + ": " + firstLine(cause.getMessage())
                      : name);
    }

    /// Server- or user-provided text made safe to log: secret shapes masked, then capped.
    static String text(String text, int cap) {
        var masked = SECRET.matcher(text).replaceAll(MASK);

        return masked.substring(0,
                                Math.min(masked.length(), cap));
    }

    private static String capped(String text) {
        return text(text, REASON_CAP);
    }

    private static String firstLine(String message) {
        var end = message.indexOf('\n');

        return end < 0
               ? message
               : message.substring(0, end);
    }

    private static Throwable unwrapped(Throwable thrown) {
        return thrown instanceof CompletionException && thrown.getCause() != null
               ? unwrapped(thrown.getCause())
               : thrown;
    }

    record unused() implements Redact {}
}
