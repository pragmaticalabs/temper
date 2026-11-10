package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// One work package's current state: its item row, and the facts of its latest event by (`at`, id). `submittedAt` is
/// its earliest event's time; `phaseSince` is when it entered its current phase (the first event of the latest run of
/// events in that phase), so a note recorded in the same phase does not restart the clock.
public record WorkCard(String id,
                       String item,
                       Option<String> title,
                       Instant submittedAt,
                       WorkPhase phase,
                       int round,
                       Option<String> coder,
                       Option<String> verifier,
                       Option<String> note,
                       Instant at,
                       Instant phaseSince) {}
