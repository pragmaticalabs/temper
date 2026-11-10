package org.pragmatica.temper.worker.unit;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.temper.protocol.message.Message;


/// A unit's processed exit: `UnitExited`, its `UnitManifest` chunks (a unit with a manifest, protocol v2) and its
/// `UnitReport` chunks, in sending order (m0-design §4.2, m1-design §2.5).
public record Collected(Message.UnitExited exited,
                        List<Message.UnitManifest> manifest,
                        List<Message.UnitReport> chunks) {
    public Collected {
        manifest = List.copyOf(manifest);
        chunks = List.copyOf(chunks);
    }

    public static Collected collected(Message.UnitExited exited,
                                      List<Message.UnitManifest> manifest,
                                      List<Message.UnitReport> chunks) {
        return new Collected(exited, manifest, chunks);
    }

    /// `UnitExited` first, then the manifest chunks, then report chunk 0..k (each chunk list ends with `last = true`).
    public List<Message> messages() {
        return Stream.<Message> concat(Stream.<Message> concat(Stream.of(exited),
                                                               manifest.stream()),
                                       chunks.stream())
                     .toList();
    }
}
