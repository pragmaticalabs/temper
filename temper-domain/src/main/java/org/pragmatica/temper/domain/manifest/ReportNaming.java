package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;


/// How a test plugin names a class in `<testcase classname>` (m1-design §2.4 rule 5), decided by the plugin's
/// version alone: surefire's JUnit Platform provider (`RunListenerAdapter`) of that version writes it, failsafe of
/// one version uses the same provider, and JUnit 5.11.4 and 5.13.4 report alike (measured under 3.5.2, 3.5.3 and
/// 3.5.4): the provider reads `@DisplayName` by reflection, not through a platform API.
///
/// Measured over the oracle fixture's `display` sources, surefire and failsafe each (T1 2026-10-14 run 2):
///
/// | Version | `classname` |
/// |---|---|
/// | 3.0.0 – 3.5.3 | the binary name (measured 3.0.0, 3.1.2, 3.2.5, 3.4.0, 3.5.0 – 3.5.3) |
/// | 3.5.4 – 3.5.6 | the class's own `@DisplayName` value, else the binary name (3.5.4 added `findDisplayNameTagValue`) |
/// | 3.6.0 | the binary name again |
///
/// Any other version is [ManifestError.UnsupportedPluginVersion], never guessed: an absent or unparseable one, a
/// qualified one (`3.0.0-M7`), an older one (2.22.2 runs no Jupiter 5.13 test at all) and a newer one. The naming
/// changed twice within four patch releases, and a wrong guess reads as missing and drift against a green build, a
/// false RED; `ERROR(manifest: <reason>)` names the version instead, and a new oracle run extends the table.
public enum ReportNaming {
    /// The binary name (`Outer$Inner` for a `@Nested` class).
    BINARY_NAME,
    /// The class's own `@DisplayName` value, raw, when it carries one; else the binary name.
    OWN_DISPLAY_NAME;
    /// A plain release version, each part at most three digits.
    private static final Pattern RELEASE = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    /// What the error names for a plugin the effective POM gives no version.
    private static final String UNVERSIONED = "(no version)";
    /// One measured range of versions, both ends included, as [#ordinal(int, int, int)] numbers.
    record Band(int from, int to, ReportNaming naming) {
        static Band band(int from, int to, ReportNaming naming) {
            return new Band(from, to, naming);
        }

        boolean contains(int version) {
            return from <= version && version <= to;
        }
    }
    private static final List<Band> BANDS = List.of(Band.band(ordinal(3, 0, 0), ordinal(3, 5, 3), BINARY_NAME),
                                                    Band.band(ordinal(3, 5, 4), ordinal(3, 5, 6), OWN_DISPLAY_NAME),
                                                    Band.band(ordinal(3, 6, 0), ordinal(3, 6, 0), BINARY_NAME));
    /// The naming of `runner` at `version` in `module`; unsupported outside the measured bands.
    static Result<ReportNaming> reportNaming(String module, Runner runner, Option<String> version) {
        return version.flatMap(ReportNaming::measured)
                      .toResult(ManifestError.UnsupportedPluginVersion.FACTORY.apply(module,
                                                                                     runner.artifactId(),
                                                                                     version.or(UNVERSIONED)));
    }
    private static Option<ReportNaming> measured(String version) {
        return release(version).flatMap(ReportNaming::bandOf);
    }
    private static Option<ReportNaming> bandOf(int version) {
        return Option.from(BANDS.stream().filter(band -> band.contains(version)).map(Band::naming).findFirst());
    }
    /// `major.minor.patch` as one comparable number; none for anything else.
    private static Option<Integer> release(String version) {
        var matcher = RELEASE.matcher(version);

        return matcher.matches()
               ? parsedOrdinal(matcher.group(1), matcher.group(2), matcher.group(3))
               : none();
    }
    /// Design-out, not an absorbed failure: the pattern admits one to three digits per part, which `parseInt` always
    /// parses, so `option()` drops no failure that can occur.
    private static Option<Integer> parsedOrdinal(String major, String minor, String patch) {
        return Result.all(Number.parseInt(major),
                          Number.parseInt(minor),
                          Number.parseInt(patch))
                     .map(ReportNaming::ordinal)
                     .option();
    }
    private static int ordinal(int major, int minor, int patch) {
        return (major * 1000 + minor) * 1000 + patch;
    }
}
