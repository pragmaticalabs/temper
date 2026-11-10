package org.pragmatica.temper.cli.merge;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.domain.exit.ExitCode;


/// The queue and control-path edits and the control-path list. A write's `404`/`409`/`422` is
/// `refused <code>` (exit 2): `NOT_QUEUED` is `refused not-queued`, which is also what a `--base` other than the row's
/// answers (lead guess 1: the `base` goes in the body and the controller judges it).
sealed interface EditRuns {
    static Promise<Outcome> queueAdd(CliCommand.MergeQueueAdd command, MergeApi api, String owner) {
        return edited(api,
                      "",
                      command.base()
                             .<Object> map(base -> new MergeWire.QueueAddAt(command.repo(),
                                                                            command.pr(),
                                                                            base,
                                                                            owner))
                             .or(() -> new MergeWire.QueueAdd(command.repo(),
                                                              command.pr(),
                                                              owner)),
                      MergeApi.CREATED,
                      "queued");
    }

    static Promise<Outcome> queueRemove(CliCommand.MergeQueueRemove command, MergeApi api) {
        return edited(api,
                      "/remove",
                      command.base()
                             .<Object> map(base -> new MergeWire.QueueRemoveAt(command.repo(),
                                                                               command.pr(),
                                                                               base))
                             .or(() -> new MergeWire.QueueRemove(command.repo(),
                                                                 command.pr())),
                      MergeApi.OK,
                      "removed");
    }

    static Promise<Outcome> queueMove(CliCommand.MergeQueueMove command, MergeApi api) {
        return edited(api,
                      "/move",
                      command.base()
                             .<Object> map(base -> new MergeWire.QueueMoveAt(command.repo(),
                                                                             command.pr(),
                                                                             command.to(),
                                                                             base))
                             .or(() -> new MergeWire.QueueMove(command.repo(),
                                                               command.pr(),
                                                               command.to())),
                      MergeApi.OK,
                      "moved");
    }

    private static Promise<Outcome> edited(MergeApi api, String suffix, Object body, int expected, String word) {
        return api.queueEdit(suffix, body, expected, Budget.CALL)
                  .fold(result -> result.fold(cause -> MergeLines.refusedOrFailed(cause, MergeApi.QUEUE + suffix),
                                              position -> Promise.success(Outcome.outcome(MergeLines.position(word,
                                                                                                              position),
                                                                                          ExitCode.ACCEPTED))));
    }

    /// `<repo> <glob> added_by=<x> at=<ISO>` per glob.
    static Promise<Outcome> pathsList(CliCommand.ControlPathsList command, MergeApi api) {
        return api.paths(command.repo(),
                         Budget.CALL)
                  .map(paths -> MergeLines.printed(paths.globs()
                                                        .stream()
                                                        .map(entry -> paths.repo()
                                                                     + " " + entry.glob()
                                                                     + " added_by=" + entry.addedBy()
                                                                     + " at=" + entry.addedAt())
                                                        .toList(),
                                                   ExitCode.ACCEPTED));
    }

    static Promise<Outcome> pathAdd(CliCommand.ControlPathsAdd command, MergeApi api, String owner) {
        return pathEdited(api,
                          "",
                          new MergeWire.PathAdd(command.repo(), command.glob(), owner),
                          MergeApi.CREATED,
                          "added " + command.repo() + " " + command.glob());
    }

    static Promise<Outcome> pathRemove(CliCommand.ControlPathsRemove command, MergeApi api) {
        return pathEdited(api,
                          "/remove",
                          new MergeWire.PathRemove(command.repo(), command.glob()),
                          MergeApi.OK,
                          "removed " + command.repo() + " " + command.glob());
    }

    private static Promise<Outcome> pathEdited(MergeApi api, String suffix, Object body, int expected, String line) {
        return api.pathEdit(suffix, body, expected, Budget.CALL)
                  .fold(result -> result.fold(cause -> MergeLines.refusedOrFailed(cause, MergeApi.PATHS + suffix),
                                              _ -> Promise.success(Outcome.outcome(line, ExitCode.ACCEPTED))));
    }

    record unused() implements EditRuns {}
}
