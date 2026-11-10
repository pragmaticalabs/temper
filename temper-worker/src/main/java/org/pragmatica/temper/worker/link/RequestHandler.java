package org.pragmatica.temper.worker.link;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.protocol.message.Message;


/// Handles one controller request (everything except the handshake and lifecycle frames, which the
/// [FrameLoop] owns). A failed promise is answered with `Failure{code, detail}`.
@FunctionalInterface
public interface RequestHandler {
    Promise<Reply> route(Message message);
}
