package org.pragmatica.temper.controller.git;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.refusal.Refusal;

import static org.assertj.core.api.Assertions.assertThat;

class LsRemoteTest {
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final String C = "c".repeat(40);

    private static Ref ref(String raw) {
        return Ref.ref(raw).unwrap();
    }

    @Test
    void resolve_branch_exactName() {
        assertThat(LsRemote.resolve(ref("main"), A + "\trefs/heads/main\n").unwrap().value()).isEqualTo(A);
    }

    @Test
    void resolve_ignoresTailMatches_otherBranches() {
        var output = A + "\trefs/heads/main\n" + B + "\trefs/heads/feature/main\n";

        assertThat(LsRemote.resolve(ref("main"), output).unwrap().value()).isEqualTo(A);
    }

    @Test
    void resolve_annotatedTag_usesPeeledCommit() {
        var output = B + "\trefs/tags/v1\n" + C + "\trefs/tags/v1^{}\n";

        assertThat(LsRemote.resolve(ref("v1"), output).unwrap().value()).isEqualTo(C);
    }

    @Test
    void resolve_refusesSource_ambiguousBranchAndTag() {
        var output = A + "\trefs/heads/x\n" + B + "\trefs/tags/x\n";

        LsRemote.resolve(ref("x"), output)
                .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                .onFailure(cause -> assertThat(((Refusal) cause).code()).isEqualTo("source"));
    }

    @Test
    void resolve_accepts_branchAndTagSameCommit() {
        var output = A + "\trefs/heads/x\n" + A + "\trefs/tags/x\n";

        assertThat(LsRemote.resolve(ref("x"), output).unwrap().value()).isEqualTo(A);
    }

    @Test
    void resolve_refusesSource_noExactMatch() {
        LsRemote.resolve(ref("main"), B + "\trefs/heads/feature/main\n")
                .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
                .onFailure(cause -> assertThat(((Refusal) cause).code()).isEqualTo("source"));
    }

    @Test
    void resolve_fullRefName() {
        assertThat(LsRemote.resolve(ref("refs/heads/main"), A + "\trefs/heads/main\n").unwrap().value()).isEqualTo(A);
    }
}
