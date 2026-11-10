package org.pragmatica.temper.worker.docker;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.protocol.message.ContainerStatus;

import static org.pragmatica.lang.Option.none;


/// Pure parsers for docker CLI output. Each takes the raw stdout text of one call.
public sealed interface DockerOutput {
    Pattern IPV4 = Pattern.compile("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");
    Pattern FULL_ID = Pattern.compile("^[0-9a-f]{64}$");
    /// Fields of one `docker network inspect` row, see [#orphanNetworks].
    int NETWORK_FIELDS = 5;
    /// What docker prints on stderr when the named object does not exist: `No such object|container|image` for
    /// inspect, rm and kill; `network <name> not found` for the network commands.
    Pattern NO_SUCH = Pattern.compile("(?i)no such (object|container|image|network)|network \\S+ not found");

    /// `docker inspect -f {{.State.Status}}` mapped for re-attach (m0-design §7.2). A `created` container never
    /// ran a step, so it counts as absent: there is nothing to collect from it.
    static ContainerStatus containerStatus(String stateStatus) {
        return switch (stateStatus.trim()) {
            case "running", "restarting", "paused" -> ContainerStatus.RUNNING;
            case "exited", "dead", "removing" -> ContainerStatus.EXITED;
            default -> ContainerStatus.ABSENT;
        };
    }

    /// The first IPv4 gateway of `{{range .IPAM.Config}}{{.Gateway}} {{end}}`: a dual-stack network also lists an
    /// IPv6 gateway, and the order is not guaranteed (the S1 spike hit this).
    static Option<String> ipv4Gateway(String gateways) {
        return Option.from(words(gateways).stream().filter(word -> IPV4.matcher(word).matches()).findFirst());
    }

    /// Full container ids, one per line (`docker ps -q --no-trunc`).
    static Set<String> containerIds(String text) {
        return words(text).stream()
                    .filter(word -> FULL_ID.matcher(word).matches())
                    .collect(Collectors.toUnmodifiableSet());
    }

    /// `docker ps -a --format '{{.ID}} {{.Label "org.testcontainers.sessionId"}}'`; a container without a session
    /// label gets an empty session (it can never be attributed to a unit).
    static List<TcContainer> testcontainers(String text) {
        return text.lines()
                   .map(String::trim)
                   .filter(line -> !line.isEmpty())
                   .map(DockerOutput::tcContainer)
                   .toList();
    }

    /// `docker network inspect -f` rows (`<created unix seconds>|<container count>|<temper.job>|<temper.unit>|<name>`),
    /// kept when the network has no container and was created before `createdBefore`; a line that does not parse is
    /// not a network temper can judge and is dropped (a network is never removed on a guess).
    static List<OrphanNetwork> orphanNetworks(String text, Instant createdBefore) {
        return text.lines()
                   .map(String::trim)
                   .flatMap(line -> orphanNetwork(line, createdBefore).stream())
                   .toList();
    }

    private static Option<OrphanNetwork> orphanNetwork(String line, Instant createdBefore) {
        var parts = line.split("\\|", NETWORK_FIELDS);

        return parts.length == NETWORK_FIELDS
               ? orphanOf(parts, createdBefore)
               : none();
    }

    private static Option<OrphanNetwork> orphanOf(String[] parts, Instant createdBefore) {
        return Number.parseLong(parts[0])
                     .option()
                     .map(Instant::ofEpochSecond)
                     .filter(created -> created.isBefore(createdBefore))
                     .flatMap(_ -> Number.parseInt(parts[1]).option())
                     .filter(containers -> containers == 0)
                     .map(_ -> OrphanNetwork.orphanNetwork(parts[4], parts[2], parts[3]));
    }

    /// Network ids or names, one per line (`docker network ls -q`).
    static List<String> networkIds(String text) {
        return words(text);
    }

    /// A first-line integer (`docker wait`, `{{.State.ExitCode}}`).
    static Option<Integer> exitCode(String text) {
        return Option.from(text.lines().map(String::trim).filter(line -> line.matches("^-?[0-9]{1,10}$")).findFirst()).flatMap(DockerOutput::integerOf);
    }

    /// Whether a failed inspect said the object does not exist (as opposed to the daemon being unreachable).
    static boolean noSuchObject(String stderr) {
        return NO_SUCH.matcher(stderr).find();
    }

    private static Option<Integer> integerOf(String digits) {
        return Number.parseInt(digits).option();
    }

    private static TcContainer tcContainer(String line) {
        var parts = line.split("\\s+", 2);

        return TcContainer.tcContainer(parts[0],
                                       parts.length > 1
                                       ? parts[1].trim()
                                       : "");
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.trim().split("\\s+"))
                     .filter(word -> !word.isEmpty())
                     .toList();
    }

    record unused() implements DockerOutput {}
}
