package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.subscribe.Selector;

import static org.pragmatica.lang.Option.option;


/// `PUT /api/v1/subscribers/{name}/selectors` body (m2-design §2.3): selectors in their wire form (`job:<id>`,
/// `sha:<40 hex>`, `ref:<name>`; [Selector#selector]) to add and to remove. An absent list is empty. Parsing gives
/// the typed lists or a 400: a bare or malformed selector is never guessed at here, the CLI classifies bare arguments.
public record SelectorChange(List<String> add, List<String> remove) {
    public static SelectorChange selectorChange(List<String> add, List<String> remove) {
        return new SelectorChange(add, remove);
    }

    /// The selectors to add.
    public Result<List<Selector>> additions() {
        return typed(option(add).or(List.of()));
    }

    /// The selectors to remove.
    public Result<List<Selector>> removals() {
        return typed(option(remove).or(List.of()));
    }

    private static Result<List<Selector>> typed(List<String> wire) {
        return Result.allOf(wire.stream().map(Selector::selector).toList());
    }
}
