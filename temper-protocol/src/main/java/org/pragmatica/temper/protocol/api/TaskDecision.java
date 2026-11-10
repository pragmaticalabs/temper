package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;


/// Bodies of the maintainer's task commands (m3-design §5.5, §11.1; full token).
public sealed interface TaskDecision {
    /// `POST tasks/{id}/rule`: the option, the ruling text, the `know:` SHA, extra rounds (T11).
    record Rule(String option, String text, Option<String> know, Option<Integer> extraRounds) implements TaskDecision {}

    /// `POST tasks/{id}/close`: `merged` (T15) or `abandoned` (T16).
    record Close(String how) implements TaskDecision {}

    /// `POST tasks/{id}/bounce`: back to FIX with the maintainer's item (T14).
    record Bounce(String item) implements TaskDecision {}
}
