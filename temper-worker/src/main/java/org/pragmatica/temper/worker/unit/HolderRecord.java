package org.pragmatica.temper.worker.unit;

import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A bb-hold holder's identity (m1-design §1.4): its pid and the start time of that pid in clock ticks since boot
/// (`/proc/<pid>/stat` field 22). A recycled pid has another start time, so the pair names one process for the
/// life of the host. `.temper/bbhold.pid` holds it as `<pid> <startTicks>`.
///
/// `startTicks` is [#UNKNOWN] when the stat could not be read right after the spawn (the holder had already exited,
/// typically a front refusal): such a record never matches a live process, so the holder reads as dead, which
/// is what it is.
public record HolderRecord(long pid, long startTicks) {
    public static final long UNKNOWN = -1;
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\s+(-?\\d+)\\s*$");

    public static HolderRecord holderRecord(long pid, long startTicks) {
        return new HolderRecord(pid, startTicks);
    }

    /// The `bbhold.pid` content.
    public String fileText() {
        return pid + " " + startTicks;
    }

    /// Parses the `bbhold.pid` content.
    public static Result<HolderRecord> holderRecord(String text) {
        var matcher = LINE.matcher(text);

        return matcher.matches()
               ? Result.all(Number.parseLong(matcher.group(1)),
                            Number.parseLong(matcher.group(2)))
                       .map(HolderRecord::holderRecord)
               : invalid("bbhold.pid").apply(text)
                        .result();
    }

    /// Whether this record names a process whose stat start time is `ticks`.
    public boolean startedAt(Option<Long> ticks) {
        return startTicks != UNKNOWN && ticks.filter(observed -> observed == startTicks)
                                             .isPresent();
    }
}
