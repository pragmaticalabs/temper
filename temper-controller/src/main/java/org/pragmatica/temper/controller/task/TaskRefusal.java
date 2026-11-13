package org.pragmatica.temper.controller.task;

import org.pragmatica.lang.Cause;


/// Why the controller refuses a task request (m3-design §5.4, the API's 422): the base ref is not a branch of the
/// mirror, the branch is a protected one, or a lane checkpoint is too large or names a sha off the branch. The text is the wire code.
public enum TaskRefusal implements Cause, Cause.Terminal {
    BASE_UNKNOWN("base-unknown"),
    PROTECTED_BRANCH("protected-branch"),
    /// A lane checkpoint's body is over 16 KB.
    CHECKPOINT_TOO_LARGE("checkpoint-too-large"),
    /// A lane checkpoint's `pushedSha` is not the task branch's tip or an ancestor of it at the remote.
    PUSHED_SHA_NOT_ON_BRANCH("pushed-sha-not-on-branch");
    private final String message;
    TaskRefusal(String message) {
        this.message = message;
    }
    @Override
    public String message() {
        return message;
    }
}
