package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;

import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.TemperStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [TemperStore] over `temper_digest` (verbatim, never decoded) and `temper_gate`: the latest read per PR, an answer or
/// the field a broken answer lacked (`TEMPER-SCHEMA(<field>)`). An answer keeps the sha temper named (`answer_sha`), so
/// the view shows it only for the head it answered.
record PgTemperStore(Db db) implements TemperStore {
    private static final String DIGEST = "INSERT INTO temper_digest (id, text, observed_at) VALUES (1, $1, $2) "
                                       + "ON CONFLICT (id) DO UPDATE SET text = EXCLUDED.text, observed_at = EXCLUDED.observed_at";

    private static final String GATE = "INSERT INTO temper_gate (pr, head_sha, answer, job, judge, schema_field, observed_at, answer_sha) "
                                     + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (pr) DO UPDATE SET head_sha = EXCLUDED.head_sha, "
                                     + "answer = EXCLUDED.answer, job = EXCLUDED.job, judge = EXCLUDED.judge, "
                                     + "schema_field = EXCLUDED.schema_field, observed_at = EXCLUDED.observed_at, "
                                     + "answer_sha = EXCLUDED.answer_sha";

    /// A read as its columns.
    record Stored(Option<String> answer,
                  Option<Long> job,
                  Option<String> judge,
                  Option<String> schemaField,
                  Option<String> answerSha) {}

    @Override
    public Promise<Unit> digest(String text, Instant at) {
        return Sql.execute(db.executor(), DIGEST, text, Sql.utc(at));
    }

    @Override
    public Promise<Unit> gate(PrNumber pr, Sha head, GateRead read, Instant at) {
        var stored = stored(read);

        return Sql.execute(db.executor(),
                           GATE,
                           pr.value(),
                           head.value(),
                           Sql.nullable(stored.answer()),
                           Sql.nullable(stored.job()),
                           Sql.nullable(stored.judge()),
                           Sql.nullable(stored.schemaField()),
                           Sql.utc(at),
                           Sql.nullable(stored.answerSha()));
    }

    static Stored stored(GateRead read) {
        return switch (read) {
            case GateRead.Answered(var sha, var answer, var job, var judge) -> new Stored(some(answer),
                                                                                          job,
                                                                                          judge,
                                                                                          none(),
                                                                                          some(sha));
            case GateRead.SchemaBroken(var field) -> new Stored(none(), none(), none(), some(field), none());
        };
    }
}
