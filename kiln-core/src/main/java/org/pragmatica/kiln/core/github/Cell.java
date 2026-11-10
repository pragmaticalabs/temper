package org.pragmatica.kiln.core.github;

import java.util.List;


/// One required check's cell at a PR head (spec §4.4): the mapped `value`, the count of same-named runs from another
/// app (`foreign`), and the values of every run and status it shows.
public record Cell(RequiredCheck check, String value, int foreign, List<String> shown) {
    /// The only value that counts as success, anywhere (spec §4.4). `success*` (a skipped status) is not it.
    public static final String SUCCESS = "success";

    public Cell {
        shown = List.copyOf(shown);
    }

    public boolean isSuccess() {
        return SUCCESS.equals(value);
    }
}
