package org.pragmatica.kiln.service.scan;

import java.util.List;
import java.util.Set;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Promise;


/// One host's `kiln-scan` verbs (kiln-f1-design §3.2), answering the raw lines. A transport failure (ssh exit 255, a
/// timeout, a nonzero rc, an empty stream) is a failed promise, never an empty listing (§3.4, K-5). Built in WP4/WP8.
public interface ScanTransport {
    /// `scan <root>...`, with `full` on stdin: full facts for those paths, stub lines for every other checkout.
    Promise<List<String>> scan(List<String> roots, List<CheckoutPath> full);
    /// `reach <sha>...` (at most 200) against the host's own bare.
    Promise<List<String>> reach(Set<Sha> shas);
    /// `size <root> <path>`: refused (64) unless `path` is one of the directories `root` matches, or under one.
    Promise<List<String>> size(String root, CheckoutPath path);
}
