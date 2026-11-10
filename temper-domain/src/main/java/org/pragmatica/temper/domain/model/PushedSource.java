package org.pragmatica.temper.domain.model;

import org.pragmatica.lang.Option;


/// A `Pushed` source after resolution: the SHA is always known, the ref only when the client gave one.
public record PushedSource(Option<Ref> ref, Sha sha) {
    public static PushedSource pushedSource(Option<Ref> ref, Sha sha) {
        return new PushedSource(ref, sha);
    }

    /// Source identity for the dedup key (spec §2.3): the SHA.
    public String identity() {
        return sha.value();
    }
}
