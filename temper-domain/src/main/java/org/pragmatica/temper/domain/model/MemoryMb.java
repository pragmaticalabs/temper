package org.pragmatica.temper.domain.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// Container memory limit in MiB.
public record MemoryMb(int value) {
    public static final int MIN = 512;
    public static final int MAX = 65536;
    /// The Gate unit's limit until the sizing report exists (maintainer ruling on Q-M1-13, m1-design §13): 16 GiB.
    public static final MemoryMb GATE_DEFAULT = new MemoryMb(16384);

    public static Result<MemoryMb> memoryMb(int raw) {
        return Verify.ensure(raw, Verify.Is::between, MIN, MAX, invalid("memory")).map(MemoryMb::new);
    }
}
