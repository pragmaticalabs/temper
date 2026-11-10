package org.pragmatica.temper.store.api;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;


/// One ticket line of `bb-tickets`, as stored in `bb_snapshot.tickets`. `waiter` is `alive`,
/// `gone`, or null for a done ticket (the JSON `null`); read it through [#waiterState].
public record BbTicket(int v,
                       String ticket,
                       String dir,
                       String waiter,
                       Map<String, String> fields,
                       List<String> dropped) {
    public BbTicket {
        fields = Map.copyOf(fields);
        dropped = List.copyOf(dropped);
    }

    public static BbTicket bbTicket(int v,
                                    String ticket,
                                    String dir,
                                    Option<String> waiter,
                                    Map<String, String> fields,
                                    List<String> dropped) {
        return new BbTicket(v, ticket, dir, waiter.or((String) null), fields, dropped);
    }

    public Option<String> waiterState() {
        return Option.option(waiter);
    }

    public Option<String> field(String key) {
        return Option.option(fields.get(key));
    }
}
