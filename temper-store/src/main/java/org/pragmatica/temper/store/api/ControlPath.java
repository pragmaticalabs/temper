package org.pragmatica.temper.store.api;

import java.time.Instant;


/// One `ci_control_paths` row of a repo: a G9 glob, who added it and when.
public record ControlPath(String glob, String addedBy, Instant addedAt) {
    public static ControlPath controlPath(String glob, String addedBy, Instant addedAt) {
        return new ControlPath(glob, addedBy, addedAt);
    }
}
