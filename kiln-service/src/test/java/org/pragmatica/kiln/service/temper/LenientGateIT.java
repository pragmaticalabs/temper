package org.pragmatica.kiln.service.temper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.temper.TemperFake.DIGEST;
import static org.pragmatica.kiln.service.temper.TemperFake.T0;
import static org.pragmatica.kiln.service.temper.TemperFake.await;
import static org.pragmatica.kiln.service.temper.TemperFake.openPr;
import static org.pragmatica.kiln.service.temper.TemperFake.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// K-12 (spec §4.5, E-1): kiln reads temper's gate leniently, through a real HTTP exchange into Postgres. A gate answer
/// with an extra field is answered; a NONE answer without `job` and `judge` is answered; one without `answer` is
/// `TEMPER-SCHEMA(answer)`, and the source is not `TEMPER-DOWN`: temper answered. Positive control: with only the two
/// good answers the source is OK.
///
/// The open-PR view lists PRs newest first, so the gates are compared as a set; each answer names its own SHA.
///
/// Probes (WP6 state file): fail on unknown properties in `GateRead.decode` → `#1` reads SchemaBroken; require `judge`
/// → `#2` reads SchemaBroken. Either turns this red.
@Tag("pg")
class LenientGateIT {
    private static final String EXTRA = "{\"sha\":\"" + sha(1).value() + "\",\"answer\":\"PASS\",\"job\":41,\"judge\":\"j1\","
                                        + "\"addedLater\":{\"x\":[1,2]}}";
    private static final String NONE_ANSWER = "{\"sha\":\"" + sha(2).value() + "\",\"answer\":\"NONE\"}";
    private static final String NO_ANSWER = "{\"sha\":\"" + sha(3).value() + "\",\"verdict\":\"PASS\",\"job\":7}";

    private KilnStore store;
    private TemperFake fake;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = TemperFake.start();
        fake.answer(DIGEST, 200, "queue: 0\nhosts: 2\n");
        fake.gate(sha(1), 200, EXTRA);
        fake.gate(sha(2), 200, NONE_ANSWER);
        fake.gate(sha(3), 200, NO_ANSWER);
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    @Test
    void gates_decodeLeniently_andAMissingAnswerIsSchemaNotDown() {
        await(store.prs().upsert(List.of(openPr(1), openPr(2), openPr(3)), T0));

        var state = await(reader().cycle());

        assertThat(state).isEqualTo(new SourceState.TemperSchema("answer", T0));
        assertThat(state).isNotInstanceOf(SourceState.TemperDown.class);
        assertThat(gates()).containsExactlyInAnyOrder(some(new GateRead.Answered(sha(1).value(), "PASS", some(41L), some("j1"))),
                                            some(new GateRead.Answered(sha(2).value(), "NONE", none(), none())),
                                            some(new GateRead.SchemaBroken("answer")));
    }

    @Test
    void gates_readOk_whenEveryAnswerHasItsFields() {
        await(store.prs().upsert(List.of(openPr(1), openPr(2)), T0));

        assertThat(await(reader().cycle())).isEqualTo(new SourceState.Ok(T0));
        assertThat(gates()).allMatch(gate -> gate.filter(GateRead.Answered.class::isInstance).isPresent());
    }

    private TemperReader reader() {
        return TemperReader.temperReader(fake.http(), store, new AtomicReference<>(T0)::get);
    }

    private List<Option<GateRead>> gates() {
        return await(store.views().openPrs()).stream()
                                              .map(PrView::gate)
                                              .map(gate -> gate.map(Observed::value))
                                              .toList();
    }
}
