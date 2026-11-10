package org.pragmatica.temper.protocol.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;


/// Client → server: `ack` is the body of the long-poll `POST /api/v1/subscribers/{name}/ack` (window of 1).
/// `subscribe` belonged to the `/api/v1/events` WebSocket (Q-2), which long-poll replaced: the token travels in the
/// `Authorization` header and the subscriber name in the path.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = WsClientMessage.WsSubscribe.class, name = "subscribe"), @JsonSubTypes.Type(value = WsClientMessage.WsAck.class, name = "ack")})
public sealed interface WsClientMessage {
    record WsSubscribe(String token, String subscriber, boolean terminal) implements WsClientMessage {}

    record WsAck(long seq) implements WsClientMessage {}
}
