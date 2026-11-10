package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;

/// `temper_gate.answer_sha` (V2, WP6 round 3): the store refuses an answer sha that is not 7 to 40 lowercase hex
/// characters, and the view shows a stored answer only when its sha is such a prefix of the head, so a row that slipped
/// past the check (written before V2, or with the check dropped) shows no gate and never hides the PR.
@Tag("pg")
class GateShaRowIT {
    /// Starts with `0`, so a one-character answer sha `0` is a prefix of it and only the hex rule hides it.
    private static final Sha HEAD = Sha.sha("0a".repeat(20)).unwrap();

    private DbConfig config;
    private KilnStore store;
    private Db db;
    private PullRequest pr;

    @BeforeEach
    void start() {
        config = KilnPgFixture.freshDatabase();
        store = KilnPostgres.connect(config, StoreFixtures.REPO, StoreFixtures.highWater()).await(KilnPgFixture.WAIT).unwrap();
        db = KilnPgFixture.db(config);
        pr = new PullRequest(PrNumber.prNumber(5).unwrap(), "pr 5", PrState.OPEN, false, BranchName.branchName("main").unwrap(),
                             BranchName.branchName("b5").unwrap(), Option.some("o/r"), HEAD, Option.none(), Option.none(), T0,
                             List.of(), List.of(), List.of());
        store.prs().upsert(List.of(pr), T0).await(KilnPgFixture.WAIT).unwrap();
    }

    @AfterEach
    void stop() {
        store.close().await(KilnPgFixture.WAIT);
    }

    @Test
    void store_refusesAnAnswerShaThatIsNotSevenToFortyLowercaseHex() {
        assertThat(gate("0").isFailure()).as("one char").isTrue();
        assertThat(gate("0a0a0a").isFailure()).as("six chars").isTrue();
        assertThat(gate("0A0A0A0A").isFailure()).as("upper case").isTrue();
        assertThat(gate("0a0a0a0").isSuccess()).as("seven chars").isTrue();
        assertThat(shown()).isEqualTo(Option.some(answered("0a0a0a0")));
    }

    /// The V2 CHECK alone: a direct write of an answer sha `0` or `ABCDEF1`, past the store, is refused.
    @Test
    void db_refusesADirectWrite_ofAnAnswerShaThatIsNotSevenToFortyLowercaseHex() {
        assertThat(gate(HEAD.value()).isSuccess()).isTrue();

        assertThat(sql("UPDATE temper_gate SET answer_sha = '0'").isFailure()).as("update to 0").isTrue();
        assertThat(sql("UPDATE temper_gate SET answer_sha = 'ABCDEF1'").isFailure()).as("update to ABCDEF1").isTrue();
        assertThat(sql("INSERT INTO temper_gate (pr, head_sha, answer, answer_sha, observed_at) "
                       + "VALUES (6, '" + HEAD.value() + "', 'PASS', '0', now())").isFailure()).as("insert 0").isTrue();
        assertThat(sql("INSERT INTO temper_gate (pr, head_sha, answer, answer_sha, observed_at) "
                       + "VALUES (7, '" + HEAD.value() + "', 'PASS', 'ABCDEF1', now())").isFailure()).as("insert ABCDEF1").isTrue();
        assertThat(shown()).as("the stored answer is untouched").isEqualTo(Option.some(answered(HEAD.value())));
    }

    /// The view's hex rule alone: with the CHECK dropped, an answer sha `0` (a prefix of the head) shows no gate, and
    /// the PR is still listed.
    @Test
    void view_showsNoGate_forAOneCharShaThatIsAPrefixOfTheHead() {
        assertThat(gate(HEAD.value()).isSuccess()).isTrue();
        script("ALTER TABLE temper_gate DROP CONSTRAINT temper_gate_answer_sha_hex; UPDATE temper_gate SET answer_sha = '0'");

        var open = store.views().openPrs().await(KilnPgFixture.WAIT).unwrap();

        assertThat(open).hasSize(1);
        assertThat(open.getFirst().pr().value().number()).isEqualTo(pr.number());
        assertThat(open.getFirst().gate()).isEqualTo(Option.none());
    }

    @Test
    void view_showsNoGate_forAShortShaThatSlippedPastTheCheck() {
        assertThat(gate(HEAD.value()).isSuccess()).isTrue();
        script("ALTER TABLE temper_gate DROP CONSTRAINT temper_gate_answer_sha_hex; UPDATE temper_gate SET answer_sha = '0a'");

        var open = store.views().openPrs().await(KilnPgFixture.WAIT).unwrap();

        assertThat(open).hasSize(1);
        assertThat(open.getFirst().gate()).isEqualTo(Option.none());
    }

    /// The verifier's pre-V2 case: an answer without `answer_sha` hides only the gate.
    @Test
    void view_showsNoGate_forARowWrittenBeforeV2() {
        assertThat(gate(HEAD.value()).isSuccess()).isTrue();
        script("ALTER TABLE temper_gate DROP CONSTRAINT temper_gate_answer_sha; UPDATE temper_gate SET answer_sha = NULL");

        var open = store.views().openPrs().await(KilnPgFixture.WAIT).unwrap();

        assertThat(open).hasSize(1);
        assertThat(open.getFirst().gate()).isEqualTo(Option.none());
    }

    private Result<Unit> gate(String sha) {
        return store.temper().gate(pr.number(), HEAD, answered(sha), T0).await(KilnPgFixture.WAIT);
    }

    private Option<GateRead> shown() {
        return store.views().openPrs().await(KilnPgFixture.WAIT).unwrap().getFirst().gate().map(Observed::value);
    }

    private void script(String sql) {
        sql(sql).unwrap();
    }

    private Result<Unit> sql(String sql) {
        return db.executor().completeScript(sql).await(KilnPgFixture.WAIT).mapToUnit();
    }

    private static GateRead answered(String sha) {
        return new GateRead.Answered(sha, "PASS", Option.none(), Option.none());
    }
}
