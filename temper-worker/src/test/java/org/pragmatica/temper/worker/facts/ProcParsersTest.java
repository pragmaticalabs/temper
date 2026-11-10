package org.pragmatica.temper.worker.facts;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.worker.ProcFixtures;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;

class ProcParsersTest {
    @Test
    void loadAverage_readsLoad1AndLoad5() {
        var load = ok(ProcParsers.loadAverage("0.52 1.58 0.59 1/389 12345\n"));

        assertThat(load.load1()).isEqualTo(0.52);
        assertThat(load.load5()).isEqualTo(1.58);
        failure(ProcParsers.loadAverage(""));
        failure(ProcParsers.loadAverage("x y z"));
    }

    @Test
    void memAvailableKb_readsTheMemAvailableLine() {
        var meminfo = "MemTotal:       65802288 kB\nMemFree:         1234 kB\nMemAvailable:   41234567 kB\nBuffers: 1 kB\n";

        assertThat(ok(ProcParsers.memAvailableKb(meminfo))).isEqualTo(41234567L);
        failure(ProcParsers.memAvailableKb("MemTotal: 1 kB\n"));
    }

    @Test
    void cmdline_joinsNulSeparatedArgv() {
        var raw = "java\0-cp\0x\0org.codehaus.plexus.classworlds.launcher.Launcher\0".getBytes(StandardCharsets.UTF_8);

        assertThat(ProcParsers.cmdline(raw)).isEqualTo("java -cp x org.codehaus.plexus.classworlds.launcher.Launcher");
    }

    @Test
    void cpuStat_readsKeyValueLines_skippingJunk() {
        assertThat(ProcParsers.cpuStat("usage_usec 123\nuser_usec 100\nbad line here\nsystem_usec x\n")).isEqualTo(Map.of("usage_usec",
                                                                                                                   123L,
                                                                                                                   "user_usec",
                                                                                                                   100L));
    }

    @Test
    void memoryPeak_readsOneInteger() {
        assertThat(ProcParsers.memoryPeak("4096\n")).isEqualTo(Option.some(4096L));
        assertThat(ProcParsers.memoryPeak("max")).isEqualTo(Option.none());
    }

    /// Field 22 is counted after the LAST `)`: a command name may hold spaces and parentheses.
    @Test
    void startTicks_readsField22_afterTheCommandName() {
        assertThat(ProcParsers.startTicks(ProcFixtures.stat(4242, 987654))).isEqualTo(org.pragmatica.lang.Option.some(987654L));
        assertThat(ProcParsers.startTicks("4242 (sh) S 1 2 3")).isEqualTo(org.pragmatica.lang.Option.none());
        assertThat(ProcParsers.startTicks("garbage")).isEqualTo(org.pragmatica.lang.Option.none());
    }
}
