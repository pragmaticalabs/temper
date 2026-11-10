package org.pragmatica.temper.protocol.message;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.facts.TcLeftovers;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Wire form of [TcLeftovers] (protocol v1 addition, m0-design §12.7): `{"containers": [{"id", "session"}],
/// "unverified": reason-or-null}`.
public record TcLeftoversDto(List<ContainerDto> containers, Option<String> unverified) {
    /// A worker that sent no reap at all: the reap is not verified, never reported clean.
    public static final String NOT_REPORTED = "not reported by the worker";
    /// Prefix of the reason when the worker's leftovers do not parse.
    public static final String UNREADABLE = "unreadable leftovers: ";

    public static TcLeftoversDto tcLeftoversDto(TcLeftovers leftovers) {
        return new TcLeftoversDto(leftovers.containers().stream().map(ContainerDto::containerDto).toList(),
                                  leftovers.unverified());
    }

    /// Wire input: an absent container list is empty; a container needs its `id`.
    public Result<TcLeftovers> toDomain() {
        return Result.allOf(option(containers).or(List.of()).stream().map(ContainerDto::toDomain).toList()).map(list -> TcLeftovers.tcLeftovers(list,
                                                                                                                                                unverified));
    }

    /// One leftover container: its id and Testcontainers session id.
    public record ContainerDto(String id, String session) {
        public static ContainerDto containerDto(TcLeftovers.TcLeftover leftover) {
            return new ContainerDto(leftover.containerId(), leftover.sessionId());
        }

        public Result<TcLeftovers.TcLeftover> toDomain() {
            return Verify.ensure(id, Verify.Is::present, invalid("tcLeftovers.containers.id")).map(present -> TcLeftovers.TcLeftover.tcLeftover(present,
                                                                                                                                                option(session).or("")));
        }
    }
}
