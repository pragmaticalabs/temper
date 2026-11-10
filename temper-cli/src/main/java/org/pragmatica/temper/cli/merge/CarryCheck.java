package org.pragmatica.temper.cli.merge;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.protocol.exec.Completed;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The Mac-side checks of `verdict --carried-from <oldSha>`: SHA must be the mechanical
/// update-branch merge of the ACCEPTed `oldSha` with a commit of the base's first-parent history, resolved by git
/// itself. Checked in the contract's order; the first refusal wins. Fetches from the explicit URL into the G8 cache.
sealed interface CarryCheck {
    /// The facts the later checks read: the fetched base tip and SHA's two parents.
    record Carry(String base, String parent1, String parent2) {}

    static Promise<Option<String>> refusal(Git git,
                                           Budget budget,
                                           Path cache,
                                           String url,
                                           long pr,
                                           String base,
                                           String sha,
                                           String oldSha,
                                           String run) {
        return BaseCheck.fetched(git, budget, cache, url, base, pr, run).flatMap(fetched -> headChecked(git,
                                                                                                        budget,
                                                                                                        cache,
                                                                                                        fetched,
                                                                                                        sha,
                                                                                                        oldSha));
    }

    private static Promise<Option<String>> headChecked(Git git,
                                                       Budget budget,
                                                       Path cache,
                                                       BaseCheck.Fetched fetched,
                                                       String sha,
                                                       String oldSha) {
        return sha.equals(fetched.head())
               ? parents(git, budget, cache, sha).flatMap(parents -> parentsChecked(git,
                                                                                    budget,
                                                                                    cache,
                                                                                    fetched,
                                                                                    sha,
                                                                                    oldSha,
                                                                                    parents))
               : refused("carry-head-moved");
    }

    private static Promise<List<String>> parents(Git git, Budget budget, Path cache, String sha) {
        return git.output(budget,
                          "rev-list",
                          cache,
                          List.of("rev-list", "--parents", "-n", "1", sha))
                  .map(text -> Arrays.stream(text.strip().split("\\s+"))
                                     .skip(1)
                                     .toList());
    }

    private static Promise<Option<String>> parentsChecked(Git git,
                                                          Budget budget,
                                                          Path cache,
                                                          BaseCheck.Fetched fetched,
                                                          String sha,
                                                          String oldSha,
                                                          List<String> parents) {
        return parents.size() != 2
               ? refused("carry-not-merge")
               : parent1Checked(git,
                                budget,
                                cache,
                                sha,
                                oldSha,
                                new Carry(fetched.base(), parents.get(0), parents.get(1)));
    }

    private static Promise<Option<String>> parent1Checked(Git git,
                                                          Budget budget,
                                                          Path cache,
                                                          String sha,
                                                          String oldSha,
                                                          Carry carry) {
        return carry.parent1()
                    .equals(oldSha)
               ? onFirstParent(git, budget, cache, carry).flatMap(onBase -> parent2Checked(git,
                                                                                           budget,
                                                                                           cache,
                                                                                           sha,
                                                                                           oldSha,
                                                                                           carry,
                                                                                           onBase))
               : refused("carry-parent1 " + BaseCheck.sha12(carry.parent1()));
    }

    /// Parent 2 is on `rev-list --first-parent <base>`: reachability alone is not enough (a side branch merged into the
    /// base is reachable but was never the base).
    private static Promise<Boolean> onFirstParent(Git git, Budget budget, Path cache, Carry carry) {
        return git.output(budget,
                          "rev-list",
                          cache,
                          List.of("rev-list",
                                  "--first-parent",
                                  carry.base()))
                  .map(text -> text.lines()
                                   .anyMatch(carry.parent2()::equals));
    }

    private static Promise<Option<String>> parent2Checked(Git git,
                                                          Budget budget,
                                                          Path cache,
                                                          String sha,
                                                          String oldSha,
                                                          Carry carry,
                                                          boolean onBase) {
        return onBase
               ? resolvedByGit(git, budget, cache, sha, oldSha, carry)
               : refused("carry-parent2-off-base");
    }

    /// `git merge-tree --write-tree <oldSha> <parent2>` must exit 0 and print SHA's tree: any hand resolution (a
    /// conflict resolved, an edit folded into the merge) shows as another tree or a non-zero exit.
    private static Promise<Option<String>> resolvedByGit(Git git,
                                                         Budget budget,
                                                         Path cache,
                                                         String sha,
                                                         String oldSha,
                                                         Carry carry) {
        return Promise.all(git.completedIn(budget,
                                           "merge-tree",
                                           cache,
                                           List.of("merge-tree",
                                                   "--write-tree",
                                                   oldSha,
                                                   carry.parent2())),
                           git.output(budget,
                                      "rev-parse",
                                      cache,
                                      List.of("rev-parse", sha + "^{tree}")))
                      .map(CarryCheck::handResolved);
    }

    private static Option<String> handResolved(Completed merged, String tree) {
        return merged.rc() == 0 && firstLine(merged.stdoutText()).equals(tree.strip())
               ? none()
               : some("carry-hand-resolved");
    }

    private static String firstLine(String text) {
        return GhMerge.firstLine(text);
    }

    private static Promise<Option<String>> refused(String reason) {
        return Promise.success(some(reason));
    }

    record unused() implements CarryCheck {}
}
