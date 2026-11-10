package org.pragmatica.temper.worker.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Every bound the worker applies to its own work. `workerTimings()` is production; tests shrink the waits.
///
/// - `heartbeat`: `Heartbeat` period and cgroup sampling period (m0-design §7.2, §7.7).
/// - `lockWait`: how long `worker.lock` is waited for before exiting 75 (m0-design §7.2).
/// - `reapWait` / `reapPoll`: the Testcontainers reap window after a unit exits (m0-design §12.4). The reap checks the
///   window after each `docker ps`, so it can last the window plus one poll plus a `docker ps`: about 62 s.
/// - `voidedReapWait`: the reap window of a unit voided for `port-group-lost` (5 s, so about 7 s in all: with a 10 s
///   heartbeat and the 2 s observation gap, detection plus this stays well inside A11's 30 s bound for
///   `VOID(port-group-lost)`, m1-design §9.1). A `disk` void keeps the unit running and uses `reapWait`.
/// - `dockerCall`: one short docker CLI call (inspect, kill, rm, network, ps).
/// - `imageBuild`: `docker build` of the runner image (the controller allows 20 min for such a `StartUnit`).
/// - `gitCall`: one git call of workspace preparation (the controller allows 15 min for `PrepareJob`).
/// - `jcmdCall`: one `docker exec … jcmd` call at a timeout (m0-design §7.5).
/// - `collectCall`: `docker logs` streaming at exit.
/// - `pomProbe`: the effective-POM auxiliary container, online (m1-design §2.3; about 20-60 s, inside the
///   controller's 20 min `StartUnit` bound).
/// - `hold`: the bb-hold waits ([HoldTimings]).
public record WorkerTimings(TimeSpan heartbeat,
                            TimeSpan lockWait,
                            TimeSpan reapWait,
                            TimeSpan voidedReapWait,
                            TimeSpan reapPoll,
                            TimeSpan dockerCall,
                            TimeSpan imageBuild,
                            TimeSpan gitCall,
                            TimeSpan jcmdCall,
                            TimeSpan collectCall,
                            TimeSpan pomProbe,
                            HoldTimings hold) {
    /// The bb-hold waits (m1-design §1.2-§1.7; the values marked [P] there).
    ///
    /// - `grantPoll`: how often a `GrantWatcher` looks at `bbhold.err`, the holder and the cancelled set (1 s).
    /// - `containerWait`: how long a granted unit's container may take to appear before a holder that exited is a
    ///   failed `docker run` (30 s).
    /// - `duplicateRetry`: the pause before the one retry of an enqueue bb-hold refused as a duplicate (10 s).
    /// - `observationGap`: the gap between a `HoldMonitor` round's two holder observations (2 s, `HoldRules`).
    /// - `holderExit`: how long a cancel waits for a signalled holder to exit (10 s).
    /// - `absentRecheck`: the startup scan's second inspect of a dead holder's absent container (10 s).
    public record HoldTimings(TimeSpan grantPoll,
                              TimeSpan containerWait,
                              TimeSpan duplicateRetry,
                              TimeSpan observationGap,
                              TimeSpan holderExit,
                              TimeSpan absentRecheck) {
        public static HoldTimings holdTimings() {
            return new HoldTimings(timeSpan(1).seconds(),
                                   timeSpan(30).seconds(),
                                   timeSpan(10).seconds(),
                                   timeSpan(2).seconds(),
                                   timeSpan(10).seconds(),
                                   timeSpan(10).seconds());
        }
    }

    /// Production timings.
    public static WorkerTimings workerTimings() {
        return new WorkerTimings(timeSpan(10).seconds(),
                                 timeSpan(30).seconds(),
                                 timeSpan(60).seconds(),
                                 timeSpan(5).seconds(),
                                 timeSpan(2).seconds(),
                                 timeSpan(60).seconds(),
                                 timeSpan(18).minutes(),
                                 timeSpan(14).minutes(),
                                 timeSpan(60).seconds(),
                                 timeSpan(10).minutes(),
                                 timeSpan(15).minutes(),
                                 HoldTimings.holdTimings());
    }

    /// The same timings with a different Testcontainers reap window.
    public WorkerTimings withReap(TimeSpan wait, TimeSpan poll) {
        return new WorkerTimings(heartbeat,
                                 lockWait,
                                 wait,
                                 voidedReapWait,
                                 poll,
                                 dockerCall,
                                 imageBuild,
                                 gitCall,
                                 jcmdCall,
                                 collectCall,
                                 pomProbe,
                                 hold);
    }

    /// The same timings with different bb-hold waits.
    public WorkerTimings withHold(HoldTimings waits) {
        return new WorkerTimings(heartbeat,
                                 lockWait,
                                 reapWait,
                                 voidedReapWait,
                                 reapPoll,
                                 dockerCall,
                                 imageBuild,
                                 gitCall,
                                 jcmdCall,
                                 collectCall,
                                 pomProbe,
                                 waits);
    }
}
