package org.pragmatica.temper.cli.config;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.StreamOps;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.protocol.message.BuildVersion;

import static org.pragmatica.lang.utils.Causes.cause;


/// The CLI's own build version (D-6): Maven filters `${project.version}` and `${temper.judge}` into [#RESOURCE], and
/// [BuildVersion] derives `<project.version>+<judge12>` from them, exactly as the controller derives the version it
/// sends in every answer's `Temper-Version` header.
public sealed interface CliVersion {
    String RESOURCE = "/org/pragmatica/temper/cli/cli-version.properties";

    static Result<String> bundled() {
        return StreamOps.readResource(CliVersion.class, RESOURCE)
                        .flatMap(CliVersion::derived)
                        .mapError(CliError.NoVersion.FACTORY);
    }

    /// The build version of the filtered resource's `version=` and `judge=` lines; a missing or unfiltered line, or a
    /// value [BuildVersion] refuses, is a failure.
    static Result<String> derived(String properties) {
        return Result.all(BuildVersion.property(properties, "version").toResult(cause("no version line in " + RESOURCE)),
                          BuildVersion.property(properties, "judge").toResult(cause("no judge line in " + RESOURCE)))
                     .flatMap(BuildVersion::buildVersion);
    }

    record unused() implements CliVersion {}
}
