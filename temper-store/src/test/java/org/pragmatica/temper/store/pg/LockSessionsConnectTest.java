package org.pragmatica.temper.store.pg;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The lock session's acquire is bounded as a whole: a server that accepts the TCP connection and never answers the
/// startup message fails the call within the bound, and the slot comes back.
class LockSessionsConnectTest {
    @Test
    void aServerThatAcceptsAndNeverAnswers_failsTheCallWithinTheBound_andFreesTheSlot() throws Exception {
        var accepted = new ArrayList<Socket>();

        try (var listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            var acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(listener.accept());
                    }
                } catch (Exception _) {
                // listener closed
                }
            });
            var builder = new NettyConnectibleBuilder();

            builder.hostname("127.0.0.1")
                   .port(listener.getLocalPort())
                   .database("x")
                   .username("x")
                   .password("x")
                   .maxConnections(1);
            var locks = LockSessions.lockSessions(builder.plain(),
                                                  TimeSpan.timeSpan(1).seconds());

            for (var i = 0; i < LockSessions.SLOTS + 1; i++) {
                var started = System.nanoTime();
                var outcome = locks.tryWithLock(1,
                                                "k",
                                                () -> Promise.success("never"))
                                   .await(TimeSpan.timeSpan(20).seconds());

                assertThat(outcome.isFailure()).isTrue();
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("within the bound, not Netty's 30 s")
                          .isLessThan(5000);
            }

            assertThat(locks.slots().availablePermits()).as("every timed-out acquire returned its slot")
                      .isEqualTo(LockSessions.SLOTS);
            acceptor.interrupt();
        } finally {
            List.copyOf(accepted).forEach(socket -> {
                try {
                    socket.close();
                } catch (Exception _) {
                // already closed
                }
            });
        }
    }

    @Test
    void aConnectionThatCompletesAfterTheBound_isClosedAtOnce_andThePermitCameBackWithTheTimeout() throws Exception {
        var late = Promise.<Connection> promise();
        var closes = new AtomicInteger();
        var session = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                                          new Class<?>[]{Connection.class},
                                                          (_, method, _) -> {
                                                              if (method.getName()
                                                                        .equals("close")) {
                                                              closes.incrementAndGet();

                                                              return Promise.success(Unit.unit());
                                                          }

                                                              return null;
                                                          });
        var sessions = (Connectible) Proxy.newProxyInstance(Connectible.class.getClassLoader(),
                                                            new Class<?>[]{Connectible.class},
                                                            (_, method, _) -> method.getName()
                                                                                    .equals("getConnection")
                                                                              ? late
                                                                              : null);
        var locks = LockSessions.lockSessions(sessions,
                                              TimeSpan.timeSpan(300).millis());
        var outcome = locks.tryWithLock(1, "k", () -> Promise.success("never")).await(TimeSpan.timeSpan(10).seconds());

        assertThat(outcome.isFailure()).isTrue();
        assertThat(locks.slots().availablePermits()).as("the timeout returned the permit").isEqualTo(LockSessions.SLOTS);
        assertThat(closes.get()).as("nothing to close before the connection arrives").isZero();
        late.succeed(session);
        assertThat(closes.get()).as("the late connection is closed, not leaked").isEqualTo(1);
        assertThat(locks.slots().availablePermits()).isEqualTo(LockSessions.SLOTS);
    }
}
