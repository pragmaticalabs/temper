package org.pragmatica.temper.controller.task;

import org.pragmatica.lang.Cause;


/// Why the controller refuses to create a task (m3-design §5.4, the API's 422): the base ref is not a branch of the
/// mirror, or the branch is a protected one. The text is the wire code.
public enum TaskRefusal implements Cause, Cause.Terminal {
    BASE_UNKNOWN("base-unknown"),
    PROTECTED_BRANCH("protected-branch");
    private final String message;
    TaskRefusal(String message) {
        this.message = message;
    }
    @Override
    public String message() {
        return message;
    }
}
