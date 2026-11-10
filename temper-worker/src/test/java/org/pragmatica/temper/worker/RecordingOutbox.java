package org.pragmatica.temper.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.link.Outbox;

/// Records every posted message and reply, in order. While `down` is set, `post` fails as a broken link does and
/// records nothing.
public final class RecordingOutbox implements Outbox {
    private final List<Message> posted = Collections.synchronizedList(new ArrayList<>());
    public final AtomicBoolean down = new AtomicBoolean();

    @Override
    public Result<Unit> post(Message message) {
        return down.get()
               ? Causes.cause("the link is down").result()
               : recorded(message);
    }

    private Result<Unit> recorded(Message message) {
        posted.add(message);
        return Result.unitResult();
    }

    @Override
    public Result<Unit> reply(long requestId, Message message) {
        posted.add(message);
        return Result.unitResult();
    }

    public List<Message> posted() {
        synchronized (posted) {
            return List.copyOf(posted);
        }
    }

    /// Waits up to 5 s for a posted message of `type`.
    public <T extends Message> List<T> awaitPosted(Class<T> type, int count) throws InterruptedException {
        for (var wait = 0; wait < 500; wait++) {
            var found = posted().stream()
                                .filter(type::isInstance)
                                .map(type::cast)
                                .toList();

            if (found.size() >= count) {
                return found;
            }
            Thread.sleep(10);
        }
        return posted().stream()
                       .filter(type::isInstance)
                       .map(type::cast)
                       .toList();
    }
}
