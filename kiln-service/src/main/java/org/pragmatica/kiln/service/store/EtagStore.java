package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `gh_etag`: the last 200 body per URL, replayed on a 304.
public interface EtagStore {
    Promise<Option<Cached>> cached(String url);
    Promise<Unit> store(String url, String etag, String body, Instant at);
}
