package org.pragmatica.temper.protocol.message;

import java.util.regex.Pattern;


/// Version handshake (m0-design §4.2): the controller accepts a `Hello` only when the protocol is
/// [#PROTOCOL_VERSION] AND the worker version equals the controller version; otherwise it answers
/// `Incompatible`, deploys the matching worker, and reconnects. Protocol 2 is M1's (m1-design §1.3), protocol 3
/// M4's (m4-design §7); there is no mixed-version window: a protocol-2 worker is `Incompatible`. The handshake is what forces the controller and the worker to deploy together (R14).
public sealed interface Handshake {
    int PROTOCOL_VERSION = 3;
    /// The alphabet of a controller/worker version: it travels as an ssh command argument (`worker-launch run <ver>`,
    /// m0-design §6.4), so both sides check their bundled version against it at start.
    Pattern VERSION_ALPHABET = Pattern.compile("^[0-9A-Za-z.+-]{1,40}$");

    static boolean compatible(Message.Hello hello, String controllerVersion) {
        return hello.protocol() == PROTOCOL_VERSION && controllerVersion.equals(hello.workerVersion());
    }

    /// The controller's answer to `hello`.
    static Message answer(Message.Hello hello, String controllerVersion) {
        return compatible(hello, controllerVersion)
               ? new Message.Welcome(PROTOCOL_VERSION, controllerVersion)
               : new Message.Incompatible(PROTOCOL_VERSION, controllerVersion, mismatch(hello, controllerVersion));
    }

    private static String mismatch(Message.Hello hello, String controllerVersion) {
        return "worker protocol " + hello.protocol()
             + " version " + hello.workerVersion()
             + "; controller expects protocol " + PROTOCOL_VERSION
             + " version " + controllerVersion;
    }

    record unused() implements Handshake {}
}
