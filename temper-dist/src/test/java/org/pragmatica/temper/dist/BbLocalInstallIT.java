package org.pragmatica.temper.dist;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// ops/bb/install-bb-temper-local.sh replaces the Mac's bb-temper by RENAME (CTO 2026-11-21): bash reads a running script incrementally,
/// so an in-place write splices two versions under a lane mid-invocation; a rename leaves running readers on the old inode.
/// Probe: replace the script's `mv -f "$tmp" "$dest"` with `cp "$tmp" "$dest"` -> the reader opened before the install sees the new
/// bytes, [#aReaderOpenedBeforeTheInstall_keepsReadingTheOldFile_theDestIsANewInode] red.
class BbLocalInstallIT {
    private static final String OLD = "#!/usr/bin/env bash\necho old version of the tool\n";
    private static final String NEW = "#!/usr/bin/env bash\necho NEW version, longer than the old one to show a splice\n";

    @TempDir
    Path dir;

    private BbFixtures.Outcome install(Path src, Path dest, String md5) throws Exception {
        return BbFixtures.run(List.of("bash", BbFixtures.OPS_BB.resolve("install-bb-temper-local.sh").toString(), src.toString(), dest.toString(), md5),
                              Map.of("PATH", System.getenv("PATH")),
                              dir,
                              null);
    }

    private String md5(String text) throws Exception {
        return BbFixtures.md5(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aReaderOpenedBeforeTheInstall_keepsReadingTheOldFile_theDestIsANewInode() throws Exception {
        var dest = Files.writeString(dir.resolve("bb-temper"), OLD);
        var src = Files.writeString(dir.resolve("new"), NEW);
        var inodeBefore = Files.getAttribute(dest, "unix:ino");

        try (var running = FileChannel.open(dest, StandardOpenOption.READ)) {
            var run = install(src, dest, md5(NEW));

            assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
            var buf = ByteBuffer.allocate(4096);

            running.read(buf, 0);
            assertThat(new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8))
                      .as("the bytes an in-flight bb-temper reads are the old file's, never a splice").isEqualTo(OLD);
        }
        assertThat(Files.getAttribute(dest, "unix:ino")).as("a rename: the dest is a new inode").isNotEqualTo(inodeBefore);
        assertThat(Files.readString(dest)).isEqualTo(NEW);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dest))).isEqualTo("rwxr-xr-x");
        assertThat(Files.readString(dir.resolve("bb-temper.bak-" + md5(OLD)))).as("the old file is kept as .bak-<md5>").isEqualTo(OLD);
        try (var left = Files.list(dir)) {
            assertThat(left.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".bb-temper.")).toList()).as("no temp file left").isEmpty();
        }
    }

    @Test
    void aSourceNotAtTheExpectedMd5_isRefused_andTheDestIsUntouched() throws Exception {
        var dest = Files.writeString(dir.resolve("bb-temper"), OLD);
        var src = Files.writeString(dir.resolve("new"), NEW);
        var inodeBefore = Files.getAttribute(dest, "unix:ino");
        var run = install(src, dest, md5("something else"));

        assertThat(run.rc()).isEqualTo(9);
        assertThat(run.stderr()).contains("refused: ", "untouched");
        assertThat(Files.readString(dest)).isEqualTo(OLD);
        assertThat(Files.getAttribute(dest, "unix:ino")).isEqualTo(inodeBefore);
        assertThat(dir.resolve("bb-temper.bak-" + md5(OLD))).doesNotExist();
    }

    @Test
    void aDestAlreadyAtTheExpectedMd5_isUnchanged_noBakNoRename() throws Exception {
        var dest = Files.writeString(dir.resolve("bb-temper"), NEW);
        var src = Files.writeString(dir.resolve("new"), NEW);
        var inodeBefore = Files.getAttribute(dest, "unix:ino");
        var run = install(src, dest, md5(NEW));

        assertThat(run.rc()).isZero();
        assertThat(run.stdout()).contains("unchanged " + dest);
        assertThat(Files.getAttribute(dest, "unix:ino")).isEqualTo(inodeBefore);
    }
}
