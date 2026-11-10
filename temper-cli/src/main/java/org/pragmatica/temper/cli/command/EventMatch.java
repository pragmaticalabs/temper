package org.pragmatica.temper.cli.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.transport.FleetLine;

import tools.jackson.databind.JsonNode;


/// One `--match k=v` pair of `events --until`: `subject` addresses the event's subject, any other
/// key a top-level `payload` field, compared as text (`attempt=2` matches the number 2). The controller matches the same
/// pairs; the CLI checks them again on every line it is given, so a controller that sends more than it should cannot
/// end a wait.
public record EventMatch(String key, String value) {
    public static final String SUBJECT = "subject";
    private static final Pattern PAIR = Pattern.compile("^([A-Za-z0-9_.-]+)=(.*)$");

    public static Result<EventMatch> eventMatch(String raw) {
        return Option.some(PAIR.matcher(raw))
                     .filter(Matcher::matches)
                     .toResult(CliError.Usage.FACTORY.apply("--match takes k=v (k is subject or a payload field): " + raw))
                     .map(pair -> new EventMatch(pair.group(1),
                                                 pair.group(2)));
    }

    public boolean holdsFor(FleetLine.Event event) {
        return SUBJECT.equals(key)
               ? value.equals(event.subject())
               : holdsFor(event.payload().path(key));
    }

    private boolean holdsFor(JsonNode field) {
        return field.isValueNode()
               && !field.isNull()
               && value.equals(field.asString());
    }

    /// `k=v`, as the route's `match` parameter takes it.
    public String text() {
        return key + "=" + value;
    }
}
