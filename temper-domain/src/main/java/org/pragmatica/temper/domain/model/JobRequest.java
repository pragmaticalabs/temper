package org.pragmatica.temper.domain.model;

/// A parsed submit request (spec §2.1): every field is a value object, so a `JobRequest` that exists is
/// well-formed. `envFieldPresent` records that the request carried an `env`/`environment` field, which
/// exists only so that it can be refused as `REFUSED(R-ENV)` (Q-7).
public record JobRequest(OwnerTag submittedBy,
                         Priority priority,
                         boolean keep,
                         boolean fresh,
                         SourceRequest source,
                         Recipe recipe,
                         RecipeOptions options,
                         boolean envFieldPresent) {
    public static JobRequest jobRequest(OwnerTag submittedBy,
                                        Priority priority,
                                        boolean keep,
                                        boolean fresh,
                                        SourceRequest source,
                                        Recipe recipe,
                                        RecipeOptions options,
                                        boolean envFieldPresent) {
        return new JobRequest(submittedBy, priority, keep, fresh, source, recipe, options, envFieldPresent);
    }

    /// `--dedicated-cpu` (m4-design §9.2): never granted to a `sharedCpu` host. Held by the options, so it is in the
    /// dedup key (§9.3) by the same path as the timeout override.
    public boolean dedicatedCpu() {
        return options.dedicatedCpu();
    }
}
