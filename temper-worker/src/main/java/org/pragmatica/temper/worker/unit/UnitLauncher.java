package org.pragmatica.temper.worker.unit;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.worker.docker.DockerCli;


/// How a unit's container is started (m1-design §1.2). A direct launch returns once `docker run -d` has; a
/// port-group launch returns once bb-hold is spawned and its ticket enqueued, before any container exists (F1),
/// so the result says which of the two happened.
@FunctionalInterface
public interface UnitLauncher {
    Promise<Launch> launch(UnitLaunch launch);

    /// What a launcher produced.
    sealed interface Launch {
        /// `docker run -d` returned this container id.
        record Running(String containerId) implements Launch {}

        /// bb-hold runs detached with this identity and waits for the port group; the container follows the grant.
        /// `exit` completes with bb-hold's rc when this JVM sees it exit (a later worker cannot: it classifies from
        /// `bbhold.err` instead).
        record Holding(HolderRecord holder, Promise<Integer> exit) implements Launch {}

        static Launch running(String containerId) {
            return new Running(containerId);
        }

        static Holding holding(HolderRecord holder, Promise<Integer> exit) {
            return new Holding(holder, exit);
        }
    }

    /// `docker run -d` with the pinned argv (the M0 behaviour).
    static UnitLauncher direct(DockerCli docker) {
        return launch -> directLaunch(docker, launch);
    }

    private static Promise<Launch> directLaunch(DockerCli docker, UnitLaunch launch) {
        return docker.run(DockerRunArgs.build(launch))
                     .map(Launch::running);
    }

    /// A unit with `portGroup` goes through `hold`, every other unit through `direct`.
    static UnitLauncher routed(UnitLauncher direct, PortGroupLauncher hold) {
        return launch -> routedLaunch(direct, hold, launch);
    }

    private static Promise<Launch> routedLaunch(UnitLauncher direct, PortGroupLauncher hold, UnitLaunch launch) {
        return launch.request()
                     .portGroup()
                     .map(request -> held(hold, launch, request))
                     .or(() -> direct.launch(launch));
    }

    private static Promise<Launch> held(PortGroupLauncher hold, UnitLaunch launch, HoldRequest request) {
        return hold.enqueue(launch, request)
                   .map(Launch.class::cast);
    }
}
