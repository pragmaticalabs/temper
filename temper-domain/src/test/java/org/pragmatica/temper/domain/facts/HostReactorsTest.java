package org.pragmatica.temper.domain.facts;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A7 (pure part): a launcher inside a temper container is counted once, via the active units, not twice.
class HostReactorsTest {
    private static final String ID = "4f3c2b1a" + "0".repeat(48) + "deadbeef";
    private static final String MVN = "/opt/java/openjdk/bin/java -classpath /opt/maven/boot/plexus-classworlds.jar "
                                      + HostReactors.LAUNCHER + " -B test";

    private static final ProcInfo TEMPER_LAUNCHER = ProcInfo.procInfo(100, MVN, "0::/system.slice/docker-" + ID + ".scope");
    private static final ProcInfo FOREIGN_LAUNCHER = ProcInfo.procInfo(200, MVN, "0::/user.slice/user-1000.slice/session-3.scope");
    private static final ProcInfo NOT_A_LAUNCHER = ProcInfo.procInfo(300, "/usr/bin/java -jar worker.jar", "0::/user.slice");

    @Test
    void total_isOne_forOneTemperUnitAndNoForeignReactors() {
        var foreign = HostReactors.foreign(List.of(TEMPER_LAUNCHER, NOT_A_LAUNCHER), Set.of(ID));

        assertThat(foreign).isZero();
        assertThat(HostReactors.total(foreign, 1)).isEqualTo(1);
    }

    @Test
    void total_isTwo_withOneForeignLauncher() {
        var foreign = HostReactors.foreign(List.of(TEMPER_LAUNCHER, FOREIGN_LAUNCHER), Set.of(ID));

        assertThat(HostReactors.total(foreign, 1)).isEqualTo(2);
    }

    @Test
    void foreign_recognisesCgroupfsDriverPath() {
        var cgroupfs = ProcInfo.procInfo(400, MVN, "0::/docker/" + ID);

        assertThat(HostReactors.foreign(List.of(cgroupfs), Set.of(ID))).isZero();
    }

    @Test
    void foreign_ignoresBlankOrShortIds_soTheyCannotHideLaunchers() {
        assertThat(HostReactors.foreign(List.of(FOREIGN_LAUNCHER, TEMPER_LAUNCHER), Set.of("", "docker", ID.substring(0, 12))))
            .isEqualTo(2);
    }

    @Test
    void foreign_countsEveryLauncher_whenNoTemperContainersRun() {
        assertThat(HostReactors.foreign(List.of(TEMPER_LAUNCHER, FOREIGN_LAUNCHER), Set.of())).isEqualTo(2);
    }
}
