package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;

import org.pragmatica.lang.Option;


/// Everything `docker run` of one unit is built from (m0-design §7.4 step 8). Each field is a value the worker
/// derived or validated; nothing here comes from the worker's own environment, which is how R-ENV holds even
/// when that environment carries secrets.
///
/// - `workspace` is mounted at the same path inside the container and is the working directory.
/// - `home` is the container `HOME` (`<ws>/.home`).
/// - `dockerSocket` is the host socket mounted at `/var/run/docker.sock`.
/// - `network` is the unit's network and its `TESTCONTAINERS_HOST_OVERRIDE` ([UnitNetwork]).
public record UnitLaunch(StartRequest request,
                         Path workspace,
                         Path home,
                         int uid,
                         int gid,
                         int dockerGid,
                         Path dockerSocket,
                         UnitNetwork network) {
    public static UnitLaunch unitLaunch(StartRequest request,
                                        Path workspace,
                                        Path home,
                                        int uid,
                                        int gid,
                                        int dockerGid,
                                        Path dockerSocket,
                                        UnitNetwork network) {
        return new UnitLaunch(request, workspace, home, uid, gid, dockerGid, dockerSocket, network);
    }

    public Option<String> mavenOpts() {
        return request.mavenOpts();
    }

    public Option<String> tcHubPrefix() {
        return request.tcHubPrefix();
    }
}
