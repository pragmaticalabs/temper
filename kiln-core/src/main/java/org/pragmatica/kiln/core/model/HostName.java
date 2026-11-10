package org.pragmatica.kiln.core.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// A scanned host's name (`[[hosts]] name`, the `host:<name>` source key, the `checkout.host` column). `mac` is
/// reserved for the Mac ingest: the factory refuses it, so no configured host can share the Mac's rows, and the ingest
/// uses [#MAC].
public record HostName(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final String RESERVED = "mac";
    /// The rows the Mac pushes through `kiln-ingest` (spec §5.4).
    public static final HostName MAC = new HostName(RESERVED);

    public static Result<HostName> hostName(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid("host name"))
                     .flatMap(present -> Verify.ensure(present,
                                                       Verify.Is::matches,
                                                       PATTERN,
                                                       invalid("host name")))
                     .flatMap(shaped -> Verify.ensure(shaped,
                                                      Verify.Is::notEqualTo,
                                                      RESERVED,
                                                      invalid("host name")))
                     .map(HostName::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
