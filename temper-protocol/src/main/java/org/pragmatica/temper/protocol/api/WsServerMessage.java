package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;


/// The `200` body of the long-poll `GET /api/v1/subscribers/{name}/next` (m0-design §6.5 fallback; the original
/// `/api/v1/events` WebSocket never reached its handler on rc4): one `event` at a time, or a `gap` when retention
/// passed the cursor. `error` belonged to the WebSocket (a bad token is HTTP `401` on long-poll). `line` already
/// ends with `seq=<n>`.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = WsServerMessage.WsEvent.class, name = "event"), @JsonSubTypes.Type(value = WsServerMessage.WsGap.class, name = "gap"), @JsonSubTypes.Type(value = WsServerMessage.WsError.class, name = "error")})
public sealed interface WsServerMessage {
    record WsEvent(long seq, Instant at, Option<Long> job, EventKind kind, Option<String> verdict, String line) implements WsServerMessage {}

    record WsGap(long from, long to) implements WsServerMessage {}

    record WsError(String error) implements WsServerMessage {}
}
