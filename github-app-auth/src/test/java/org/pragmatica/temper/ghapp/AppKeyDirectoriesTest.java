package org.pragmatica.temper.ghapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// The directories above a key, the same rule as the wrappers': the key's real directory and every
/// directory up to and including the home must not be group- or other-writable. Symbolic links among the directories
/// are resolved first. Mutation for the walk: check only the key's own directory, or `toRealPath` dropped.
class AppKeyDirectoriesTest {
    @TempDir
    Path dir;

    private Path home;
    private KeyPair pair;

    @BeforeEach
    void setUp() throws IOException {
        home = directory(dir.resolve("home"), "rwx------");
        pair = TestKeys.generate();
    }

    private static Path directory(Path path, String mode) throws IOException {
        Files.createDirectories(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));

        return path;
    }

    private Path key(Path directory) throws IOException {
        return TestKeys.write(directory.resolve("k.pem"), TestKeys.pkcs8Pem(pair), "rw-------");
    }

    private Cause refusal(Path key) {
        return AppKey.load(key, home).fold(cause -> cause, loaded -> null);
    }

    private Result<?> loaded(Path key) {
        return AppKey.load(key, home);
    }

    private void assertRefused(Path key, String directory) {
        var cause = refusal(key);

        assertThat(cause).isInstanceOf(AppAuthError.KeyFileUnsafe.class);
        assertThat(cause.message()).contains("directory " + directory + " is group- or other-writable");
        assertThat(((AppAuthError.KeyFileUnsafe) cause).exitCode()).isEqualTo(78);
    }

    @Test
    void worldWritableGrandparent_isRefused() throws IOException {
        var grand = directory(home.resolve("grand"), "rwx------");
        var key = key(directory(grand.resolve("keys"), "rwx------"));

        assertThat(refusal(key)).isNull();  // control: the same tree, safe
        Files.setPosixFilePermissions(grand, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertRefused(key,
                      grand.toRealPath().toString());
    }

    @Test
    void groupWritableHome_isRefused_thoughNothingAboveItIsChecked() throws IOException {
        var key = key(directory(home.resolve("keys"), "rwx------"));

        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwx---"));
        assertRefused(key,
                      home.toRealPath().toString());
        // control: `dir` above the home is world-writable and does not matter
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThat(refusal(key)).isNull();
    }

    @Test
    void symlinkedParent_whoseTargetIsWorldWritable_isRefused() throws IOException {
        var target = directory(dir.resolve("elsewhere").resolve("keys"),
                               "rwxrwxrwx");

        key(target);
        var link = Files.createSymbolicLink(home.resolve("link"), target);

        assertRefused(link.resolve("k.pem"),
                      target.toRealPath().toString());
    }

    @Test
    void symlinkedParent_whoseTargetIsSafe_isAccepted() throws IOException {
        var target = directory(dir.resolve("elsewhere").resolve("keys"),
                               "rwx------");

        key(target);
        var link = Files.createSymbolicLink(home.resolve("link"), target);
        boolean ok = loaded(link.resolve("k.pem")).fold(cause -> false, loaded -> true);

        assertThat(ok).isTrue();
    }

    @Test
    void symlinkedParent_whoseTargetSitsUnderAWritableDirectoryInHome_isRefused() throws IOException {
        var grand = directory(home.resolve("grand"), "rwxrwxrwx");
        var target = directory(grand.resolve("keys"), "rwx------");

        key(target);
        var link = Files.createSymbolicLink(home.resolve("link"), target);

        assertRefused(link.resolve("k.pem"),
                      grand.toRealPath().toString());
    }
}
