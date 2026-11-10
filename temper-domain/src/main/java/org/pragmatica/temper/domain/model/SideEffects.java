package org.pragmatica.temper.domain.model;

/// Whether a unit acts outside its own workspace (m4-design §5.1, ruling R-M4-5): `EXTERNAL` (a deploy, a release, a
/// push) is never run a second time after a host loss; the unit ends `ERROR(host-lost)` instead. The form in
/// `units.side_effects` is [#wire].
public enum SideEffects {
    NONE("none"),
    EXTERNAL("external");
    private final String wire;
    SideEffects(String wire) {
        this.wire = wire;
    }
    /// The value of `units.side_effects`.
    public String wire() {
        return wire;
    }
}
