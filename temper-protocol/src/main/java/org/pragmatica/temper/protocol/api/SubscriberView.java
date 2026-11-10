package org.pragmatica.temper.protocol.api;

import java.util.List;


/// `PUT /api/v1/subscribers/{name}/selectors` answer: the subscriber after the change, its selectors in wire form,
/// sorted (empty means every job, spec §8.3), and its cursor.
public record SubscriberView(String name, List<String> selectors, long cursor) {
    public static SubscriberView subscriberView(String name, List<String> selectors, long cursor) {
        return new SubscriberView(name, selectors, cursor);
    }
}
