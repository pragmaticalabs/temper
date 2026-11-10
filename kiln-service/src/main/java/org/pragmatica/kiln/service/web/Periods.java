package org.pragmatica.kiln.service.web;

import java.time.Duration;

import org.pragmatica.kiln.core.source.Source;


/// Each source's loop period (kiln-f1-design §3.1), which its staleness bound is 3 × of (spec §6). `github` is the
/// period in force, stretched when the rate budget demands it (Q-7); `host` is the hot pass's; `mac` is the interval the
/// Mac is expected to push at, which no loop of kiln's owns: [#MAC_PUSH] (WP9 open item 3).
public record Periods(Duration github, Duration mirror, Duration temper, Duration host, Duration mac, Duration hosts) {
    /// The Mac pushes every 2 h, so its push reads stale after 6 h.
    public static final Duration MAC_PUSH = Duration.ofHours(2);
    /// The fleet-alarms read's period.
    public static final Duration HOSTS = Duration.ofSeconds(60);

    /// The periods before the `hosts` source: its period is [#HOSTS].
    public Periods(Duration github, Duration mirror, Duration temper, Duration host, Duration mac) {
        this(github, mirror, temper, host, mac, HOSTS);
    }

    /// The periods of the loops, with the Mac's [#MAC_PUSH].
    public static Periods periods(Duration github, Duration mirror, Duration temper, Duration host) {
        return new Periods(github, mirror, temper, host, MAC_PUSH, HOSTS);
    }

    public Duration of(Source source) {
        return switch (source) {
            case Source.GitHub _ -> github;
            case Source.Mirror _ -> mirror;
            case Source.Temper _ -> temper;
            case Source.Hosts _ -> hosts;
            case Source.Host _ -> host;
            case Source.Mac _ -> mac;
        };
    }
}
