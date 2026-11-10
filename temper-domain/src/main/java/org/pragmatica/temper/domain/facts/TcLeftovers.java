package org.pragmatica.temper.domain.facts;

import java.util.List;

import org.pragmatica.lang.Option;


/// The Testcontainers reap at unit end (m0-design §12.4), **best-effort** (maintainer ruling, m1-design §14): the
/// Testcontainers containers of sessions that were new since the unit started and were still present when the reap
/// wait ended. That is not exactly the containers the unit spawned: its Ryuk and reusable containers carry no session
/// id and are never listed, and a session another process started during the unit's run can be listed
/// (`TestcontainersReap` states the bound). Leftovers are a flag only, never a verdict. `unverified` carries a reason
/// when the reap proves nothing: docker could not be asked (no containers), or a voided unit's window was cut short
/// (m1-design §1.4; the containers present when it ended). No containers and no `unverified` means none was found,
/// not that the unit left nothing behind.
public record TcLeftovers(List<TcLeftover> containers, Option<String> unverified) {
    public TcLeftovers {
        containers = List.copyOf(containers);
    }

    public static TcLeftovers tcLeftovers(List<TcLeftover> containers, Option<String> unverified) {
        return new TcLeftovers(containers, unverified);
    }

    /// One leftover container and its Testcontainers session id (empty if it had none). Not validated here: the
    /// worker takes ids from `docker ps --no-trunc` output, which are never blank, and the wire parser
    /// (`TcLeftoversDto`) rejects a blank id, degrading the whole reap to `unverified`.
    public record TcLeftover(String containerId, String sessionId) {
        public static TcLeftover tcLeftover(String containerId, String sessionId) {
            return new TcLeftover(containerId, sessionId);
        }
    }
}
