package org.pragmatica.kiln.core.checkout;

/// Where a tip is reachable (spec §5.3, W4): kiln's GitHub mirror, only the host's own bare, nowhere, or [#UNKNOWN] when
/// no `reach` answer exists (a Mac row, or the host failed).
public enum Reach {
    MIRROR,
    HOST_BARE,
    NOWHERE,
    UNKNOWN
}
