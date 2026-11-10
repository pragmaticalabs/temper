package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.StreamOps;
import org.pragmatica.temper.protocol.digest.Digests;
import org.pragmatica.temper.worker.InFlight;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;


/// The runner image `temper/runner:<version>` (spec §4.6, m0-design §7.6): returns its image id, building it
/// on first use from the `Dockerfile` and `RunSteps.java` shipped in the worker jar (extracted to
/// `~/.temper/runner/<version>/`). The image carries the sha256 of that build context as a label; an image under
/// the same tag built from different resources (a rebuilt jar of the same version) is rebuilt, never reused.
@FunctionalInterface
public interface RunnerImage {
    List<String> RESOURCES = List.of("Dockerfile", "RunSteps.java");
    Promise<String> ensure(String version);

    /// Serialised per version: concurrent `StartUnit`s share one in-flight check-or-build ([InFlight#joined]). Only
    /// the in-flight promise is shared: a call made after a caller saw it resolve inspects again (cheap), so an image
    /// removed by hand or a failed build is noticed instead of cached. A call racing the resolution itself joins it.
    static RunnerImage runnerImage(DockerCli docker, WorkerPaths paths) {
        var inFlight = new ConcurrentHashMap<String, Promise<String>>();

        return version -> shared(inFlight, version, () -> imageOrBuild(docker, paths, version));
    }

    private static Promise<String> shared(Map<String, Promise<String>> inFlight,
                                          String version,
                                          Supplier<Promise<String>> ensure) {
        return InFlight.joined(inFlight, version, ensure);
    }

    private static Promise<String> imageOrBuild(DockerCli docker, WorkerPaths paths, String version) {
        return contentHash().async()
                          .flatMap(hash -> currentOrBuilt(docker, paths, version, hash));
    }

    private static Promise<String> currentOrBuilt(DockerCli docker, WorkerPaths paths, String version, String hash) {
        return docker.imageContentHash(DockerRunArgs.image(version))
                     .flatMap(label -> builtUnlessCurrent(docker, paths, version, hash, label));
    }

    private static Promise<String> builtUnlessCurrent(DockerCli docker,
                                                      WorkerPaths paths,
                                                      String version,
                                                      String hash,
                                                      Option<String> label) {
        return label.filter(hash::equals)
                    .isPresent()
               ? imageId(docker, version)
               : built(docker, paths, version, hash);
    }

    private static Promise<String> built(DockerCli docker, WorkerPaths paths, String version, String hash) {
        return extract(paths.runnerDir(version)).async()
                      .flatMap(directory -> docker.build(DockerRunArgs.image(version),
                                                         version,
                                                         hash,
                                                         directory))
                      .flatMap(_ -> imageId(docker, version));
    }

    private static Promise<String> imageId(DockerCli docker, String version) {
        return docker.imageId(DockerRunArgs.image(version))
                     .flatMap(id -> id.async(WorkerError.UnexpectedOutput.FACTORY.apply("image inspect",
                                                                                        "image missing after a build")));
    }

    /// sha256 over the build context resources, in order.
    static Result<String> contentHash() {
        return Result.allOf(RESOURCES.stream().map(RunnerImage::resource).toList()).flatMap(RunnerImage::digestOf);
    }

    private static Result<String> digestOf(List<String> contents) {
        return Digests.sha256Hex(String.join("\u0000", contents));
    }

    /// Writes the build context from the jar's `runner/` resources.
    static Result<Path> extract(Path directory) {
        return Result.allOf(RESOURCES.stream().map(name -> extracted(directory, name)).toList()).map(_ -> directory);
    }

    private static Result<Unit> extracted(Path directory, String name) {
        return resource(name).flatMap(content -> UnitFiles.storeAtomic(directory.resolve(name), content));
    }

    /// The text of `runner/<name>` on the classpath.
    static Result<String> resource(String name) {
        return StreamOps.readResource(RunnerImage.class, "/runner/" + name).mapError(origin -> WorkerError.Io.FACTORY.apply("read the runner resource " + name,
                                                                                                                            origin));
    }
}
