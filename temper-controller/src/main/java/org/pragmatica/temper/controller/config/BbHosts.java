package org.pragmatica.temper.controller.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.config.ControllerConfig.BbHost;
import org.pragmatica.temper.controller.config.ControllerConfig.BbLink;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// `[fleet] bbHosts`, `bbScanKey` and `bbKnownHosts`. Each entry is `<host>` or
/// `<host>=<user>@<address>`; a malformed entry, a duplicate name, a name that is also a `[[hosts]]` name, or an `=`
/// entry without the scan key and known_hosts all refuse the start.
final class BbHosts {
    private static final Pattern ENTRY = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]{0,63})(?:=([a-z_][a-z0-9_-]{0,31})@([A-Za-z0-9.:-]+))?$");

    private BbHosts() {}

    /// The parsed entries; `linked` = the names of `[[hosts]]`.
    static Result<List<BbHost>> entries(TomlDocument doc, Set<String> linked) {
        return raw(doc).flatMap(entries -> parsed(entries, linked));
    }

    private static Result<List<String>> raw(TomlDocument doc) {
        var present = option(doc.sections().get("fleet")).filter(table -> table.containsKey("bbHosts")).isPresent();

        return doc.getStringList("fleet", "bbHosts")
                  .map(Result::success)
                  .or(() -> present
                            ? invalid("bbHosts", "must be an array of strings")
                            : success(List.of()));
    }

    private static Result<List<BbHost>> parsed(List<String> entries, Set<String> linked) {
        return Result.allOf(entries.stream().map(BbHosts::entry).toList()).flatMap(hosts -> distinct(hosts, linked));
    }

    private static Result<BbHost> entry(String text) {
        var matcher = ENTRY.matcher(text);

        return matcher.matches()
               ? success(BbHost.bbHost(matcher.group(1),
                                       option(matcher.group(2)).map(user -> BbLink.bbLink(user, matcher.group(3)))))
               : invalid("bbHosts", "malformed entry '" + text + "'");
    }

    private static Result<List<BbHost>> distinct(List<BbHost> hosts, Set<String> linked) {
        var seen = new HashSet<String>();

        return hosts.stream()
                    .filter(host -> linked.contains(host.name()) || !seen.add(host.name()))
                    .findFirst()
                    .map(host -> BbHosts.<List<BbHost>> invalid("bbHosts",
                                                                linked.contains(host.name())
                                                                ? host.name() + " is also a [[hosts]] name"
                                                                : "duplicate host " + host.name()))
                    .orElseGet(() -> success(hosts));
    }

    /// The scan key (or known_hosts) when any entry has the `=` form; absent then refuses the start.
    static Result<Option<Path>> path(TomlDocument doc, String key, List<BbHost> hosts, Path home) {
        var needed = hosts.stream().anyMatch(host -> host.link()
                                                         .isPresent());

        return TomlValues.optionalText(doc, "fleet", key).flatMap(raw -> raw.map(text -> success(some(TomlValues.expand(text,
                                                                                                                        home))))
                                                                            .or(() -> needed
                                                                                      ? ConfigError.Missing.FACTORY.apply("fleet",
                                                                                                                          key).<Option<Path>> result()
                                                                                      : success(none())));
    }

    private static <T> Result<T> invalid(String key, String reason) {
        return ConfigError.Invalid.FACTORY.apply("fleet", key, cause(reason)).result();
    }
}
