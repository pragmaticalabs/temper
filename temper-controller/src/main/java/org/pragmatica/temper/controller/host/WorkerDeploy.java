package org.pragmatica.temper.controller.host;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.message.BuildVersion;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Deploys the worker jar over the forced-command key's `install <ver> <sha256>` verb (m0-design §6.4, Q-13): the
/// jar travels on ssh's stdin; `worker-launch` verifies the sha256 before an atomic rename into
/// `~/.temper/worker-<ver>.jar`. A non-zero exit fails the deploy; the link then simply retries with backoff.
/// The kill of a timed-out install is queued, so the next `run` ssh may start while it still lives; that is safe
/// because `worker-launch` installs only a jar whose sha256 matched, by an atomic rename (never a partial jar).
///
/// L4 (D-6): before anything is sent, the jar's own build version (its [BuildVersion#WORKER_RESOURCE] `version=` and
/// `judge=` lines, read from the bytes about to be sent) must be this controller's; a jar of another build is refused
/// with [LinkError.ForeignWorkerJar] and never installed, since the worker it starts would be `Incompatible` and
/// redeployed forever. The whole build version is compared, not only the judge: any difference makes that loop.
public sealed interface WorkerDeploy {
    static Promise<Unit> deploy(LinkEnv env, ControllerConfig.Host host) {
        return versionedJar(env, host).flatMap(versioned -> ofThisBuild(env, host, versioned))
                           .async()
                           .flatMap(payload -> installed(env, host, payload));
    }

    /// The configured jar, its digest and the build version it names.
    private static Result<Versioned> versionedJar(LinkEnv env, ControllerConfig.Host host) {
        return FileOps.readBytes(env.workerJar())
                      .flatMap(WorkerDeploy::payload)
                      .flatMap(WorkerDeploy::withVersion)
                      .mapError(cause -> LinkError.DeployFailed.FACTORY.apply(host.name(),
                                                                              cause));
    }

    /// The jar and its digest.
    record Payload(byte[] jar, String sha256) {
        static Payload payload(byte[] jar, String sha256) {
            return new Payload(jar, sha256);
        }
    }

    /// A payload and the build version its jar names.
    record Versioned(Payload payload, String version) {
        static Versioned versioned(Payload payload, String version) {
            return new Versioned(payload, version);
        }
    }

    private static Result<Payload> payload(byte[] jar) {
        return sha256(jar).map(digest -> Payload.payload(jar, digest));
    }

    private static Result<Versioned> withVersion(Payload payload) {
        return jarVersion(payload.jar()).map(version -> Versioned.versioned(payload, version));
    }

    /// The jar's build version, derived from its version resource as the worker derives its own at start.
    static Result<String> jarVersion(byte[] jar) {
        return resourceIn(new ZipInputStream(new ByteArrayInputStream(jar))).toResult(cause("the worker jar has no " + BuildVersion.WORKER_RESOURCE))
                         .flatMap(WorkerDeploy::readEntry)
                         .flatMap(WorkerDeploy::derived);
    }

    private static Result<String> derived(String properties) {
        return Result.all(BuildVersion.property(properties, "version").toResult(cause("the worker jar's version resource has no version")),
                          BuildVersion.property(properties, "judge").toResult(cause("the worker jar's version resource has no judge")))
                     .flatMap(BuildVersion::buildVersion);
    }

    /// The stream positioned at the version resource's entry, scanned lazily in jar order; none when the jar ends first.
    /// The stream reads an in-memory array, so it is not closed: it holds no descriptor, and its inflater is released
    /// with it.
    private static Option<ZipInputStream> resourceIn(ZipInputStream zip) {
        return Option.from(Stream.iterate(nextEntry(zip),
                                          Option::isPresent,
                                          _ -> nextEntry(zip))
                                 .flatMap(Option::stream)
                                 .filter(WorkerDeploy::isVersionResource)
                                 .findFirst()).map(_ -> zip);
    }

    private static boolean isVersionResource(ZipEntry entry) {
        return entry.getName()
                    .equals(BuildVersion.WORKER_RESOURCE);
    }

    /// The next entry; none at the end. FER: an entry that cannot be read ends the scan as the end of the jar does, so a
    /// damaged jar is refused as one without the resource (nothing is installed), never skipped past.
    private static Option<ZipEntry> nextEntry(ZipInputStream zip) {
        return Result.lift(Causes::fromThrowable, zip::getNextEntry)
                     .option()
                     .flatMap(Option::option);
    }

