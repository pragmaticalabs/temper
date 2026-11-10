package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;


/// `POST tasks/{id}/park` body (m3-design §5.5, T10): the request, and the epoch when the lease holder parks
/// (`Authorization: Lease`); the maintainer (full token) sends none.
public record ParkCall(Option<Long> epoch, ParkRequestDto request) {}
