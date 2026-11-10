package org.pragmatica.temper.controller.host;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.evidence.UnitEvidence;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.message.BuildVersion;
import org.pragmatica.temper.store.api.HostStore;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.UnitStore;


/// What every host link shares: process execution, the frame JSON mapper, the stores, the wake signal, the
/// settings sent in `StartUnit` (`version` is the expected worker version, the build version of D-6, and
/// [#runnerVersion] its `project.version` part;
/// `portGroupOwner` is bb-hold's owner tag, m1-design §1.2; `instance` labels the unit containers and scopes
/// reconcile, F2), how long a link may stay down before its host is LOST (`hostLost`, m4-design §2.1), and what happens to a unit's evidence when it ends
/// (m1-design §3), and who hears its UP/DOWN transitions (`signal`, the fleet stream; nobody by default).
public record LinkEnv(ProcessRunner runner,
                      JsonMapper mapper,
                      UnitStore units,
                      JobStore jobs,
                      HostStore hosts,
                      Wake wake,
                      String version,
                      String origin,
                      int cpuShares,
                      Option<String> mavenOpts,
                      Option<String> tcHubPrefix,
                      Duration lease,
                      Duration hostLost,
                      Path workerJar,
                      LinkTiming timing,
                      OwnerTag portGroupOwner,
                      InstanceId instance,
                      UnitEvidence evidence,
                      LinkSignal signal,
                      Supplier<Instant> clock) {
    public static LinkEnv linkEnv(ProcessRunner runner,
                                  JsonMapper mapper,
                                  UnitStore units,
                                  JobStore jobs,
                                  HostStore hosts,
                                  Wake wake,
                                  String version,
                                  String origin,
                                  int cpuShares,
                                  Option<String> mavenOpts,
                                  Option<String> tcHubPrefix,
                                  Duration lease,
                                  Duration hostLost,
                                  Path workerJar,
                                  LinkTiming timing,
                                  OwnerTag portGroupOwner,
                                  InstanceId instance,
                                  UnitEvidence evidence) {
        return new LinkEnv(runner,
                           mapper,
                           units,
                           jobs,
                           hosts,
                           wake,
                           version,
                           origin,
                           cpuShares,
                           mavenOpts,
                           tcHubPrefix,
                           lease,
                           hostLost,
                           workerJar,
                           timing,
                           portGroupOwner,
                           instance,
                           evidence,
                           LinkSignal.NONE,
                           Instant::now);
    }

    /// The same environment, its links' UP and DOWN transitions heard by `signal` (the fleet stream).
    public LinkEnv withSignal(LinkSignal signal) {
        return new LinkEnv(runner,
                           mapper,
                           units,
                           jobs,
                           hosts,
                           wake,
                           version,
                           origin,
                           cpuShares,
                           mavenOpts,
                           tcHubPrefix,
                           lease,
                           hostLost,
                           workerJar,
                           timing,
                           portGroupOwner,
                           instance,
                           evidence,
                           signal,
                           clock);
    }

    /// The same environment, its links judging retirement (`retireAt`, #103) by `clock` instead of the wall clock. The
    /// liveness clocks (down time, silence, `stableUp`) stay on the wall clock: they measure elapsed time.
    public LinkEnv withClock(Supplier<Instant> clock) {
        return new LinkEnv(runner,
                           mapper,
                           units,
                           jobs,
                           hosts,
                           wake,
                           version,
                           origin,
                           cpuShares,
                           mavenOpts,
                           tcHubPrefix,
                           lease,
                           hostLost,
                           workerJar,
                           timing,
                           portGroupOwner,
                           instance,
                           evidence,
                           signal,
                           clock);
    }

    /// The runner version sent in `StartUnit`: the `project.version` part of [#version], because it becomes a docker tag
    /// (`temper/runner:<version>`), where the build version's `+` is not allowed. A rebuilt runner context of the same
    /// tag is still rebuilt on the worker, by its content hash label.
    public String runnerVersion() {
        return BuildVersion.projectVersion(version);
    }
}