    private static Result<String> readEntry(ZipInputStream zip) {
        return Result.lift(Causes::fromThrowable, zip::readAllBytes).map(bytes -> new String(bytes,
                                                                                             StandardCharsets.UTF_8));
    }

    private static Result<Payload> ofThisBuild(LinkEnv env, ControllerConfig.Host host, Versioned versioned) {
        return versioned.version()
                        .equals(env.version())
               ? success(versioned.payload())
               : LinkError.ForeignWorkerJar.FACTORY.apply(host.name(),
                                                          versioned.version(),
                                                          env.version())
                                                   .result();
    }

    /// A host-key failure stays itself ([LinkError.HostKey]), so the link marks the host LOST (m4-design §2.2).
    private static Promise<Unit> installed(LinkEnv env, ControllerConfig.Host host, Payload payload) {
        return install(env, host, payload).mapError(cause -> deployFailure(host, cause));
    }

    private static Cause deployFailure(ControllerConfig.Host host, Cause cause) {
        return cause instanceof LinkError.HostKey
               ? cause
               : LinkError.DeployFailed.FACTORY.apply(host.name(), cause);
    }

    private static Promise<Unit> install(LinkEnv env, ControllerConfig.Host host, Payload payload) {
        return Command.command(SshCommand.install(host,
                                                  env.version(),
                                                  payload.sha256()),
                               Map.of(),
                               env.timing().deploy())
                      .map(Command::inheritingEnvironment)
                      .flatMap(env.runner()::spawn)
                      .async()
                      .flatMap(process -> bounded(env,
                                                  host,
                                                  process,
                                                  payload.jar()));
    }

    /// The whole transfer is bounded; on failure the ssh process is killed.
    private static Promise<Unit> bounded(LinkEnv env, ControllerConfig.Host host, Spawned process, byte[] jar) {
        // The kill may run late: it is a best-effort signal to an abandoned process, whose outcome nothing reads.
        return transfer(host, process, jar).timeout(env.timing().deploy())
                       .onFailure(_ -> process.kill());
    }

    /// Fork-join: write the jar to stdin while draining stdout and stderr, then wait for the exit code.
    private static Promise<Unit> transfer(ControllerConfig.Host host, Spawned process, byte[] jar) {
        return Promise.all(Promise.<Unit> promise().async(() -> pipe(process, jar)),
                           Promise.lift(Causes::fromThrowable,
                                        () -> process.stdout()
                                                     .readAllBytes()),
                           Promise.lift(Causes::fromThrowable,
                                        () -> process.stderr()
                                                     .readAllBytes()))
                      .flatMap((_, _, stderr) -> exitOf(host, process, stderr));
    }

    private static Promise<Unit> exitOf(ControllerConfig.Host host, Spawned process, byte[] stderr) {
        return process.exit()
                      .flatMap(rc -> exited(host,
                                            rc,
                                            new String(stderr, StandardCharsets.UTF_8)));
    }

    /// Writes the jar, then closes stdin so `worker-launch install` sees EOF.
    private static Result<Unit> pipe(Spawned process, byte[] jar) {
        return Result.lift(Causes::fromThrowable,
                           () -> process.stdin()
                                        .write(jar))
                     .flatMap(_ -> Result.lift(Causes::fromThrowable,
                                               () -> process.stdin()
                                                            .close()));
    }

    private static Promise<Unit> exited(ControllerConfig.Host host, int rc, String stderr) {
        return rc == 0
               ? Promise.unitPromise()
               : installFailed(host, rc, stderr);
    }

    /// ssh's own failure; any other code is the remote command's, whose stderr is not ssh's diagnosis (m4-design §2.2).
    int SSH_FAILED = 255;

    private static Promise<Unit> installFailed(ControllerConfig.Host host, int rc, String stderr) {
        return rc == SSH_FAILED && SshDiagnosis.hostKeyFailureIn(stderr)
               ? LinkError.HostKey.FACTORY.apply(host.name()).promise()
               : cause("install exited " + rc + ": " + stderr.trim()).promise();
    }

    static Result<String> sha256(byte[] bytes) {
        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> HexFormat.of().formatHex(digest.digest(bytes)));
    }

    record unused() implements WorkerDeploy {}
}
