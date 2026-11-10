package org.pragmatica.temper.domain.gate;

import java.util.List;

import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The expected [GateEvidence] of a table row, by job id; [#check] compares every member.
record Expected(Option<Judge> judge,
                GateAnswer answer,
                Option<Long> answering,
                Option<Long> previous,
                List<Long> conflict,
                Option<Long> running,
                List<Long> older,
                List<Long> judgeChanged,
                List<Long> unjudged) {
    static Expected answer(GateAnswer answer, Judge judge, long answering) {
        return new Expected(some(judge), answer, some(answering), none(), List.of(), none(), List.of(), List.of(), List.of());
    }

    static Expected conflict(Judge judge, Long... votes) {
        return new Expected(some(judge), GateAnswer.CONFLICT, none(), none(), List.of(votes), none(), List.of(), List.of(), List.of());
    }

    static Expected nothing() {
        return new Expected(none(), GateAnswer.NONE, none(), none(), List.of(), none(), List.of(), List.of(), List.of());
    }

    Expected previous(long id) {
        return new Expected(judge, answer, answering, some(id), conflict, running, older, judgeChanged, unjudged);
    }

    Expected running(long id) {
        return new Expected(judge, answer, answering, previous, conflict, some(id), older, judgeChanged, unjudged);
    }

    Expected older(Long... ids) {
        return new Expected(judge, answer, answering, previous, conflict, running, List.of(ids), judgeChanged, unjudged);
    }

    Expected judgeChanged(Long... ids) {
        return new Expected(judge, answer, answering, previous, conflict, running, older, List.of(ids), unjudged);
    }

    Expected unjudged(Long... ids) {
        return new Expected(judge, answer, answering, previous, conflict, running, older, judgeChanged, List.of(ids));
    }

    void check(GateEvidence evidence) {
        assertThat(evidence.answer()).as("answer").isEqualTo(answer);
        assertThat(evidence.judge()).as("judge").isEqualTo(judge);
        assertThat(Gates.id(evidence.answering())).as("answering").isEqualTo(answering);
        assertThat(Gates.id(evidence.previous())).as("previous").isEqualTo(previous);
        assertThat(Gates.ids(evidence.conflict())).as("conflict").isEqualTo(conflict);
        assertThat(evidence.running().map(job -> job.value())).as("running").isEqualTo(running);
        assertThat(Gates.ids(evidence.older())).as("older").isEqualTo(older);
        assertThat(Gates.ids(evidence.judgeChanged())).as("judgeChanged").isEqualTo(judgeChanged);
        assertThat(Gates.jobIds(evidence.unjudged())).as("unjudged").isEqualTo(unjudged);
    }
}
