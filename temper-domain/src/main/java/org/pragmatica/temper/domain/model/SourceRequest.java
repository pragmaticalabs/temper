package org.pragmatica.temper.domain.model;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// What the client asked to build, before the controller resolves it. M0 accepts only `Pushed`; the other
/// spec source kinds parse into [Unsupported] so that submit can refuse them as `REFUSED(unsupported)`.
public sealed interface SourceRequest {
    /// `{pushed: {ref}}`: resolved with `git ls-remote` inside the submit call.
    record ByRef(Ref ref) implements SourceRequest {}

    /// `{pushed: {sha}}`: must be contained in a remote head.
    record BySha(Sha sha) implements SourceRequest {}

    /// `bundle` or `patch` (M2).
    record Unsupported(String kind) implements SourceRequest {}

    /// A rerun job's source (m2-design §5.2): the parent's resolved SHA, taken as is and not checked against origin
    /// again [G], since the parent already passed the check. No client sends it: the controller builds it from the
    /// parent's row. `sha` is absent only for a parent refused before resolution, whose rerun request is refused.
    record Inherited(JobId parent, Option<Sha> sha) implements SourceRequest {}

    static Result<SourceRequest> byRef(String rawRef) {
        return Ref.ref(rawRef).map(ByRef::new);
    }

    static Result<SourceRequest> bySha(String rawSha) {
        return Sha.sha(rawSha).map(BySha::new);
    }

    static SourceRequest unsupported(String kind) {
        return new Unsupported(kind);
    }

    static SourceRequest inherited(JobId parent, Option<Sha> sha) {
        return new Inherited(parent, sha);
    }
}
