package org.pragmatica.temper.controller.http;

/// Which credential authorised a request ([Credentials#classify]). The full token calls every route but the lease-only
/// ones; the read token calls READ routes only; the worker token (ruling R-M3-15) READ routes and `claim`; a lease token
/// (`Authorization: Lease <token>`, m3-design §6.4) only its own task's lease verbs, whose store fence decides whether
/// the token is the task's current one. The verdict token (`[server] verdictTokenFile`) calls the
/// VERDICT routes only, and the Mac token (`[server] macTokenFile`) the MAC routes only; the full token calls neither
/// (design §4.3: "the full token gets 403"), so a merge or a verdict needs the verdict token's holder.
public enum Access {
    FULL,
    READ,
    WORKER,
    LEASE,
    VERDICT,
    MAC;
    public boolean permits(Route.Scope scope) {
        return switch (this) {
            case FULL -> scope != Route.Scope.LEASE && scope != Route.Scope.VERDICT && scope != Route.Scope.MAC;
            case READ -> scope == Route.Scope.READ;
            case WORKER -> scope == Route.Scope.READ || scope == Route.Scope.WORKER;
            case LEASE -> scope == Route.Scope.LEASE || scope == Route.Scope.LEASE_OR_WRITE;
            case VERDICT -> scope == Route.Scope.VERDICT;
            case MAC -> scope == Route.Scope.MAC;
        };
    }
}
