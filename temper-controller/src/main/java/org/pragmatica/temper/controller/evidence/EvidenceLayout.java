package org.pragmatica.temper.controller.evidence;

import java.nio.file.Path;

import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;


/// The evidence store on the controller host (m1-design §3.1 [P]), under `[evidence] root`:
///
/// ```
/// jobs/<job>/result.json
/// jobs/<job>/units/<unit>/a<n>/evidence.tar.gz     small, of attempt n
/// jobs/<job>/units/<unit>/a<n>/full.tar.gz         full, of attempt n
/// pgdump/temper-<yyyymmddThhmmssZ>.dump
/// ```
///
/// Job ids and unit names are validated value objects and an attempt is a number, so no path built here leaves `root`.
/// Each attempt has its own directory (m4-design §4.6), so a pull of a superseded attempt racing a requeue never
/// overwrites the current attempt's file.
public record EvidenceLayout(Path root) {
    public static final String RESULT_JSON = "result.json";
    public static final String PARTIAL = ".partial";

    public static EvidenceLayout evidenceLayout(Path root) {
        return new EvidenceLayout(root);
    }

    public Path job(JobId job) {
        return root.resolve("jobs")
                   .resolve(job.toString());
    }

    public Path resultJson(JobId job) {
        return job(job).resolve(RESULT_JSON);
    }

    public Path tarball(UnitRef ref, int attempt, EvidenceKind kind) {
        return job(ref.job()).resolve("units")
                  .resolve(ref.unit().value())
                  .resolve("a" + attempt)
                  .resolve(fileName(kind));
    }

    public Path pgDumps() {
        return root.resolve("pgdump");
    }

    /// The sibling a file is written to before its atomic rename.
    public static Path partial(Path file) {
        return file.resolveSibling(file.getFileName() + PARTIAL);
    }

    private static String fileName(EvidenceKind kind) {
        return switch (kind) {
            case SMALL -> "evidence.tar.gz";
            case FULL -> "full.tar.gz";
        };
    }
}
