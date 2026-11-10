package org.pragmatica.kiln.service.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.vo.Url;

import static org.pragmatica.lang.Option.none;


/// `~/.config/kiln/kiln.toml` (kiln-f1-design §6 row "Config"), parsed by [KilnConfigLoader]. Paths are absolute (a
/// leading `~/` is expanded; a relative path is refused). Secrets are not held here: the tokens and the database password stay in their files until
/// [KilnSecrets] reads them.
public record KilnConfig(Server server,
                         Database db,
                         GitHub github,
                         Mirror mirror,
                         Temper temper,
                         Scan scan,
                         Mac mac,
                         List<Host> hosts,
                         Option<Conflicts> conflicts) {
    public KilnConfig {
        hosts = List.copyOf(hosts);
    }

    /// Without the conflict watch: only [KilnConfigLoader] turns it on, so no test config writes an events.log.
    public KilnConfig(Server server,
                      Database db,
                      GitHub github,
                      Mirror mirror,
                      Temper temper,
                      Scan scan,
                      Mac mac,
                      List<Host> hosts) {
        this(server, db, github, mirror, temper, scan, mac, hosts, none());
    }

    public static KilnConfig kilnConfig(Server server,
                                        Database db,
                                        GitHub github,
                                        Mirror mirror,
                                        Temper temper,
                                        Scan scan,
                                        Mac mac,
                                        List<Host> hosts) {
        return new KilnConfig(server, db, github, mirror, temper, scan, mac, hosts);
    }

    /// This config with the conflict watch's settings; none keeps the watch off.
    public KilnConfig withConflicts(Option<Conflicts> settings) {
        return new KilnConfig(server, db, github, mirror, temper, scan, mac, hosts, settings);
    }

    /// `[conflicts]` (temper #100): the release branch the merge-tree early warning checks open PRs against (default: the
    /// first `[github] bases` entry), the events.log it appends to (default `~/temper/conflicts/events.log`), and the
    /// scratch directory whose `objects` receive what merge-tree writes, so the mirror is never written (default
    /// `~/.local/share/kiln/conflicts-scratch`). The watch reads `[mirror] path`; it never fetches on its own.
    public record Conflicts(BranchName branch, Path eventsLog, Path scratch) {
        public static Conflicts conflicts(BranchName branch, Path eventsLog, Path scratch) {
            return new Conflicts(branch, eventsLog, scratch);
        }
    }

    /// `[server]`: the port, always served on the loopback address. `bind` (owner 2026-11-05) is one further IPv4 address
    /// served on the same port, for reads from the LAN (writes stay loopback-only, Q-1); `hostNames` are the extra `Host`
    /// names it answers to, and are empty without `bind`.
    public record Server(int port, Option<String> bind, List<String> hostNames) {
        public Server {
            hostNames = List.copyOf(hostNames);
        }

        public static Server server(int port) {
            return new Server(port, Option.none(), List.of());
        }

        public static Server server(int port, Option<String> bind, List<String> hostNames) {
            return new Server(port, bind, hostNames);
        }
    }

    /// `[db]`: the kiln-pg connection; the password stays in its file. `highWaterFile` is the change feed's high-water
    /// mark outside the database (`<epoch> <last id>`), which detects a restored database at start (CI interface T1).
    public record Database(String host, int port, String name, String user, Path passwordFile, Path highWaterFile) {
        public static Database database(String host,
                                        int port,
                                        String name,
                                        String user,
                                        Path passwordFile,
                                        Path highWaterFile) {
            return new Database(host, port, name, user, passwordFile, highWaterFile);
        }
    }

    /// `[github]`: `repo` is `owner/name`; `bases` are the configured bases (spec §4.3); the credential is `tokenFile`,
    /// or `[github.app]` (`app`), or neither with `anonymous = true` (60 requests an hour, Q-7); [KilnSecrets] and
    /// `Main` refuse any other combination. `cycleDeadline` frees the poller's next tick from a cycle that
    /// never ends (WP6). `others` are the `[[repos]]` tables (CI interface T1 WP2): repositories read for the change feed
    /// only, never for the dashboard's tables. `hourlyCap` is the most requests kiln itself sends in one GitHub rate
    /// window, and `reserve` the remaining count at or below which it sends none: the owner account's 5,000/h is shared
    /// with the whole fleet.
    public record GitHub(String repo,
                         List<BranchName> bases,
                         Option<Path> tokenFile,
                         Duration cycleDeadline,
                         List<Repo> others,
                         int hourlyCap,
                         int reserve,
                         Option<App> app,
                         boolean anonymous) {
        /// 1,428 of kiln's own reads plus the 72 calls an hour of the ruleset and team drift reads: the cap is still the binding guard against a kiln bug, whatever limit GitHub reports.
        public static final int DEFAULT_HOURLY_CAP = 1572;
        public static final int DEFAULT_RESERVE = 1500;

        public GitHub {
            bases = List.copyOf(bases);
            others = List.copyOf(others);
        }

        public static GitHub gitHub(String repo,
                                    List<BranchName> bases,
                                    Option<Path> tokenFile,
                                    Duration cycleDeadline) {
            return gitHub(repo, bases, tokenFile, cycleDeadline, List.of());
        }

        public static GitHub gitHub(String repo,
                                    List<BranchName> bases,
                                    Option<Path> tokenFile,
                                    Duration cycleDeadline,
                                    List<Repo> others) {
            return gitHub(repo, bases, tokenFile, cycleDeadline, others, DEFAULT_HOURLY_CAP, DEFAULT_RESERVE);
        }

        public static GitHub gitHub(String repo,
                                    List<BranchName> bases,
                                    Option<Path> tokenFile,
                                    Duration cycleDeadline,
                                    List<Repo> others,
                                    int hourlyCap,
                                    int reserve) {
            return gitHub(repo, bases, tokenFile, cycleDeadline, others, hourlyCap, reserve, none(), false);
        }

        public static GitHub gitHub(String repo,
                                    List<BranchName> bases,
                                    Option<Path> tokenFile,
                                    Duration cycleDeadline,
                                    List<Repo> others,
                                    int hourlyCap,
                                    int reserve,
                                    Option<App> app,
                                    boolean anonymous) {
            return new GitHub(repo, bases, tokenFile, cycleDeadline, others, hourlyCap, reserve, app, anonymous);
        }

        /// Every repository kiln reads, `[github] repo` first: the single `[github]` form is the one-element list.
        public List<Repo> repos() {
            return Stream.concat(Stream.of(Repo.repo(repo, bases)),
                                 others.stream())
                         .toList();
        }
    }

    /// `[github.app]`: the kiln App's non-secret ids and the path of its private key (the
    /// content is read at each mint, never held here).
    public record App(String clientId, long installationId, Path keyFile) {
        public static App app(String clientId, long installationId, Path keyFile) {
            return new App(clientId, installationId, keyFile);
        }
    }

    /// One `[[repos]]` table (or `[github] repo bases`): `owner/name` and its configured bases.
    public record Repo(String name, List<BranchName> bases) {
        public Repo {
            bases = List.copyOf(bases);
        }

        public static Repo repo(String name, List<BranchName> bases) {
            return new Repo(name, bases);
        }
    }

    /// `[mirror]`: the origin kiln's bare mirror fetches from, and where the mirror lives (spec §5.1).
    public record Mirror(String origin, Path path) {
        public static Mirror mirror(String origin, Path path) {
            return new Mirror(origin, path);
        }
    }

    /// `[temper]`: temper's API and its read-token file, named by path (spec §3), and the reader's cycle deadline.
    public record Temper(Url url, Path readTokenFile, Duration cycleDeadline) {
        public static Temper temper(Url url, Path readTokenFile, Duration cycleDeadline) {
            return new Temper(url, readTokenFile, cycleDeadline);
        }
    }

    /// `[scan]`: the hot pass period and window, the full pass's local start time, the gap between size reads, the age
    /// after which an unowned checkout is stale (spec §5.3, §5.5), the deadline of one serial step of a host (an ssh
    /// session and its store write), and the ssh transport's own timeout, always below half the step deadline, so a
    /// session that never ends is killed and recorded as the host's failure while the step still has time to write it.
    public record Scan(Duration hot,
                       Duration hotWindow,
                       LocalTime fullAt,
                       Duration sizeGap,
                       Duration staleAfter,
                       Duration stepDeadline,
                       Duration transportTimeout) {
        public static Scan scan(Duration hot,
                                Duration hotWindow,
                                LocalTime fullAt,
                                Duration sizeGap,
                                Duration staleAfter,
                                Duration stepDeadline,
                                Duration transportTimeout) {
            return new Scan(hot, hotWindow, fullAt, sizeGap, staleAfter, stepDeadline, transportTimeout);
        }
    }

    /// `[mac]`: the roots the Mac's push lists (unexpanded, as `kiln-scan scan` was given them on the Mac). A complete
    /// push deletes only under these roots; with none, a push deletes nothing.
    public record Mac(List<String> roots) {
        public Mac {
            roots = List.copyOf(roots);
        }

        public static Mac mac(List<String> roots) {
            return new Mac(roots);
        }
    }

    /// `[[hosts]]`: a host kiln scans over ssh with the forced-command key, and the roots it passes to `kiln-scan` as
    /// words (Q-3), unexpanded: the script expands `~` and the glob on the host.
    public record Host(HostName name, String address, String user, Path key, Path knownHosts, List<String> roots) {
        public Host {
            roots = List.copyOf(roots);
        }

        public static Host host(HostName name,
                                String address,
                                String user,
                                Path key,
                                Path knownHosts,
                                List<String> roots) {
            return new Host(name, address, user, key, knownHosts, roots);
        }
    }
}
