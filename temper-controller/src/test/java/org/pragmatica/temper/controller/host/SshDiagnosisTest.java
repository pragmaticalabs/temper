package org.pragmatica.temper.controller.host;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// m4-design §2.2, §12.1: the OpenSSH host-key lines classify as a host-key failure; any other stderr does not. The
/// lines are OpenSSH's text, which `SshdHostKeyIT` checks against a real client.
class SshDiagnosisTest {
    static final String CHANGED = "@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @";
    static final String FAILED = "Host key verification failed.";
    static final String UNKNOWN = "No ED25519 host key is known for [127.0.0.1]:32768 and you have requested strict checking.";

    @Test
    void hostKeyFailure_true_changedKey() {
        assertThat(SshDiagnosis.hostKeyFailure(CHANGED)).isTrue();
    }

    @Test
    void hostKeyFailure_true_verificationFailed() {
        assertThat(SshDiagnosis.hostKeyFailure(FAILED)).isTrue();
    }

    @Test
    void hostKeyFailure_false_unrelatedLines() {
        assertThat(SshDiagnosis.hostKeyFailure("ssh: connect to host 198.51.100.1 port 22: Connection refused")).isFalse();
        assertThat(SshDiagnosis.hostKeyFailure("Connection timed out during banner exchange")).isFalse();
        assertThat(SshDiagnosis.hostKeyFailure(UNKNOWN)).isFalse();
        assertThat(SshDiagnosis.hostKeyFailure("")).isFalse();
    }

    @Test
    void hostKeyFailureIn_true_capturedStderr() {
        assertThat(SshDiagnosis.hostKeyFailureIn(UNKNOWN + "\n" + FAILED + "\n")).isTrue();
        assertThat(SshDiagnosis.hostKeyFailureIn("@@@@@@@@\n" + CHANGED + "\n@@@@@@@@\nIT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\n"))
            .isTrue();
    }

    @Test
    void hostKeyFailureIn_false_emptyOrUnrelated() {
        assertThat(SshDiagnosis.hostKeyFailureIn("")).isFalse();
        assertThat(SshDiagnosis.hostKeyFailureIn("install: sha256 mismatch\n")).isFalse();
    }
}
