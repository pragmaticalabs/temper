package org.pragmatica.kiln.service.scan;

import java.util.List;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.lang.Option;


/// The roots a pass hands [org.pragmatica.kiln.service.store.CheckoutStore#applyPass] (WP5 verification ruling, §3.4).
/// The store deletes absent rows only under ABSOLUTE roots, so every `~/` root is expanded first with the `home` of the
/// scan's own trailer (`cd ~ && pwd -P` on the scanned host): the very spelling the script expanded it to. Without a
/// home (an older script, or one that is not an absolute canonical path) the root is passed on as it is, and the store
/// reads it as upsert-only: it deletes nothing under it. kiln never guesses a host's home.
///
/// Accepted residual (WP8 verification): the expansion is textual, `home` + the root's rest. The script lists each match
/// by its on-disk spelling (`pwd -P`), so a `~` root whose literal part runs through a symbolic link (say `~/work` a link
/// to `/data/work`) never covers its own rows: they are never deleted and never sized. This fails safe (rows kept, sizes
/// unknown), and bigboy's roots are physical; give such a root as its physical absolute path instead.
public sealed interface ScanRoots {
    String HOME_PREFIX = "~/";

    static List<String> expanded(List<String> roots, Option<String> home) {
        return roots.stream()
                    .map(root -> expanded(root,
                                          home.filter(ScanRoots::canonical)))
                    .toList();
    }

    private static String expanded(String root, Option<String> home) {
        return root.startsWith(HOME_PREFIX)
               ? home.map(dir -> dir + root.substring(1))
                     .or(root)
               : root;
    }

    /// A home is used only as an absolute canonical path (never `/`, which has no component), so an expansion is always
    /// one too.
    private static boolean canonical(String home) {
        return CheckoutPath.checkoutPath(home).isSuccess();
    }

    record unused() implements ScanRoots {}
}
