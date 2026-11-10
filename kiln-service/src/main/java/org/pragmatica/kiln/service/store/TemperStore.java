package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `temper_digest` (verbatim, never decoded) and `temper_gate`.
public interface TemperStore {
    Promise<Unit> digest(String text, Instant at);
    Promise<Unit> gate(PrNumber pr, Sha head, GateRead read, Instant at);
}
