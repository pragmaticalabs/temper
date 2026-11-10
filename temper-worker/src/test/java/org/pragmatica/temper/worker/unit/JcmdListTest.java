package org.pragmatica.temper.worker.unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JcmdListTest {
    @Test
    void pids_listsEveryJvmExceptJcmdItself() {
        var output = """
            1 RunSteps 6 mvn -o verify
            57 org.codehaus.plexus.classworlds.launcher.Launcher -o verify
            212 /ws/core/target/surefire/surefirebooter123.jar /ws/core/target/surefire
            318 jdk.jcmd/sun.tools.jcmd.JCmd -l
            319 sun.tools.jcmd.JCmd -l
            not a line
            400
            """;

        assertThat(JcmdList.pids(output)).containsExactly(1L, 57L, 212L, 400L);
    }

    @Test
    void pids_isEmptyForNoOutput() {
        assertThat(JcmdList.pids("")).isEmpty();
    }
}
