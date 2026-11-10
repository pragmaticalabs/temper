package org.pragmatica.kiln.service.web;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// Every source's status with its period: what decides that a panel turns red and that a host's rows are stale. A
/// source with no recorded status is stale: a source never observed is never fresh.
public record Sources(List<SourceStatus> statuses, Periods periods) {
    public Sources {
        statuses = List.copyOf(statuses);
    }

    public Option<SourceStatus> of(Source source) {
        return Option.from(statuses.stream().filter(status -> status.source()
                                                                    .equals(source)).findFirst());
    }

    /// Whether the source's last success is older than 3 × its period ([SourceStatus#staleAt]), or lies in the future
    /// beyond [Html#SKEW]: a success the clocks disagree on proves nothing fresh.
    public boolean stale(Source source, Instant now) {
        return of(source).map(status -> status.staleAt(now,
                                                       periods.of(source)) || skewed(status, now))
                 .or(true);
    }

    private static boolean skewed(SourceStatus status, Instant now) {
        return status.lastSuccess()
                     .filter(at -> Html.future(at, now))
                     .isPresent();
    }

    public Option<Instant> lastSuccess(Source source) {
        return of(source).flatMap(SourceStatus::lastSuccess);
    }

    public boolean anyStale(List<Source> sources, Instant now) {
        return sources.stream()
                      .anyMatch(source -> stale(source, now));
    }

    /// The oldest last success of `sources`, the time a panel resting on all of them is as of: absent (`never`) when
    /// one never succeeded, or when there is none.
    public Option<Instant> oldestSuccess(List<Source> sources) {
        var successes = sources.stream().map(this::lastSuccess).toList();

        return ! successes.isEmpty() && successes.stream()
                                                 .allMatch(Option::isPresent)
               ? Option.from(successes.stream().flatMap(Option::stream).min(Comparator.naturalOrder()))
               : none();
    }

    /// The sources of `hosts`' rows.
    public static List<Source> sourcesOf(List<HostName> hosts) {
        return hosts.stream()
                    .map(Sources::sourceOf)
                    .toList();
    }

    /// A host's rows are stale when its source is, and when its last pass failed: an `UNREACHABLE` or `INCOMPLETE`
    /// host shows its last rows marked stale with their age (spec §5.6).
    public boolean rowsStale(HostName host, Instant now) {
        return stale(sourceOf(host), now) || of(sourceOf(host)).map(Sources::failed)
                                               .or(false);
    }

    /// The configured hosts and every host that has a state, by name; never the Mac, whose rows have a section of their
    /// own.
    public List<HostName> hosts(List<HostName> configured) {
        return Stream.concat(configured.stream(),
                             statuses.stream().map(SourceStatus::source).flatMap(Sources::hostOf))
                     .filter(host -> !HostName.MAC.equals(host))
                     .distinct()
                     .sorted(Comparator.comparing(HostName::value))
                     .toList();
    }

    /// Every source in the order the strip and the header show them: GitHub, the mirror, temper, the fleet alarms, the hosts by name, the
    /// Mac.
    public List<Source> all(List<HostName> configured) {
        return Stream.of(Stream.of(Source.GITHUB, Source.MIRROR, Source.TEMPER, Source.HOSTS),
                         hosts(configured).stream().map(Source::host),
                         Stream.of(Source.MAC))
                     .flatMap(sources -> sources)
                     .toList();
    }

    private static Stream<HostName> hostOf(Source source) {
        return source instanceof Source.Host host
               ? Stream.of(host.name())
               : Stream.empty();
    }

    /// The source a host's rows come from: the Mac's push for [HostName#MAC], else the host's scans.
    public static Source sourceOf(HostName host) {
        return HostName.MAC.equals(host)
               ? Source.MAC
               : Source.host(host);
    }

    private static boolean failed(SourceStatus status) {
        return status.state() instanceof SourceState.Unreachable || status.state() instanceof SourceState.Incomplete;
    }
}
