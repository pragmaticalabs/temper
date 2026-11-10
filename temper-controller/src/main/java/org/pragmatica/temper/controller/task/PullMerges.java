package org.pragmatica.temper.controller.task;

import org.pragmatica.lang.Promise;


/// Whether a pull request is merged on GitHub (m3-design §10.5, T15's guard). A failure means it could not be read,
/// and the close is refused with it (fail closed), never taken as merged.
@FunctionalInterface
public interface PullMerges {
    Promise<Boolean> isMerged(long pr);
}
