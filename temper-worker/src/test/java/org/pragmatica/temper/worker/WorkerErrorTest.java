package org.pragmatica.temper.worker;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.DomainError;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerErrorTest {
    @Test
    void unexpectedOutput_keepsTheOutputAsData_outOfTheMessage() {
        var cause = WorkerError.UnexpectedOutput.FACTORY.apply("wait", "secret host detail");

        assertThat(cause.message()).isEqualTo("Unexpected output of wait")
                                   .doesNotContain("secret");
        assertThat(cause.output()).isEqualTo("secret host detail");
    }

    @Test
    void code_mapsEachFailureFamily_andCompositesByTheirFirstMember() {
        assertThat(WorkerError.code(WorkerError.SymlinkedSeed.FACTORY.apply(1, List.of("a")))).isEqualTo("seed-isolation");
        assertThat(WorkerError.code(WorkerError.ContainerLost.FACTORY.apply("c"))).isEqualTo("lost");
        assertThat(WorkerError.code(WorkerError.Io.FACTORY.apply("x", Causes.cause("y")))).isEqualTo("worker");
        assertThat(WorkerError.code(WorkerError.CorruptState.FACTORY.apply(Path.of("f"), Causes.cause("y")))).isEqualTo("worker");

        var composite = Result.all(DomainError.InvalidValue.FACTORY.apply("job", "0").result(),
                                   Result.success(1))
                              .map((a, b) -> a);

        assertThat(WorkerError.code(composite.fold(cause -> cause, _ -> null))).isEqualTo("bad-request");
    }
}
