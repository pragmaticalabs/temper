package org.pragmatica.temper.controller.fleet;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.ghapp.VirtualSend;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// L0, the controller's self-check every 60 s (design §3). It FAILS only on:
///
/// - `ssh-banner`: `ssh-keyscan -T 5 127.0.0.1` printed no key line (the 11-02 class: sshd stalled, host up);
/// - `loopback`: the controller's own `GET /api/v1/hosts` over 127.0.0.1 was not answered within 5 s. Any status
///   answers (the probe carries no token, so a 401 is the expected answer): the probe is whether the HTTP stack
///   responds, not whether the route authorises.
///
/// Load and memory pressure are read into `advisory` and NEVER fail it (R2-12): ordinary bb batteries cross any fixed
/// limit, and a failed self-check wakes every waiter (exit 6).
public record SelfCheck(ProcessRunner runner,
                        HttpClient http,
                        URI loopback,
                        Supplier<HeartbeatLine.Advisory> advisory) {
    public static final String BANNER = "ssh-banner";
    public static final String LOOPBACK = "loopback";
    private static final Duration PROBE = Duration.ofSeconds(5);

    /// Before the first check has run: nothing failed yet.
    /// The heartbeat's self-check before the first one completed: ok=false, nothing failed yet (no optimistic
    /// initial ok).
    public static final HeartbeatLine.SelfCheck NOT_RUN = HeartbeatLine.SelfCheck.selfCheck(false,
                                                                                            List.of(),
                                                                                            HeartbeatLine.Advisory.advisory(none(),
                                                                                                                            cores(),
                                                                                                                            none()));

    public static SelfCheck selfCheck(ProcessRunner runner, int port) {
        return new SelfCheck(runner,
                             HttpClient.newBuilder().connectTimeout(PROBE).build(),
                             URI.create("http://127.0.0.1:" + port + "/api/v1/hosts"),
                             SelfCheck::readAdvisory);
    }

    /// Both probes in parallel; the verdict from their outcomes alone.
    public Promise<HeartbeatLine.SelfCheck> run() {
        return Promise.all(banner(), answered()).map((banner, loopback) -> verdict(banner, loopback, advisory.get()));
    }

    /// `ok` iff no probe failed; `advisory` is carried, never judged.
    static HeartbeatLine.SelfCheck verdict(boolean banner, boolean loopback, HeartbeatLine.Advisory advisory) {
        var failed = Stream.of(banner
                               ? Option.<String> none()
                               : some(BANNER),
                               loopback
                               ? Option.<String> none()
                               : some(LOOPBACK))
                           .flatMap(Option::stream)
                           .toList();

        return HeartbeatLine.SelfCheck.selfCheck(failed.isEmpty(), failed, advisory);
    }

    /// FER: a probe that cannot run (ssh-keyscan missing, the process failing) is a failed probe: the verdict IS the
    /// degraded answer, and it is announced.
    private Promise<Boolean> banner() {
        return Command.command(List.of("ssh-keyscan", "-T", "5", "127.0.0.1"),
                               Map.of(),
                               TimeSpan.timeSpan(10).seconds())
                      .async()
                      .flatMap(runner::run)
                      .map(SelfCheck::hasKeyLine)
                      .recover(_ -> false);
    }

    static boolean hasKeyLine(Completed completed) {
        return completed.stdoutText()
                        .lines()
                        .map(String::trim)
                        .anyMatch(line -> !line.isEmpty() && !line.startsWith("#"));
    }

    /// FER: no answer within 5 s (or a refused connection) is the failed probe.
    private Promise<Boolean> answered() {
        return VirtualSend.send(http,
                                HttpRequest.newBuilder(loopback).timeout(PROBE).GET().build(),
                                HttpResponse.BodyHandlers.discarding(),
                                PROBE)
                          .map(_ -> true)
                          .recover(_ -> false);
    }

    /// `/proc/loadavg` and `/proc/pressure/memory` (Linux); elsewhere the JVM's load average and no pressure.
    static HeartbeatLine.Advisory readAdvisory() {
        return HeartbeatLine.Advisory.advisory(readProc("/proc/loadavg").flatMap(SelfCheck::load1)
                                                       .orElse(SelfCheck::jvmLoad),
                                               cores(),
                                               readProc("/proc/pressure/memory").flatMap(SelfCheck::memSomeAvg60));
    }

    /// The first field of `/proc/loadavg`.
    static Option<Double> load1(String loadavg) {
        return Option.from(Stream.of(loadavg.trim().split("\\s+")).findFirst()).flatMap(SelfCheck::number);
    }

    /// `avg60` of the `some` line of `/proc/pressure/memory`.
    static Option<Double> memSomeAvg60(String pressure) {
        return Option.from(pressure.lines()
                                   .filter(line -> line.startsWith("some "))
                                   .flatMap(line -> Stream.of(line.split("\\s+")))
                                   .filter(field -> field.startsWith("avg60="))
                                   .findFirst()).flatMap(field -> number(field.substring("avg60=".length())));
    }

    private static Option<Double> number(String text) {
        return Result.lift(Causes::fromThrowable,
                           () -> Double.parseDouble(text))
                     .option();
    }

    private static Option<String> readProc(String file) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.readString(Path.of(file)))
                     .option();
    }

    private static Option<Double> jvmLoad() {
        return some(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()).filter(load -> load >= 0);
    }

    private static int cores() {
        return Runtime.getRuntime().availableProcessors();
    }
}
