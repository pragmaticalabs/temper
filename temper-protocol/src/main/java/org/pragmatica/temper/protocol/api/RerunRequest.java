package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;


/// `POST /api/v1/jobs/{id}/rerun` body (m2-design §5.5): who asks, an optional priority (the parent's when absent),
/// `fresh` (a new rerun job even when an equal one exists) and an optional ISO-8601 `timeout` for the rerun unit (the
/// derived bound when absent; like submit's, it enters the dedup key). The route parses each field with its domain
/// factory (400). `dedicatedCpu` (`--dedicated-cpu`, m4-design §9.2) can only add the class: a rerun inherits its
/// parent's whatever this says.
public record RerunRequest(String submittedBy,
                           Option<Integer> priority,
                           Option<Boolean> fresh,
                           Option<String> timeout,
                           Option<Boolean> dedicatedCpu) {
    public static RerunRequest rerunRequest(String submittedBy,
                                            Option<Integer> priority,
                                            Option<Boolean> fresh,
                                            Option<String> timeout,
                                            Option<Boolean> dedicatedCpu) {
        return new RerunRequest(submittedBy, priority, fresh, timeout, dedicatedCpu);
    }
}
