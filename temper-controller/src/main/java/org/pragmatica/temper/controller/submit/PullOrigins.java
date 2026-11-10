package org.pragmatica.temper.controller.submit;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Tells a same-repo pull request from a fork PR (spec §13, R-M2-7), which git alone cannot: GitHub publishes both
/// under `refs/pull/<N>/head`. A failure ([PullOriginError]) means the origin could not be established, and the
/// submit is refused as `pr-origin-unverified` (fail closed), never accepted.
@FunctionalInterface
public interface PullOrigins {
    Promise<PullOrigin> origin(PullNumber pull);

    /// The origin already established for `pull`, without a call; none when it must be asked.
    default Option<PullOrigin> known(PullNumber pull) {
        return Option.none();
    }
}
