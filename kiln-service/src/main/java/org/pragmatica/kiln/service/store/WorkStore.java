package org.pragmatica.kiln.service.store;

import org.pragmatica.lang.Promise;


/// The work ledger (`work_item`, `work_event`): the Work tab's only source. Not a cache, unlike every other table: phase
/// and round are recorded by the team lead and cannot be rebuilt from anything kiln reads.
public interface WorkStore {
    /// Appends the event and upserts its item in one transaction: the item is created with `submitted_at` the event's
    /// `at`, which only an earlier event moves back; a given title replaces the stored one unless an event later than this
    /// one is recorded and a title is already stored, and an absent one keeps it.
    /// Answers the event's id.
    Promise<Long> record(WorkEvent event);
    /// Every item's current state: its latest event by (`at`, id).
    Promise<WorkBoard> board();
}
