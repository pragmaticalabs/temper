package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// One transition of the work ledger, as `POST /api/v1/ingest/work` accepted it (`WorkRequest`): the work package `id`
/// (`T1-WP5`), its `item` (the id's leading segment, `T1`), the title when one is given, the phase it entered, its round
/// and the other facts the team lead recorded, at `at`.
public record WorkEvent(String id,
                        String item,
                        Option<String> title,
                        WorkPhase phase,
                        int round,
                        Option<String> coder,
                        Option<String> verifier,
                        Option<String> note,
                        Instant at) {}
