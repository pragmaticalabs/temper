package org.pragmatica.temper.protocol;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.BuildVersion;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;

import static org.assertj.core.api.Assertions.assertThat;

/// m0-design §4.2: accept only the current protocol AND worker version == controller version. M4 is protocol 3
/// (m4-design §7), so a protocol-2 worker (M1-M3) is answered `Incompatible` and redeployed: there is no mixed-version
/// window, and no v2 frame (one without an attempt) ever reaches a v3 controller.
class HandshakeTest {
    private static Message.Hello hello(int protocol, String version) {
        var facts = new HostFactsDto(0, 0, 1, 0, 0, Option.none(), Option.none(), 0, 0, 0, Instant.EPOCH, List.of());

        return new Message.Hello(protocol, version, "bigboy", 1000, 1000, 998, 1, facts, List.of());
    }

    @Test
    void protocolVersion_isThree() {
        assertThat(Handshake.PROTOCOL_VERSION).isEqualTo(3);
    }

    @Test
    void answer_welcomes_matchingProtocolAndVersion() {
        assertThat(Handshake.answer(hello(3, "0.1.0"), "0.1.0")).isEqualTo(new Message.Welcome(3, "0.1.0"));
    }

    @Test
    void answer_isIncompatible_forVersionOrProtocolMismatch() {
        assertThat(Handshake.answer(hello(3, "0.0.9"), "0.1.0")).isInstanceOfSatisfying(Message.Incompatible.class, incompatible -> {
            assertThat(incompatible.expectedProtocol()).isEqualTo(Handshake.PROTOCOL_VERSION);
            assertThat(incompatible.expectedWorkerVersion()).isEqualTo("0.1.0");
        });
        assertThat(Handshake.answer(hello(1, "0.1.0"), "0.1.0")).as("an M0 worker").isInstanceOf(Message.Incompatible.class);
        assertThat(Handshake.answer(hello(4, "0.1.0"), "0.1.0")).isInstanceOf(Message.Incompatible.class);
        assertThat(Handshake.answer(hello(3, null), "0.1.0")).isInstanceOf(Message.Incompatible.class);
    }

    /// m4-design §7: a protocol-2 worker of the very same build version is still refused (and redeployed), whatever
    /// else matches; the answer names protocol 3.
    @Test
    void answer_isIncompatible_forAProtocol2WorkerOfTheSameVersion() {
        assertThat(Handshake.answer(hello(2, "0.1.0"), "0.1.0")).isInstanceOfSatisfying(Message.Incompatible.class, incompatible -> {
            assertThat(incompatible.expectedProtocol()).isEqualTo(3);
            assertThat(incompatible.reason()).isEqualTo("worker protocol 2 version 0.1.0; controller expects protocol 3 version 0.1.0");
        });
    }

    /// D-6: two builds of one `project.version` differ by judge, so a stale worker is `Incompatible` and redeployed.
    @Test
    void answer_isIncompatible_forTheSameProjectVersionOfAnotherJudge() {
        var controller = BuildVersion.buildVersion("0.1.0-SNAPSHOT", "1111111111111111111111111111111111111111").unwrap();
        var stale = BuildVersion.buildVersion("0.1.0-SNAPSHOT", "2222222222222222222222222222222222222222").unwrap();

        assertThat(Handshake.answer(hello(3, stale), controller)).isInstanceOfSatisfying(Message.Incompatible.class, incompatible ->
            assertThat(incompatible.expectedWorkerVersion()).isEqualTo(controller));
        assertThat(Handshake.answer(hello(3, controller), controller)).isEqualTo(new Message.Welcome(3, controller));
    }
}
