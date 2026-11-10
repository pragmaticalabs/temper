package org.pragmatica.temper.store.api;

/// One `claude` process of a Mac post: its pid, its start (epoch seconds UTC, H5) and its label.
public record MacProcess(int pid, long start, String label) {
    public static MacProcess macProcess(int pid, long start, String label) {
        return new MacProcess(pid, start, label);
    }
}
