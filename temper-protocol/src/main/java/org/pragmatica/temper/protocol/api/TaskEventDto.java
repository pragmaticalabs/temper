package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.Map;


/// One `task_events` row in `GET /api/v1/tasks/{id}/events` (m3-design §5.3): the kind, the stage, status, visit and
/// epoch the event left the task in, who acted, and the event's facts.
public record TaskEventDto(long seq,
                           Instant at,
                           String kind,
                           String stage,
                           String status,
                           int visit,
                           long epoch,
                           String actor,
                           Map<String, String> facts) {}
