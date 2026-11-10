package org.pragmatica.kiln.core.model;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// A checkout's absolute path on its host, as the scan reports it: starts with `/`, holds no control character
/// ([Printable]), and is canonical: split on `/`, no component is empty (`//`, a trailing `/`), `.` or `..`. A
/// non-canonical alias of a checkout is refused, never normalised, so one checkout has one key; the scan emits canonical
/// paths. A name merely containing two dots (`/w/a..b`) is a legal directory and is kept: refusing it would make every
/// scan of its root undecodable.
public record CheckoutPath(String value) {
    private static final Pattern ABSOLUTE = Pattern.compile("^/[^\\p{Cc}\\u2028\\u2029]*$");

    public static Result<CheckoutPath> checkoutPath(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid("checkout path"))
                     .flatMap(present -> Verify.ensure(present,
                                                       Verify.Is::matches,
                                                       ABSOLUTE,
                                                       invalid("checkout path")))
                     .flatMap(absolute -> Verify.ensure(absolute,
                                                        CheckoutPath::canonical,
                                                        invalid("checkout path")))
                     .map(CheckoutPath::new);
    }

    private static boolean canonical(String path) {
        return Arrays.stream(path.substring(1).split("/", -1)).allMatch(CheckoutPath::component);
    }

    private static boolean component(String component) {
        return ! component.isEmpty()
               && !component.equals(".")
               && !component.equals("..");
    }

    @Override
    public String toString() {
        return value;
    }
}
