package org.pragmatica.kiln.service.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.conflict.ConflictBoard;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// temper #100: `GET /api/v1/conflicts` names the tip, each prediction with its hot paths, the costs, and the limit; a
/// watch that is off answers `enabled: false`, never an empty "no conflicts" board.
class ConflictsJsonTest {
    private static final Sha TIP = Sha.sha("2e44f7577bbfce430673a4813836186b499f3552").unwrap();

    @Test
    void board_isSerialisedWithHotPathsCostsAndTheLimit() {
        var board = new ConflictBoard(BranchName.branchName("release-1.0.0-rc5").unwrap(),
                                      some(TIP),
                                      some(Instant.parse("2026-11-08T10:00:00Z")),
                                      3,
                                      0,
                                      7,
                                      List.of(Prediction.tip(PrNumber.prNumber(2713).unwrap(),
                                                             List.of("a/SystemTags.java", "b.md"),
                                                             TIP)),
                                      List.of(),
                                      List.of(new ConflictBoard.Cost(Instant.parse("2026-11-08T10:00:00Z"),
                                                                     some(900L),
                                                                     none(),
                                                                     40,
                                                                     1,
                                                                     0)),
                                      none());
        var json = Views.json(Views.conflicts(some(board))).unwrap();

        assertThat(json).contains("\"enabled\":true",
                                  "\"tip\":\"" + TIP.value() + "\"",
                                  "\"kind\":\"tip\"",
                                  "\"pairsSkipped\":7",
                                  "\"firsts\":[]",
                                  "\"prs\":[2713]",
                                  "\"hot\":[\"a/SystemTags.java\"]",
                                  "\"fetchMillis\":900",
                                  "\"fetchedBytes\":null",
                                  "textual conflicts only");
    }

    @Test
    void watchOff_isEnabledFalse() {
        assertThat(Views.json(Views.conflicts(none())).unwrap()).contains("\"enabled\":false", "textual conflicts only");
    }
}
