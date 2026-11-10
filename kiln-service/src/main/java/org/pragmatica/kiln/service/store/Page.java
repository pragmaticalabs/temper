package org.pragmatica.kiln.service.store;

import java.util.List;


/// One page of a drill-down (100 rows), its number and the page count.
public record Page<T>(List<T> rows, int page, int pages) {
    public Page {
        rows = List.copyOf(rows);
    }
}
