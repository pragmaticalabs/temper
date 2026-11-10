package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.state.JobState;

import static org.pragmatica.lang.Option.option;


/// `GET /api/v1/jobs/{id}` and the `GET /jobs` list items. `verdict`/`reason` are set once terminal; for a
/// REFUSED job `reason` is the refusal code. `reruns` (M2, m2-design §5.3) are the job's rerun jobs, oldest first;
/// an absent `reruns` reads as none, as `DigestView`'s `known` does. `userProperties` (M4, maintainer ruling Q-M4-11c) are the
/// `-D` properties the user wrote, those that skip or narrow the tests marked ([UserPropertyDto]), derived from `recipe`;
/// an absent `userProperties` is derived from `recipe` too. `dedicatedCpu` (M4, m4-design §9.2, §11) is the job's
/// `--dedicated-cpu`: its units are never granted to a shared-CPU host; absent reads false.
public record JobView(long job,
                      JobState state,
                      Option<String> verdict,
                      Option<String> reason,
                      String submittedBy,
                      Instant submittedAt,
                      int priority,
                      boolean keep,
                      boolean fresh,
                      boolean superseded,
                      SourceView source,
                      RecipeDto recipe,
                      List<UnitView> units,
                      List<RerunView> reruns,
                      List<UserPropertyDto> userProperties,
                      Boolean dedicatedCpu) {
    /// A job without `--dedicated-cpu`.
    public static JobView jobView(long job,
                                  JobState state,
                                  Option<String> verdict,
                                  Option<String> reason,
                                  String submittedBy,
                                  Instant submittedAt,
                                  int priority,
                                  boolean keep,
                                  boolean fresh,
                                  boolean superseded,
                                  SourceView source,
                                  RecipeDto recipe,
                                  List<UnitView> units,
                                  List<RerunView> reruns) {
        return jobView(job,
                       state,
                       verdict,
                       reason,
                       submittedBy,
                       submittedAt,
                       priority,
                       keep,
                       fresh,
                       superseded,
                       source,
                       recipe,
                       units,
                       reruns,
                       false);
    }

    public static JobView jobView(long job,
                                  JobState state,
                                  Option<String> verdict,
                                  Option<String> reason,
                                  String submittedBy,
                                  Instant submittedAt,
                                  int priority,
                                  boolean keep,
                                  boolean fresh,
                                  boolean superseded,
                                  SourceView source,
                                  RecipeDto recipe,
                                  List<UnitView> units,
                                  List<RerunView> reruns,
                                  boolean dedicatedCpu) {
        return new JobView(job,
                           state,
                           verdict,
                           reason,
                           submittedBy,
                           submittedAt,
                           priority,
                           keep,
                           fresh,
                           superseded,
                           source,
                           recipe,
                           units,
                           reruns,
                           UserPropertyDto.of(recipe),
                           dedicatedCpu);
    }

    public JobView {
        units = List.copyOf(units);
        reruns = option(reruns).map(List::copyOf).or(List.of());
        userProperties = option(userProperties).map(List::copyOf).or(() -> UserPropertyDto.of(recipe));
        dedicatedCpu = option(dedicatedCpu).or(false);
    }
}
