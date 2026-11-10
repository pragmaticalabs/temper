package org.pragmatica.temper.domain.task;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.Sha;


/// What an accepted evidence document decided (m3-design §7, validated by WP-2 before [TaskMachine] sees it, T4, T9,
/// T9′, T13): the `next` stage it asks for, a VERIFY verdict, whether a FIX's only open item is a `merge-forward`
/// pushed as a proven MERGE-FORWARD (the one FIX → CI path, §10.3), the head and PR temper derived, and the items
/// still open (a round-cap park carries them, §7.6).
public record EvidenceOutcome(Option<Stage> next,
                              Verdict verdict,
                              boolean mergeForwardOnly,
                              Option<Sha> head,
                              Option<Integer> pr,
                              List<String> openItems) {
    /// A VERIFY verdict; every other stage's evidence has none.
    public enum Verdict {
        NONE,
        PASS,
        FAIL
    }

    public EvidenceOutcome {
        openItems = List.copyOf(openItems);
    }

    public static EvidenceOutcome evidenceOutcome(Option<Stage> next,
                                                  Verdict verdict,
                                                  boolean mergeForwardOnly,
                                                  Option<Sha> head,
                                                  Option<Integer> pr,
                                                  List<String> openItems) {
        return new EvidenceOutcome(next, verdict, mergeForwardOnly, head, pr, openItems);
    }
}
