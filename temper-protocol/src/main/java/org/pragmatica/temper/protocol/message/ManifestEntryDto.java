package org.pragmatica.temper.protocol.message;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.Runner;

import com.fasterxml.jackson.annotation.JsonProperty;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Wire form of [ManifestEntry] (protocol v2): `{"module", "class", "runner"}`, `runner` being `surefire` or
/// `failsafe`. Also an entry of the `GET /jobs/{id}/manifest` view.
public record ManifestEntryDto(String module, @JsonProperty("class") String className, String runner) {
    public static final String SUREFIRE = "surefire";
    public static final String FAILSAFE = "failsafe";

    public static ManifestEntryDto manifestEntryDto(ManifestEntry entry) {
        return new ManifestEntryDto(entry.module(),
                                    entry.className(),
                                    switch (entry.runner()) {
            case SUREFIRE -> SUREFIRE;
            case FAILSAFE -> FAILSAFE;
        });
    }

    /// Wire input: `module` and `class` must be present, `runner` known.
    public Result<ManifestEntry> toDomain() {
        return Result.all(Verify.ensure(module,
                                        Verify.Is::present,
                                        invalid("manifest.module")),
                          Verify.ensure(className,
                                        Verify.Is::present,
                                        invalid("manifest.class")),
                          runnerOf(runner))
                     .map(ManifestEntry::manifestEntry);
    }

    private static Result<Runner> runnerOf(String raw) {
        return switch (raw) {
            case SUREFIRE -> success(Runner.SUREFIRE);
            case FAILSAFE -> success(Runner.FAILSAFE);
            case null, default -> invalid("manifest.runner").apply(raw).result();
        };
    }
}
