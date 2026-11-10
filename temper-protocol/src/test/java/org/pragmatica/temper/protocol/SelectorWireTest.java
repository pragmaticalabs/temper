package org.pragmatica.temper.protocol;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.SelectorChange;
import org.pragmatica.temper.protocol.api.SubscriberView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;

/// The `PUT subscribers/{name}/selectors` wire (m2-design §2.3): `SelectorChange{add, remove}` in, `SubscriberView{name,
/// selectors, cursor}` out, selectors in their prefixed wire form only.
class SelectorWireTest {
    private static final JsonMapper MAPPER = TemperJson.mapper();
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";

    @Test
    void selectorChange_wireNames_andTypedParse() {
        var change = SelectorChange.selectorChange(List.of("job:4711", "sha:" + SHA), List.of("ref:main"));

        assertThat(MAPPER.writeAsString(change).unwrap()).isEqualTo("{\"add\":[\"job:4711\",\"sha:" + SHA + "\"],\"remove\":[\"ref:main\"]}");
        assertThat(MAPPER.readString("{\"add\":[\"job:4711\"],\"remove\":[]}", SelectorChange.class).unwrap()
                         .additions()
                         .unwrap()
                         .stream()
                         .map(Selector::wire)
                         .toList()).containsExactly("job:4711");
    }

    @Test
    void selectorChange_absentLists_areEmpty() {
        var change = MAPPER.readString("{}", SelectorChange.class).unwrap();

        assertThat(change.additions().unwrap()).isEmpty();
        assertThat(change.removals().unwrap()).isEmpty();
    }

    /// The CLI classifies bare arguments; on the wire a bare or short selector is refused, never guessed at.
    @Test
    void selectorChange_bareOrShortSelector_failsToParse() {
        assertThat(SelectorChange.selectorChange(List.of("4711"), List.of()).additions().isFailure()).isTrue();
        assertThat(SelectorChange.selectorChange(List.of(), List.of("sha:0cf5631")).removals().isFailure()).isTrue();
    }

    @Test
    void subscriberView_wireNames_roundTrip() {
        var view = SubscriberView.subscriberView("cto", List.of("job:4711", "ref:main"), 88);
        var json = MAPPER.writeAsString(view).unwrap();

        assertThat(json).isEqualTo("{\"name\":\"cto\",\"selectors\":[\"job:4711\",\"ref:main\"],\"cursor\":88}");
        assertThat(MAPPER.readString(json, SubscriberView.class).unwrap()).isEqualTo(view);
    }
}
