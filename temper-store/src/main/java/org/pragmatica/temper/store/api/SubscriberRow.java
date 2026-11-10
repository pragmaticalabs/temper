package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.temper.domain.subscribe.Selector;


/// A `subscribers` row; `selectors` are typed (m2-design §2.2), sorted by their wire form; empty means every job.
/// `version` grows with every selector change (R-M2-12): a skip-ack holds only under the version its poll read.
public record SubscriberRow(String name, long cursor, List<Selector> selectors, long version) {
    public SubscriberRow {
        selectors = List.copyOf(selectors);
    }

    public static SubscriberRow subscriberRow(String name, long cursor, List<Selector> selectors, long version) {
        return new SubscriberRow(name, cursor, selectors, version);
    }
}
