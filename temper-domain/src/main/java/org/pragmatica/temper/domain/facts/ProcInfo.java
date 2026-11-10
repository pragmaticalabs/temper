package org.pragmatica.temper.domain.facts;

/// One host process as read from `/proc/<pid>/cmdline` (NUL separators replaced by spaces) and
/// `/proc/<pid>/cgroup`.
public record ProcInfo(long pid, String cmdline, String cgroup) {
    public static ProcInfo procInfo(long pid, String cmdline, String cgroup) {
        return new ProcInfo(pid, cmdline, cgroup);
    }
}
