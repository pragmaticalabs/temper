package org.pragmatica.kiln.core.github;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The canonical form and the hash of a ruleset.
class RulesetDriftTest {
    @Test
    void canonical_sortsKeysAtEveryDepth_andKeepsArrayOrder() {
        assertThat(RulesetDrift.canonical("{ \"b\": [2, 1], \"a\": {\"y\": null, \"x\": \"s\"} }").unwrap()).isEqualTo("{\"a\":{\"x\":\"s\",\"y\":null},\"b\":[2,1]}");
    }

    @Test
    void ids_areDistinctAndAscending() {
        assertThat(RulesetDrift.ids("[{\"ruleset_id\":9},{\"ruleset_id\":7},{\"ruleset_id\":9}]").unwrap()).containsExactly(7L,
                                                                                                                            9L);
        assertThat(RulesetDrift.ids("[]").unwrap()).isEmpty();
        assertThat(RulesetDrift.ids("[{\"type\":\"x\"}]").isFailure()).isTrue();
    }

    @Test
    void governing_keepsTheFourFields_andDropsTheRest() {
        var kept = RulesetDrift.governing("{\"id\":7,\"name\":\"n\",\"updated_at\":\"t\",\"enforcement\":\"active\",\"bypass_actors\":[]}").unwrap();

        assertThat(kept).isEqualTo("{\"enforcement\":\"active\",\"bypass_actors\":[]}");
    }

    @Test
    void members_areSortedLogins() {
        assertThat(RulesetDrift.members("[{\"login\":\"b\",\"id\":1},{\"login\":\"a\"}]").unwrap()).containsExactly("a",
                                                                                                                    "b");
    }

    @Test
    void material_changesWithABypassActor_notWithKeyOrder() {
        var effective = RulesetDrift.canonical("[{\"ruleset_id\":7}]").unwrap();
        var none = RulesetDrift.governing("{\"enforcement\":\"active\",\"bypass_actors\":[]}").unwrap();
        var reordered = RulesetDrift.governing("{\"bypass_actors\":[],\"enforcement\":\"active\"}").unwrap();
        var added = RulesetDrift.governing("{\"enforcement\":\"active\",\"bypass_actors\":[{\"actor_id\":1}]}").unwrap();

        assertThat(RulesetDrift.material(effective,
                                         Map.of(7L, none))).isEqualTo(RulesetDrift.material(effective,
                                                                                            Map.of(7L, reordered)))
                  .hasSize(64)
                  .isNotEqualTo(RulesetDrift.material(effective,
                                                      Map.of(7L, added)));
    }
}
