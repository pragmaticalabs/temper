package org.pragmatica.temper.worker.unit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

class LogFilesTest {
    @Test
    void lastLines_keepsTheLastLines_andMarksTruncation() {
        assertThat(LogFiles.lastLines("a\nb\nc\n", false, 2)).isEqualTo(LogFiles.LogText.logText("b\nc\n", true));
        assertThat(LogFiles.lastLines("a\nb\nc", false, 2)).isEqualTo(LogFiles.LogText.logText("b\nc", true));
        assertThat(LogFiles.lastLines("a\nb\nc\n", false, 5)).isEqualTo(LogFiles.LogText.logText("a\nb\nc\n", false));
        assertThat(LogFiles.lastLines("a\nb\nc\n", false, 3)).isEqualTo(LogFiles.LogText.logText("a\nb\nc\n", false));
        assertThat(LogFiles.lastLines("a\nb\n", false, 0)).isEqualTo(LogFiles.LogText.logText("", true));
        assertThat(LogFiles.lastLines("", false, 3)).isEqualTo(LogFiles.LogText.logText("", false));
    }

    @Test
    void lastLines_dropsThePartialFirstLine_whenCutMidFile() {
        assertThat(LogFiles.lastLines("tial\nb\nc\n", true, 10)).isEqualTo(LogFiles.LogText.logText("b\nc\n", true));
    }

    @Test
    void tail_readsOnlyTheEndOfALargeFile(@TempDir Path dir) throws IOException {
        var log = dir.resolve("log");
        var text = new StringBuilder();

        for (var line = 0; line < 1000; line++) {
            text.append("line ")
                .append(line)
                .append('\n');
        }

        Files.writeString(log, text);

        var tail = ok(LogFiles.tail(log, 3, 100));

        assertThat(tail.text()).isEqualTo("line 997\nline 998\nline 999\n");
        assertThat(tail.truncated()).isTrue();

        var small = ok(LogFiles.tail(log, 2000, 1024 * 1024));

        assertThat(small.text()).isEqualTo(text.toString());
        assertThat(small.truncated()).isFalse();
    }

    @Test
    void keepTail_capsToTheLastBytes(@TempDir Path dir) throws IOException {
        var log = dir.resolve("log");

        Files.writeString(log, "0123456789");
        ok(LogFiles.keepTail(log, 4));
        assertThat(Files.readString(log, StandardCharsets.UTF_8)).isEqualTo("6789");

        ok(LogFiles.keepTail(log, 100));
        assertThat(Files.readString(log)).isEqualTo("6789");
        assertThat(Files.exists(dir.resolve("log.cap"))).isFalse();
    }
}
