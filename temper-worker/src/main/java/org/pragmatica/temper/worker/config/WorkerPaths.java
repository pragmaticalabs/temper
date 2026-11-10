package org.pragmatica.temper.worker.config;

import java.nio.file.Path;

import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;


/// The worker's host layout under `~/.temper` (spec §4.1, m0-design §7.1). Pure path arithmetic; nothing here
/// touches the filesystem.
///
/// ```
/// ~/.temper/ worker.lock  mirror.git/  seed-m2/  runner/<ver>/
///   jobs/<job>/ws/                         checkout, ws/.m2-local (copy of seed-m2), ws/.home
///   jobs/<job>/units/<unit>/.temper/<file> unit state files (UnitFile)
///   jobs/<job>/units/<unit>/reports/       copied report XML
///   jobs/<job>/units/<unit>/threaddumps/   one file per JVM at a timeout
///   jobs/<job>/units/<unit>/log            docker logs, written at exit
///   jobs/<job>/units/<unit>/log.tail       the last 1 MiB of `log` (small evidence, m1-design §3.2)
///   jobs/<job>/units/<unit>/manifest/      effective-pom.xml and manifest.json (m1-design §2.3, §2.5)
/// ```
public record WorkerPaths(Path temperHome) {
    public static WorkerPaths workerPaths(Path temperHome) {
        return new WorkerPaths(temperHome.toAbsolutePath().normalize());
    }

    public Path lockFile() {
        return temperHome.resolve("worker.lock");
    }

    public Path mirror() {
        return temperHome.resolve("mirror.git");
    }

    public Path seed() {
        return temperHome.resolve("seed-m2");
    }

    public Path runnerDir(String version) {
        return temperHome.resolve("runner")
                         .resolve(version);
    }

    public Path jobsDir() {
        return temperHome.resolve("jobs");
    }

    public Path jobDir(JobId job) {
        return jobsDir().resolve(job.toString());
    }

    public Path workspace(JobId job) {
        return jobDir(job).resolve("ws");
    }

    /// The workspace Maven repository; `-Dmaven.repo.local` points here (R-M2).
    public Path workspaceRepo(JobId job) {
        return workspace(job).resolve(".m2-local");
    }

    /// The unit container's `HOME` (Testcontainers writes `~/.testcontainers.properties` there).
    public Path workspaceHome(JobId job) {
        return workspace(job).resolve(".home");
    }

    public Path unitsDir(JobId job) {
        return jobDir(job).resolve("units");
    }

    public Path unitDir(UnitRef ref) {
        return unitsDir(ref.job()).resolve(ref.unit().value());
    }

    public Path stateDir(UnitRef ref) {
        return unitDir(ref).resolve(".temper");
    }

    public Path stateFile(UnitRef ref, UnitFile file) {
        return stateDir(ref).resolve(file.fileName());
    }

    public Path reportsDir(UnitRef ref) {
        return unitDir(ref).resolve("reports");
    }

    public Path threadDumpsDir(UnitRef ref) {
        return unitDir(ref).resolve("threaddumps");
    }

    public Path logFile(UnitRef ref) {
        return unitDir(ref).resolve("log");
    }

    public Path logTail(UnitRef ref) {
        return unitDir(ref).resolve("log.tail");
    }

    public Path manifestDir(UnitRef ref) {
        return unitDir(ref).resolve("manifest");
    }

    /// The effective POM, moved here from [#effectivePomOutput] after the probe.
    public Path effectivePom(UnitRef ref) {
        return manifestDir(ref).resolve("effective-pom.xml");
    }

    /// The computed manifest outcome as sent (JSON), resent by a later `Collect`.
    public Path manifestFile(UnitRef ref) {
        return manifestDir(ref).resolve("manifest.json");
    }

    /// Where the probe container writes the effective POM: the planner's `-Doutput=${ws}/.home/.temper/
    /// effective-pom-<unit>.xml` with `${ws}` substituted.
    public Path effectivePomOutput(UnitRef ref) {
        return workspaceHome(ref.job()).resolve(".temper")
                            .resolve("effective-pom-" + ref.unit().value() + ".xml");
    }
}
