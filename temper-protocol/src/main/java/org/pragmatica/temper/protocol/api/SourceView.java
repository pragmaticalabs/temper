package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Ref;

import static org.pragmatica.lang.Option.some;


/// A job's source as shown in views and the result record (spec §5.4): `{"sha": ..., "ref": ...}`. The SHA is
/// absent only for a job refused before resolution (`REFUSED(source)`).
public record SourceView(Option<String> ref, Option<String> sha) {
    public static SourceView sourceView(PushedSource source) {
        return new SourceView(source.ref().map(Ref::value),
                              some(source.sha().value()));
    }
}
