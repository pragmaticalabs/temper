package org.pragmatica.temper.domain.mvn;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.MvnOption;
import org.pragmatica.temper.domain.mvn.SideEffectAllowlist.ValueShape;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The options of a user's `mvn` arguments, read against the option entries of [SideEffectAllowlist] (m4-design §5.1, maintainer
/// ruling Q-M4-11d). Only an exact listed spelling is an option: its short or its long form as one argv element, and its
/// value, when its shape takes one, the next element; `-D` and `-P` also take the value attached (`-Dk=v`, `-Pname`).
/// Every other element starting with `-` (`-fn`, `-fail-never`, `--fail-never=true`, `-pl=a`, `-fa/pom.xml`, `-T4`) is
/// [Written.Unlisted], consumes nothing after it, and is never dropped: [SideEffectRule] refuses it and [UserProperties]
/// marks it. An element without a leading `-` is a goal or a phase, which [MvnStep] reads.
public sealed interface UserOptions {
    /// One option element of the user's arguments.
    sealed interface Written {
        /// A listed option, by the spelling written (`-D` for an attached `-Dk=v`), with its value when its shape takes
        /// one; none when the arguments end first.
        record Listed(MvnOption option, String name, Option<String> value) implements Written {}

        /// An element starting with `-` that is no listed spelling.
        record Unlisted(String token) implements Written {}
    }

    /// The option elements of `args` (the arguments without the executable), in argv order.
    static List<Written> written(List<String> args, List<MvnOption> options) {
        return Stream.iterate(0,
                              index -> index < args.size(),
                              index -> index + width(args.get(index),
                                                     options))
                     .flatMap(index -> writtenAt(args, index, options).stream())
                     .toList();
    }

    /// 2 for an exact listed spelling whose shape takes a value, else 1.
    private static int width(String arg, List<MvnOption> options) {
        return exact(arg, options).filter(option -> option.shape() != ValueShape.NONE)
                    .isPresent()
               ? 2
               : 1;
    }

    private static Option<Written> writtenAt(List<String> args, int index, List<MvnOption> options) {
        var arg = args.get(index);

        return arg.startsWith("-")
               ? some(exact(arg, options).<Written> map(option -> new Written.Listed(option,
                                                                                     arg,
                                                                                     valueOf(option, args, index)))
                           .or(() -> attached(arg, options)))
               : none();
    }

    private static Option<String> valueOf(MvnOption option, List<String> args, int index) {
        return option.shape() != ValueShape.NONE && index + 1 < args.size()
               ? some(args.get(index + 1))
               : none();
    }

    /// `-Dk=v` or `-Pname`: a `DEFINE` or `PROFILES` option's short spelling with its value attached; else unlisted.
    private static Written attached(String arg, List<MvnOption> options) {
        return Option.from(options.stream()
                                  .filter(option -> option.shape() == ValueShape.DEFINE || option.shape() == ValueShape.PROFILES)
                                  .filter(option -> arg.length() > option.shortName()
                                                                         .length() && arg.startsWith(option.shortName()))
                                  .findFirst())
                     .<Written> map(option -> new Written.Listed(option,
                                                                 option.shortName(),
                                                                 some(arg.substring(option.shortName().length()))))
                     .or(() -> new Written.Unlisted(arg));
    }

    private static Option<MvnOption> exact(String arg, List<MvnOption> options) {
        return Option.from(options.stream()
                                  .filter(option -> option.shortName()
                                                          .equals(arg) || option.longName()
                                                                                .equals(arg))
                                  .findFirst());
    }

    record unused() implements UserOptions {}
}
