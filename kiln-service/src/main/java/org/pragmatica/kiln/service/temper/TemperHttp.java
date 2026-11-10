package org.pragmatica.kiln.service.temper;

import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.core.temper.HostsRead;
import org.pragmatica.lang.Promise;


/// temper's read API with the read token (spec §4.5). Built in WP6 (`JdkTemperHttp`).
public interface TemperHttp {
    /// `GET /api/v1/digest?format=text&max=10`, verbatim: never decoded.
    Promise<String> digestText();
    /// `GET /api/v1/gate/{sha}`, decoded by [GateRead#decode].
    Promise<GateRead> gate(Sha head);
    /// `GET /api/v1/fleet/alarms`, decoded by [HostsRead#decode] (tolerant of unknown fields).
    Promise<HostsRead> fleetAlarms();
}
