package org.pragmatica.temper.protocol.api;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The `rerun` recipe on the wire and in `jobs.recipe` (m2-design §5.2): it round-trips member for member and parses
/// back to the domain recipe it came from (a refused request's too); `POST /jobs` does not take it (a 400).
class RerunRecipeJsonTest {
    private static final JobId PARENT = JobId.jobId(4711).unwrap();

    private static Recipe.Rerun accepted() {
        return Recipe.rerun(PARENT,
                            List.of(Recipe.Rerun.Target.target("org.x.FooTest", "core", Runner.SUREFIRE).unwrap(),
                                    Recipe.Rerun.Target.target("org.x.BarIT", "aether/ember", Runner.FAILSAFE).unwrap()),
                            Recipe.Rerun.Original.original(PARENT,
                                                           "FAILED",
                                                           some("tests"),
                                                           List.of(Recipe.Rerun.Failure.failure("org.x.FooTest", "works", none()),
                                                                   Recipe.Rerun.Failure.failure("org.x.BarIT", "runs", some("#12")))));
    }

    @Test
    void rerunRecipe_roundTrips_andParsesBackToTheDomainRecipe() {
        var mapper = TemperJson.mapper();
        var dto = RecipeDto.from(accepted());
        var json = mapper.writeAsString(dto).unwrap();

        assertThat(json).startsWith("{\"rerun\":{\"of\":4711,\"classes\":[{\"class\":\"org.x.BarIT\",\"module\":\"aether/ember\",\"runner\":\"failsafe\"}");
        assertThat(mapper.readString(json, RecipeDto.class).unwrap()).isEqualTo(dto);
        assertThat(dto.toDomain().unwrap()).isEqualTo(accepted());
        assertThat(dto.toDomain().unwrap().canonical()).isEqualTo(accepted().canonical());
    }

    /// maintainer ruling on the rerun bound: the bound decided at submit is stored with the recipe and read back, and is not in
    /// the canonical form (not in the dedup key).
    @Test
    void boundedRerunRecipe_roundTrips_andTheBoundIsNotCanonical() {
        var mapper = TemperJson.mapper();
        var bounded = accepted().withBound(Recipe.Rerun.Bound.bound(Duration.ofMinutes(48), "Σ 25.1m ×1.5 + install 5m + slack 5m"));
        var dto = RecipeDto.from(bounded);
        var json = mapper.writeAsString(dto).unwrap();

        assertThat(json).endsWith(",\"bound\":{\"millis\":2880000,\"derivation\":\"Σ 25.1m ×1.5 + install 5m + slack 5m\"}}}");
        assertThat(mapper.readString(json, RecipeDto.class).unwrap()).isEqualTo(dto);
        assertThat(dto.toDomain().unwrap()).isEqualTo(bounded);
        assertThat(bounded.canonical()).isEqualTo(accepted().canonical());
    }

    /// An explicit unit timeout is kept and printed exactly, to the millisecond, never rounded.
    @Test
    void explicitBound_keepsItsExactValue() {
        var bounded = accepted().withBound(Recipe.Rerun.Bound.bound(Duration.ofMillis(90_500), "unit-timeout"));

        assertThat(RecipeDto.from(bounded).toDomain().unwrap()).isEqualTo(bounded);
        assertThat(bounded.bound().map(Recipe.Rerun.Bound::annotation)).isEqualTo(some("rerun-bound=90500ms (unit-timeout)"));
    }

    /// A rerun stored before the derived bound (Deploy 11) has no `bound` member and still parses, without one.
    @Test
    void storedRerun_withoutABoundMember_parsesWithoutABound() {
        var mapper = TemperJson.mapper();
        var json = mapper.writeAsString(RecipeDto.from(accepted()))
                         .unwrap()
                         .replace(",\"bound\":null", "");

        assertThat(json).doesNotContain("bound");
        assertThat(mapper.readString(json, RecipeDto.class).unwrap().toDomain().unwrap()).isEqualTo(accepted());
    }

    @Test
    void storedRerun_withABadBound_doesNotParse() {
        assertThat(new RecipeDto.Rerun(4711, List.of(), none(), some(RecipeDto.Rerun.BoundDto.boundDto(0, "x"))).toDomain()
                                                                                                         .isFailure()).isTrue();
        assertThat(new RecipeDto.Rerun(4711, List.of(), none(), some(RecipeDto.Rerun.BoundDto.boundDto(60, " "))).toDomain()
                                                                                                          .isFailure()).isTrue();
    }

    @Test
    void refusedRerunRecipe_roundTrips() {
        var dto = RecipeDto.from(Recipe.refusedRerun(PARENT));

        assertThat(dto).isEqualTo(new RecipeDto.Rerun(4711, List.of(), none(), none()));
        assertThat(dto.toDomain().unwrap()).isEqualTo(Recipe.refusedRerun(PARENT));
    }

    @Test
    void storedRerun_withABadRunnerOrClass_doesNotParse() {
        assertThat(new RecipeDto.Rerun(4711, List.of(new RecipeDto.Rerun.TargetDto("org.x.FooTest", "core", "junit")), none(), none()).toDomain()
                                                                                                                         .isFailure()).isTrue();
        assertThat(new RecipeDto.Rerun(4711, List.of(new RecipeDto.Rerun.TargetDto("a b", "core", "surefire")), none(), none()).toDomain()
                                                                                                                 .isFailure()).isTrue();
    }

    @Test
    void submitRequest_withARerunRecipe_isRefused() {
        var request = SubmitRequest.submitRequest("v2174",
                                                  none(),
                                                  none(),
                                                  none(),
                                                  new SourceDto.Pushed(none(), some("a".repeat(40))),
                                                  RecipeDto.from(accepted()),
                                                  none(),
                                                  none(),
                                                  none(),
                                                  none());

        assertThat(request.toDomain().isFailure()).isTrue();
    }

    /// A view written without `reruns` (an older controller, a hand-written answer) reads as having none.
    @Test
    void jobView_withoutReruns_readsAsNone() {
        var json = "{\"job\":41,\"state\":\"RUNNING\",\"verdict\":null,\"reason\":null,\"submittedBy\":\"v2174\","
                   + "\"submittedAt\":\"2026-10-13T10:00:00Z\",\"priority\":-20,\"keep\":false,\"fresh\":false,\"superseded\":false,"
                   + "\"source\":{\"ref\":\"main\",\"sha\":null},\"recipe\":{\"gate\":{}},\"units\":[]}";

        assertThat(TemperJson.mapper().readString(json, JobView.class).unwrap().reruns()).isEmpty();
    }
}
