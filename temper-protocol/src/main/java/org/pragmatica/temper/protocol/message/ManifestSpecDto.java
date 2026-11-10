package org.pragmatica.temper.protocol.message;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Wire form of [ManifestSpec] (protocol v2, `StartUnit.manifest`, m1-design §2.2). `reach` is `test` or
/// `integration-test`.
public record ManifestSpecDto(List<String> pomArgv,
                              String reach,
                              Map<String, String> userProperties,
                              Option<String> test,
                              Option<String> itTest) {
    public static final String TEST = "test";
    public static final String INTEGRATION_TEST = "integration-test";

    public static ManifestSpecDto manifestSpecDto(ManifestSpec spec) {
        return new ManifestSpecDto(spec.pomArgv(),
                                   switch (spec.reach()) {
            case TEST -> TEST;
            case INTEGRATION_TEST -> INTEGRATION_TEST;
        },
                                   spec.userProperties(),
                                   spec.test(),
                                   spec.itTest());
    }

    /// Wire input from the controller: a non-empty `pomArgv` without null elements and a known `reach` are
    /// required; absent `userProperties` are none, and a null key or value fails.
    public Result<ManifestSpec> toDomain() {
        return Result.all(Verify.ensure(option(pomArgv).or(List.of()),
                                        ManifestSpecDto::isArgv,
                                        invalid("manifest.pomArgv")),
                          reachOf(reach),
                          Verify.ensure(option(userProperties).or(Map.of()),
                                        ManifestSpecDto::hasNoNulls,
                                        invalid("manifest.userProperties")))
                     .map((argv, parsed, properties) -> ManifestSpec.manifestSpec(argv, parsed, properties, test, itTest));
    }

    private static boolean isArgv(List<String> argv) {
        return ! argv.isEmpty() && argv.stream()
                                       .allMatch(Objects::nonNull);
    }

    private static boolean hasNoNulls(Map<String, String> properties) {
        return properties.entrySet()
                         .stream()
                         .allMatch(property -> Objects.nonNull(property.getKey()) && Objects.nonNull(property.getValue()));
    }

    private static Result<Reach> reachOf(String raw) {
        return switch (raw) {
            case TEST -> success(Reach.TEST);
            case INTEGRATION_TEST -> success(Reach.INTEGRATION_TEST);
            case null, default -> invalid("manifest.reach").apply(raw).result();
        };
    }
}
