package org.pragmatica.kiln.service.store;

import java.util.List;

import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `source_state`: one row per source key, so a restart shows the last known state with its age (§3.4).
public interface SourceStore {
    /// Records the state; an [SourceState.Ok] also sets the last success.
    Promise<Unit> record(Source source, SourceState state);
    Promise<List<SourceStatus>> all();
}
