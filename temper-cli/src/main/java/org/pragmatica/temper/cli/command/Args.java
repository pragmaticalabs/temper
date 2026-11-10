package org.pragmatica.temper.cli.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// The tokens of one command line after the verb: positionals in order, `--name value` options, bare flags,
/// and everything after `--` (mvn arguments).
record Args(List<String> positionals, Map<String, String> options, Set<String> flags, List<String> passthrough) {
    static final Args EMPTY = new Args(List.of(), Map.of(), Set.of(), List.of());

    /// Which options and flags a verb accepts, how many positionals it takes, and whether `--` is allowed.
    record Shape(Set<String> options, Set<String> flags, int positionals, boolean passthrough) {
        static Shape shape(Set<String> options, Set<String> flags, int positionals, boolean passthrough) {
            return new Shape(options, flags, positionals, passthrough);
        }

        boolean isAllowed(String name) {
            return options.contains(name) || flags.contains(name);
        }
    }

    Args withPositional(String value) {
        var next = new ArrayList<>(positionals);

        next.add(value);

        return new Args(List.copyOf(next), options, flags, passthrough);
    }

    Result<Args> withOption(String name, String value) {
        var next = new HashMap<>(options);

        return option(next.putIfAbsent(name, value)).map(_ -> CommandLine.<Args> usage(name + " given twice"))
                     .or(() -> success(new Args(positionals,
                                                Map.copyOf(next),
                                                flags,
                                                passthrough)));
    }

    /// A repeatable option (`--match`): a second value joins the first, comma-separated, as one comma-separated value
    /// would be given.
    Args withRepeated(String name, String value) {
        var next = new HashMap<>(options);

        next.merge(name, value, Args::joined);

        return new Args(positionals, Map.copyOf(next), flags, passthrough);
    }

    private static String joined(String first, String second) {
        return first + "," + second;
    }

    Args withFlag(String name) {
        var next = new HashSet<>(flags);

        next.add(name);

        return new Args(positionals, options, Set.copyOf(next), passthrough);
    }

    Args withPassthrough(List<String> rest) {
        return new Args(positionals, options, flags, List.copyOf(rest));
    }

    Option<String> optionValue(String name) {
        return option(options.get(name));
    }

    boolean hasFlag(String name) {
        return flags.contains(name);
    }

    boolean isGiven(String name) {
        return options.containsKey(name) || flags.contains(name);
    }

    /// Fails with a usage error naming the first option or flag `verb` does not accept, a wrong number of
    /// positionals, or `--` where the verb takes no mvn arguments.
    Result<Args> restrict(String verb, Shape shape) {
        return Option.from(Stream.concat(options.keySet().stream().sorted(),
                                         flags.stream().sorted())
                                 .filter(Predicate.not(shape::isAllowed))
                                 .findFirst())
                     .map(name -> CommandLine.<Args> usage(name + " does not apply to " + verb))
                     .or(() -> counted(verb, shape));
    }

    private Result<Args> counted(String verb, Shape shape) {
        return positionals.size() != shape.positionals()
               ? CommandLine.usage(verb + " takes " + shape.positionals() + " argument(s), got " + positionals)
               : passthroughAllowed(verb, shape);
    }

    private Result<Args> passthroughAllowed(String verb, Shape shape) {
        return passthrough.isEmpty() || shape.passthrough()
               ? success(this)
               : CommandLine.usage(verb + " takes no '--' arguments");
    }
}
