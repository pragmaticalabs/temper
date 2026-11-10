package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.lang.Option;


/// The last pass of one kind on one host (`host_pass`), as the dashboard header shows it (spec §6 panel 1: each host
/// with its last hot and full pass, the Mac with its push). A running full pass shows its progress `k of N` (spec §5.5).
/// The Mac's push is kind [PassKind#PUSH]; its `finishedAt` is `pushedAt` (spec §5.4). Added in WP10, which renders
/// it; [ViewStore#passes] reads it (WP9), with `expected`, the listing's trailer count (absent when no trailer was
/// read).
public record PassView(HostName host,
                       PassKind kind,
                       PassOutcome outcome,
                       int rows,
                       Option<Integer> expected,
                       Instant startedAt,
                       Option<Instant> finishedAt,
                       Option<Integer> progressK,
                       Option<Integer> progressN) {}
