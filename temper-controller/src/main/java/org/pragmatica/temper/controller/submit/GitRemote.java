package org.pragmatica.temper.controller.submit;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;


/// Resolves a pushed ref to its commit inside the submit call (spec §2.2). A ref that matches no branch or tag, or
/// matches more than one distinct commit, fails with `Refusal(source)`; a git that cannot run fails with
/// [GitError].
@FunctionalInterface
public interface GitRemote {
    Promise<Sha> lsRemote(Ref ref);
}
