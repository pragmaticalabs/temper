package org.pragmatica.temper.acceptance.trust;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// A harness command line: `--name value` pairs (a flag may repeat; order kept) and bare switches. Pure.
public record Flags(List<Flag> values, Set<String> switches) {
    public static final String PREFIX = "--";
    private static final Flags NONE = new Flags(List.of(), Set.of());

    public Flags {
        values = List.copyOf(values);
        switches = Set.copyOf(switches);
    }

    /// One `--name value` pair.
    public record Flag(String name, String value) {
        public static Flag flag(String name, String value) {
            return new Flag(name, value);
        }

        boolean named(String wanted) {
            return name.equals(wanted);
        }
    }

    /// Parses `args`: a name in `switchNames` takes no value, every other `--name` takes the next argument, and
    /// anything else is a usage error.
    public static Result<Flags> flags(List<String> args, Set<String> switchNames) {
        return flags(args, switchNames, NONE);
    }

    /// The flags of `rest` added to `done`, one argument (or `--name value` pair) at a time.
    private static Result<Flags> flags(List<String> rest, Set<String> switchNames, Flags done) {
        return rest.isEmpty()
               ? success(done)
               : flags(rest, switchNames, done, rest.getFirst());
    }

    private static Result<Flags> flags(List<String> rest, Set<String> switchNames, Flags done, String first) {
        return switchNames.contains(first)
               ? flags(rest.subList(1, rest.size()),
                       switchNames,
                       done.withSwitch(first))
               : flags(rest, switchNames, done, Flag.flag(first, valueAfter(rest)));
    }

    /// The argument after the first; empty when there is none (the pair is then incomplete and refused).
    private static String valueAfter(List<String> rest) {
        return rest.size() > 1
               ? rest.subList(1, 2)
                     .getFirst()
               : "";
    }

    private static Result<Flags> flags(List<String> rest, Set<String> switchNames, Flags done, Flag next) {
        return next.name()
                   .startsWith(PREFIX) && rest.size() > 1
               ? flags(rest.subList(2, rest.size()),
                       switchNames,
                       done.withValue(next))
               : TrustError.Usage.FACTORY.apply("unexpected argument " + next.name()).result();
    }

    private Flags withSwitch(String name) {
        return new Flags(values,
                         Stream.concat(switches.stream(), Stream.of(name)).collect(Collectors.toUnmodifiableSet()));
    }

    private Flags withValue(Flag flag) {
        return new Flags(Stream.concat(values.stream(), Stream.of(flag)).toList(),
                         switches);
    }

    /// The last value of `name`.
    public Option<String> value(String name) {
        return Option.from(all(name).stream().reduce((_, last) -> last));
    }

    public Result<String> required(String name) {
        return value(name).toResult(TrustError.Usage.FACTORY.apply(name + " is required"));
    }

    /// Every value of `name`, in order.
    public List<String> all(String name) {
        return values.stream()
                     .filter(flag -> flag.named(name))
                     .map(Flag::value)
                     .toList();
    }

    public boolean has(String switchName) {
        return switches.contains(switchName);
    }
}
