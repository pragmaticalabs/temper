package org.pragmatica.temper.store.api;

/// Both sides of `temper diff`, read in one snapshot ([ResultStore#diffSides]).
public record StoredSides(StoredSide a, StoredSide b) {
    public static StoredSides storedSides(StoredSide a, StoredSide b) {
        return new StoredSides(a, b);
    }
}
