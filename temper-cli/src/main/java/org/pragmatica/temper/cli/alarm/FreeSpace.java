package org.pragmatica.temper.cli.alarm;

import java.nio.file.Path;

import org.pragmatica.temper.cli.config.AlarmConfig;


/// The usable bytes on the file store holding `path`, as measured at this check.
public record FreeSpace(Path path, long usableBytes) {
    public static FreeSpace freeSpace(Path path, long usableBytes) {
        return new FreeSpace(path, usableBytes);
    }

    String described() {
        return String.format("%s has %.1f GiB free", path, (double) usableBytes / AlarmConfig.GIB);
    }
}
