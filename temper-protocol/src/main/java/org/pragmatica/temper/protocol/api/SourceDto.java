package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.SourceRequest;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static org.pragmatica.temper.domain.DomainError.invalid;
import static org.pragmatica.lang.Result.success;


/// Submit `source` (spec §2.1): `{"pushed": {"ref": ...}}`, `{"pushed": {"sha": ...}}`, or `bundle`/`patch`,
/// which parse into `SourceRequest.Unsupported` so submit refuses them as `REFUSED(unsupported)` in M0.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = SourceDto.Pushed.class, name = "pushed"), @JsonSubTypes.Type(value = SourceDto.Bundle.class, name = "bundle"), @JsonSubTypes.Type(value = SourceDto.Patch.class, name = "patch")})
public sealed interface SourceDto {
    Result<SourceRequest> toDomain();

    /// Exactly one of `ref` and `sha`.
    record Pushed(Option<String> ref, Option<String> sha) implements SourceDto {
        @Override
        public Result<SourceRequest> toDomain() {
            return Verify.ensure(this,
                                 Pushed::exactlyOne,
                                 invalid("source.pushed (exactly one of ref, sha)"))
                         .flatMap(Pushed::request);
        }

        private boolean exactlyOne() {
            return ref.isPresent() != sha.isPresent();
        }

        private Result<SourceRequest> request() {
            return ref.map(SourceRequest::byRef)
                      .or(() -> SourceRequest.bySha(sha.or("")));
        }
    }

    /// `{blobId, sha}` (M2).
    record Bundle(Option<String> blobId, Option<String> sha) implements SourceDto {
        @Override
        public Result<SourceRequest> toDomain() {
            return success(SourceRequest.unsupported("bundle"));
        }
    }

    /// `{baseSha, diff}` (M2).
    record Patch(Option<String> baseSha, Option<String> diff) implements SourceDto {
        @Override
        public Result<SourceRequest> toDomain() {
            return success(SourceRequest.unsupported("patch"));
        }
    }
}
