package org.pragmatica.kiln.service.mirror;

import org.pragmatica.lang.Option;


/// How far an open PR is behind its base in the mirror (spec §5.1), with `B = merge-base(head, base)`: `behindBy` is
/// `rev-list --count B..base`, and `behindTreeEmpty` whether `git diff --quiet B base` holds. `behindTreeEmpty` means
/// **tree-equal** (maintainer ruling, K-8): the tree at `B` equals the base tip's tree, because a build reads only the tree.
/// It does not mean "behind by empty commits only": a change and its revert, or a merge that keeps the tree, read
/// `true`. Both are absent (`UNKNOWN`, never 0) when the head is not in the mirror (K-8).
public record BehindFacts(Option<Integer> behindBy, Option<Boolean> behindTreeEmpty) {}
