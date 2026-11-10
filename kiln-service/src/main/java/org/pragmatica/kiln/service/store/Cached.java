package org.pragmatica.kiln.service.store;

import java.time.Instant;


/// A URL's last 200 answer: its `ETag` and body.
public record Cached(String etag, String body, Instant observedAt) {}
