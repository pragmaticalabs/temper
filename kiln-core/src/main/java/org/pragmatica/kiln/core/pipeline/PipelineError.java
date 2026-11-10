package org.pragmatica.kiln.core.pipeline;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Failures of the pipeline baseline's reads (#102) that are neither an HTTP status nor a missing field: each one fails
/// the read it belongs to, so it shows on the Pipeline tab and in its JSON instead of reading as zero hours.
public sealed interface PipelineError extends Cause {
    /// GraphQL answered 200 with an `errors[]` list (a permission the App lacks, a query GitHub refused); `first` is the
    /// first error's message.
    record GraphQl(String first, String message) implements PipelineError {
        public static final Fn1<GraphQl, String> FACTORY = Causes.forOneValue("GitHub GraphQL refused the query: %s",
                                                                              GraphQl::new);
    }

    /// One attempt lists more jobs than one page holds: its hours would be read short, so the attempt is refused.
    record JobsOverPage(long run, long total, String message) implements PipelineError {
        public static final Fn2<JobsOverPage, Long, Long> FACTORY = Causes.forTwoValues("run %d lists %d jobs, over one page",
                                                                                        JobsOverPage::new);
    }
}
