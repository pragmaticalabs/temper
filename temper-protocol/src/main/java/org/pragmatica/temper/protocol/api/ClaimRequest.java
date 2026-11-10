package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;


/// `POST tasks/{id}/claim` and `POST tasks/claim?stage=S` body (m3-design §6.1): who claims (`--worker NAME`), recorded
/// as the lease holder; absent, the holder is `worker`.
public record ClaimRequest(Option<String> worker) {}
