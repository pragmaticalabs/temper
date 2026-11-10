package org.pragmatica.temper.worker.unit;

/// The network a unit's containers run in, and the `TESTCONTAINERS_HOST_OVERRIDE` that matches it (spec §4.2,
/// m1-design §14 "Gate unit networking").
///
/// - A bridged unit runs in its own network `temper-<job>-<unit>`; Testcontainers inside reaches the containers it
///   starts through that network's IPv4 gateway.
/// - A Gate unit runs with `--network host` (maintainer ruling 2026-10-14, bb parity: on bb's host build `getHost()` is
///   `localhost`, and pragmatica's `AwsLocalStackContractTest` joins it to a container-internal port). No unit
///   network exists for it. The override is `localhost`, not absent: Testcontainers 1.21 resolves `getHost()` in
///   any container (`/.dockerenv` exists, host network or not) to the gateway of docker's `bridge` network, which
///   is not what bb resolves.
///
/// M2 note: host networking for a unit is accepted only because the unit already holds root-equivalent docker
/// access through the mounted socket; the M2 per-unit docker socket proxy denies `network=host` to the containers
/// the unit creates, never to the unit itself.
public record UnitNetwork(String name, String hostOverride) {
    /// A Gate unit's network: docker's `host`, with `localhost` (what a host-built test resolves) as the override.
    public static final UnitNetwork HOST = new UnitNetwork("host", "localhost");

    /// The per-unit bridge network `name`, with its IPv4 `gateway` as the override.
    public static UnitNetwork unitNetwork(String name, String gateway) {
        return new UnitNetwork(name, gateway);
    }
}
