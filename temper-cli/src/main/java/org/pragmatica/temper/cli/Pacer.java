package org.pragmatica.temper.cli;

import java.time.Duration;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Promise.promise;


/// Waiting between polls and retries, injectable so tests run without real delays.
@FunctionalInterface
public interface Pacer {
    Promise<Unit> pause(Duration duration);

    /// Resolves after `duration` on core's scheduler.
    static Pacer pacer() {
        return duration -> promise(TimeSpan.fromDuration(duration), Result::unitResult);
    }
}
