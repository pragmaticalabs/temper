package org.pragmatica.temper.domain.facts;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;


/// Host reactor counting (spec §6.1, A7). Processes inside temper containers are visible in the host's
/// process table, so counting every Maven launcher would count each temper unit twice. Instead:
/// host reactors = launchers whose cgroup is NOT inside a temper container + temper's active units.
///
/// Matching is "the cgroup line contains the full 64-hex container id", which covers both the systemd
/// driver (`docker-<id>.scope`) and the cgroupfs driver (`/docker/<id>`).
public sealed interface HostReactors {
    /// Main class of the Maven launcher (classworlds); one per running reactor.
    String LAUNCHER = "org.codehaus.plexus.classworlds.launcher.Launcher";
    /// A full docker container id.
    Pattern CONTAINER_ID = Pattern.compile("^[0-9a-f]{64}$");

    /// Launcher processes not inside any temper container.
    static int foreign(List<ProcInfo> procs, Set<String> temperContainerIds) {
        return (int) procs.stream()
                          .filter(proc -> proc.cmdline()
                                              .contains(LAUNCHER))
                          .filter(proc -> outsideTemper(proc, temperContainerIds))
                          .count();
    }

    /// A blank or short id would be contained in every cgroup line and hide every launcher, so only full
    /// 64-hex container ids are matched; anything else is ignored (which can only over-count, the safe side).
    private static boolean outsideTemper(ProcInfo proc, Set<String> temperContainerIds) {
        return temperContainerIds.stream()
                                 .filter(id -> CONTAINER_ID.matcher(id).matches())
                                 .noneMatch(proc.cgroup()::contains);
    }

    /// Host reactors as the cap rule counts them.
    static int total(int foreign, int temperActiveUnits) {
        return foreign + temperActiveUnits;
    }

    record unused() implements HostReactors {}
}
