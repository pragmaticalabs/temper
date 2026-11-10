package org.pragmatica.temper.controller.config;

import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.StreamOps;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.protocol.message.BuildVersion;

import static org.pragmatica.lang.utils.Causes.cause;


/// The controller version comes from the build (m0-design §12.7 item 3): Maven filters `${project.version}` and
/// `${temper.judge}` into [#RESOURCE], and [BuildVersion] derives `<project.version>+<judge12>` from them (D-6), so the
/// expected worker version is exactly the build that made this jar, never a hand-typed value. The worker derives its
/// own version the same way (`worker-version.properties`), so the controller and worker jars of one build agree, and
/// a worker of another commit of the same `project.version` is `Incompatible`. The runner version stays the bare
/// `project.version`; the dedup key's runner component is the whole build version ([ControllerConfig#dedupRunner]).
///
/// The judge (m2-design §1.7, ruling R-M2-10) comes from the same resource: Maven filters `${temper.judge}` into its
/// `judge=` line, the commit `ops/build-dist.sh` passes as `-Dtemper.judge`, or the root pom's `unjudged` default.
public sealed interface ControllerVersion {
    String RESOURCE = "/org/pragmatica/temper/controller/controller-version.properties";
    /// The judges a controller may start as: a commit (clean or `-dirty`) or `unjudged` (dev and test builds). The
    /// deploy refuses all but a clean commit (`ops/deploy-jars.sh`); `pre-judge` names old rows, never a build.
    Pattern STARTABLE_JUDGE = BuildVersion.JUDGE;

    /// The bundled build version; [BuildVersion] keeps it inside the worker-launch version alphabet
    /// ([ConfigLoader#VERSION]).
    static Result<String> bundled() {
        return StreamOps.readResource(ControllerVersion.class, RESOURCE)
                        .mapError(origin -> ConfigError.Unreadable.FACTORY.apply(RESOURCE, origin))
                        .flatMap(ControllerVersion::derived);
    }

    /// The build version of the filtered resource's `version=` and `judge=` lines; a missing or unfiltered line fails
    /// start, as does a value [BuildVersion] refuses.
    static Result<String> derived(String properties) {
        return Result.all(versionOf(properties).toResult(ConfigError.General.NO_VERSION),
                          judgeOf(properties).toResult(ConfigError.General.NO_JUDGE))
                     .flatMap(ControllerVersion::buildVersion);
    }

    /// The bundled judge; an unfiltered placeholder or a value outside [#STARTABLE_JUDGE] fails start, as an unfiltered
    /// version does.
    static Result<Judge> judge() {
        return StreamOps.readResource(ControllerVersion.class, RESOURCE)
                        .mapError(origin -> ConfigError.Unreadable.FACTORY.apply(RESOURCE, origin))
                        .flatMap(properties -> judgeOf(properties).toResult(ConfigError.General.NO_JUDGE))
                        .flatMap(ControllerVersion::startable);
    }

    private static Result<String> buildVersion(String projectVersion, String judge) {
        return BuildVersion.buildVersion(projectVersion, judge).mapError(origin -> ConfigError.Invalid.FACTORY.apply("",
                                                                                                                     "version",
                                                                                                                     origin));
    }

    /// A judge outside [#STARTABLE_JUDGE] is invalid.
    static Result<Judge> startable(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             STARTABLE_JUDGE,
                             ConfigError.Invalid.FACTORY.apply("",
                                                               "judge",
                                                               cause("bundled judge '" + raw
                                                                    + "' is outside " + STARTABLE_JUDGE.pattern())))
                     .flatMap(Judge::judge);
    }

    /// The `version=` line of the filtered resource; an unfiltered `${...}` placeholder is no version.
    static Option<String> versionOf(String properties) {
        return BuildVersion.property(properties, "version");
    }

    /// The `judge=` line of the filtered resource; an unfiltered `${...}` placeholder is no judge.
    static Option<String> judgeOf(String properties) {
        return BuildVersion.property(properties, "judge");
    }

    /// A `version` key in `controller.toml` is refused: a hand-typed value could silently disagree with the build.
    static Result<String> handTyped(String raw) {
        return ConfigError.Invalid.FACTORY.apply("",
                                                 "version",
                                                 cause("'" + raw
                                                      + "' is hand-typed; the version comes from the build, "
                                                      + "remove this key")).result();
    }

    record unused() implements ControllerVersion {}
}
