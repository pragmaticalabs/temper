package org.pragmatica.temper.domain.manifest.read;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.temper.domain.manifest.ManifestError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// The class-file reader's failure path; its success path is exercised by every synthesised fixture.
class ClassInfosTest {
    @Test
    void read_failsOnBytesThatAreNotAClassFile() {
        Cause failure = ClassInfos.read("a/Junk.class", new byte[] {1, 2, 3}).fold(cause -> cause, _ -> fail("expected a failure"));

        assertThat(failure).isInstanceOfSatisfying(ManifestError.MalformedClass.class,
                                                   error -> assertThat(error.message()).isEqualTo("Malformed class file a/Junk.class"));
    }

    @Test
    void read_failsOnATruncatedClassFile() {
        var bytes = ClassFile.of().build(ClassDesc.of("a.FooTest"), _ -> {});
        var truncated = Arrays.copyOf(bytes, bytes.length / 2);

        Cause failure = ClassInfos.read("a/FooTest.class", truncated).fold(cause -> cause, _ -> fail("expected a failure"));

        assertThat(failure).isInstanceOf(ManifestError.MalformedClass.class);
    }

    @Test
    void read_namesAPlainClass() {
        var bytes = ClassFile.of().build(ClassDesc.of("a.b.FooTest"), _ -> {});

        assertThat(ClassInfos.read("a/b/FooTest.class", bytes).map(info -> info.binaryName() + "|" + info.superName().or("-") + "|" + info.outerName().or("-")))
            .matches(result -> result.isSuccess())
            .satisfies(result -> result.onSuccess(text -> assertThat(text).isEqualTo("a.b.FooTest|java.lang.Object|-")));
    }
}
