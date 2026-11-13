package org.pragmatica.temper.protocol.api;

import java.time.Instant;


/// A tree report as the agent SELF-REPORTED it at heartbeat time (m3 lane registry): `head` of its tree, files `dirty`,
/// commits `unpushed`. The controller cannot observe the tree; `epoch` and `at` say which lease sent it and when.
public record TreeReportDto(String head, int dirty, int unpushed, long epoch, Instant at) {}
