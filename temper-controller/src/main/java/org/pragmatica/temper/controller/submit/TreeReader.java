package org.pragmatica.temper.controller.submit;

import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.refusal.TreeFacts;


/// Reads, at `sha`, every Java source file that declares one of `simpleClassNames` (the data-driven refusal rules'
/// classes, spec §2.5), for the R-1553 check, and the reactor the root pom builds (m1-design §1.6), for the planner's
/// port-group rule and the refusals; an unreadable reactor is none (the conservative M0 judgement). [#file] reads one
/// file at `sha` (the rerun bound's shard weights).
public interface TreeReader {
    Promise<TreeFacts> facts(Sha sha, Set<String> simpleClassNames);
    /// The file at `path` in `sha`; none only when the tree has no such file, a failed read fails.
    Promise<Option<byte[]>> file(Sha sha, String path);
}
