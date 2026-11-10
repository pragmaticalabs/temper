package org.pragmatica.kiln.service.web;

import java.time.Instant;


/// GitHub's rate-limit budget as the last answer stated it (`X-RateLimit-Remaining`, `X-RateLimit-Reset`): the header
/// shows it beside GitHub's state (spec §6 panel 1).
public record RateBudget(int remaining, Instant reset, int kilnSpent, int hourlyCap, int reserve) {}
