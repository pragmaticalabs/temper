package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.ReadPointStamp;


/// The read-point stamp file `DIR/.temper-read-point` (m3-design §6.5, R-M3-24): `{task, epoch, readPoint, at}`, written
/// by `temper task claim --checkout DIR` and read back into evidence.
public record ReadPointStampDto(long task, long epoch, String readPoint, Instant at) {
    public static ReadPointStampDto readPointStampDto(ReadPointStamp stamp) {
        return new ReadPointStampDto(stamp.task(),
                                     stamp.epoch(),
                                     stamp.readPoint().value(),
                                     stamp.at());
    }

    public Result<ReadPointStamp> toDomain() {
        return Sha.sha(readPoint).map(sha -> ReadPointStamp.readPointStamp(task, epoch, sha, at));
    }
}
