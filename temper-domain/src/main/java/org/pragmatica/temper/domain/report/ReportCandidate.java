package org.pragmatica.temper.domain.report;

/// A report file found in the workspace, with its mtime in epoch nanoseconds.
public record ReportCandidate(String path, long mtimeEpochNanos) {
    public static ReportCandidate reportCandidate(String path, long mtimeEpochNanos) {
        return new ReportCandidate(path, mtimeEpochNanos);
    }
}
