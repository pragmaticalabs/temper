package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.HostFactsDto;

import static org.pragmatica.lang.Option.option;


/// `GET /hosts` item. `reactors = foreign + active` (spec §6.1, A7). `state` is `LOST` while the host is LOST
/// (m4-design §2, §11), otherwise DOWN, CONNECTING or UP; `link` is the link's own state either way (a LOST host keeps
/// reconnecting). `lost` is the LOST mark, present exactly while `state` is `LOST`. `sharedCpu` is the host's CPU class
/// (§9.1); `retireAt` its configured retirement and `retired` whether it has passed (`now ≥ retireAt`, §10: no new
/// grants). A view written without the M4 fields (an older controller, a hand-written answer) reads `link` as `state`,
/// never LOST, a dedicated-CPU host, not retired.
public record HostView(String name,
                       String state,
                       String link,
                       Option<HostLossDto> lost,
                       int cap,
                       int reactors,
                       int foreign,
                       int active,
                       Option<HostFactsDto> facts,
                       Boolean sharedCpu,
                       Option<Instant> retireAt,
                       Boolean retired) {
    public static final String LOST = "LOST";

    public HostView {
        link = option(link).or(state);
        sharedCpu = option(sharedCpu).or(false);
        retired = option(retired).or(false);
    }

    /// A host as before M4: never LOST, dedicated-CPU, no `retireAt`; `link` is `state`.
    public static HostView hostView(String name,
                                    String state,
                                    int cap,
                                    int reactors,
                                    int foreign,
                                    int active,
                                    Option<HostFactsDto> facts) {
        return hostView(name, state, Option.none(), cap, reactors, foreign, active, facts, false, Option.none(), false);
    }

    public static HostView hostView(String name,
                                    String link,
                                    Option<HostLossDto> lost,
                                    int cap,
                                    int reactors,
                                    int foreign,
                                    int active,
                                    Option<HostFactsDto> facts,
                                    boolean sharedCpu,
                                    Option<Instant> retireAt,
                                    boolean retired) {
        return new HostView(name,
                            lost.map(_ -> LOST).or(link),
                            link,
                            lost,
                            cap,
                            reactors,
                            foreign,
                            active,
                            facts,
                            sharedCpu,
                            retireAt,
                            retired);
    }
}
