package org.pragmatica.kiln.core.source;

import java.util.regex.Pattern;

import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;
import static org.pragmatica.lang.Result.success;


/// Where a fact comes from (spec §5.6, §6 header). Each source has one state ([SourceState]) and one key, which is the
/// `source_state` row's primary key: `github`, `mirror`, `temper`, `hosts`, `host:<name>` or `mac`. [#source] parses a key
/// back, so a stored row always names the source it was written for.
public sealed interface Source {
    String HOST_PREFIX = "host:";
    Pattern HOST_KEY = Pattern.compile("^host:.*$");
    String key();

    /// GitHub's REST API (spec §4).
    record GitHub() implements Source {
        @Override
        public String key() {
            return "github";
        }
    }

    /// kiln's bare mirror of GitHub (spec §5.1).
    record Mirror() implements Source {
        @Override
        public String key() {
            return "mirror";
        }
    }

    /// temper's read API (spec §4.5).
    record Temper() implements Source {
        @Override
        public String key() {
            return "temper";
        }
    }

    /// temper's fleet alarms (`GET /api/v1/fleet/alarms`). Not a `host:<name>` source.
    record Hosts() implements Source {
        @Override
        public String key() {
            return "hosts";
        }
    }

    /// A build host scanned over ssh (spec §5.2).
    record Host(HostName name) implements Source {
        @Override
        public String key() {
            return HOST_PREFIX + name.value();
        }
    }

    /// The Mac's pushed scans (spec §5.4).
    record Mac() implements Source {
        @Override
        public String key() {
            return HostName.MAC.value();
        }
    }

    Source GITHUB = new GitHub();
    Source MIRROR = new Mirror();
    Source TEMPER = new Temper();
    Source HOSTS = new Hosts();
    Source MAC = new Mac();

    static Source host(HostName name) {
        return new Host(name);
    }

    /// The source a key names; anything else is refused, `host:mac` included (by [HostName#hostName]).
    static Result<Source> source(String key) {
        return Verify.ensure(key, Verify.Is::present, invalid("source key")).flatMap(Source::parsed);
    }

    private static Result<Source> parsed(String key) {
        return switch (key) {
            case "github" -> success(GITHUB);
            case "mirror" -> success(MIRROR);
            case "temper" -> success(TEMPER);
            case "hosts" -> success(HOSTS);
            case "mac" -> success(MAC);
            default -> hostKey(key);
        };
    }

    private static Result<Source> hostKey(String key) {
        return Verify.ensure(key,
                             Verify.Is::matches,
                             HOST_KEY,
                             invalid("source key"))
                     .flatMap(prefixed -> HostName.hostName(prefixed.substring(HOST_PREFIX.length())))
                     .map(Source::host);
    }
}
