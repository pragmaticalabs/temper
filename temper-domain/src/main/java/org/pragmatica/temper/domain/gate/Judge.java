package org.pragmatica.temper.domain.gate;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Values;


/// Which temper build judged a job (m2-design §1.7, ruling R-M2-10): the git commit of the build that produced the
/// jars, `<commit>-dirty` for a build of a tree with changes, [#UNJUDGED] for a build without a judge (a plain
/// `mvn install`, every dev and test build), and [#PRE] for the rows written before the judge existed (the V6
/// migration's value). `ops/deploy-jars.sh` deploys only a clean 40-hex judge; a controller starts with any judge but
/// [#PRE] (`ControllerVersion.judge`).
///
/// Judges are never ordered by their value: which judge is newest at a SHA follows from the job ids
/// (`GateEvidence`), so [#PRE] rows are older than any judge because their ids are.
public record Judge(String value) {
    /// Rows older than the V6 migration.
    public static final Judge PRE = new Judge("pre-judge");
    /// A build that was not given a judge.
    public static final Judge UNJUDGED = new Judge("unjudged");
    /// The `jobs.judge` and `results.judge` CHECK of the V6 migration; `units.granted_judge` admits all but `pre-judge`.
    public static final Pattern PATTERN = Pattern.compile("^([0-9a-f]{40}(-dirty)?|unjudged|pre-judge)$");
    /// The result annotation of a job judged across a deploy (ruling R-M2-11), followed by the grant's judge.
    public static final String CHANGED_ANNOTATION = "judge-changed=";
    private static final int COMMIT = 40;
    private static final int SHORT = 7;

    public static Result<Judge> judge(String raw) {
        return Values.matching(raw, PATTERN, "judge").map(Judge::new);
    }

    /// The 7-hex prefix of a commit judge (`-dirty` kept), the word itself otherwise: the one-line form.
    public String shortForm() {
        return value.length() < COMMIT
               ? value
               : value.substring(0, SHORT) + value.substring(COMMIT);
    }

    /// `judge-changed=<granted, short>`: on the record of a job granted under `granted` and finished under another judge.
    public static String changedAnnotation(Judge granted) {
        return CHANGED_ANNOTATION + granted.shortForm();
    }

    @Override
    public String toString() {
        return value;
    }
}
