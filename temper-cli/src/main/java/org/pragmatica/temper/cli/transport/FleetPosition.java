package org.pragmatica.temper.cli.transport;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.cli.CliError;


/// A place in temper's fleet event log: the temper epoch (a UUID) and a `seq` in it, written
/// `<epoch>:<seq>`. A `seq` means something only within its epoch: a restored or replaced database starts a new one.
public record FleetPosition(String epoch, long seq) {
    private static final Pattern FORM = Pattern.compile("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}):([0-9]{1,19})$");

    /// `<uuid>:<seq>`, as `events --until` takes it in `--after`; the epoch is kept in lower case, as a UUID prints.
    public static Result<FleetPosition> fleetPosition(String raw) {
        return Option.some(FORM.matcher(raw))
                     .filter(Matcher::matches)
                     .toResult(CliError.Usage.FACTORY.apply("--after takes <epoch>:<seq> (the epoch UUID and seq of an event line): " + raw))
                     .flatMap(form -> parsed(raw, form));
    }

    public static FleetPosition fleetPosition(String epoch, long seq) {
        return new FleetPosition(epoch, seq);
    }

    private static Result<FleetPosition> parsed(String raw, Matcher form) {
        return Number.parseLong(form.group(2))
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply("--after", raw, cause))
                     .map(seq -> fleetPosition(form.group(1).toLowerCase(Locale.ROOT),
                                               seq));
    }

    /// The same epoch, at `seen` when that is further on.
    public FleetPosition past(long seen) {
        return fleetPosition(epoch, Math.max(seq, seen));
    }

    /// `<epoch>:<seq>`, the `after` query value.
    public String text() {
        return epoch + ":" + seq;
    }
}
