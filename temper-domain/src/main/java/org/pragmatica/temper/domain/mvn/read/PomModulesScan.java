package org.pragmatica.temper.domain.mvn.read;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.events.XMLEvent;


/// Thread-confined scan state over ONE `pom.xml`, fed StAX event by event: the project's `<modules>` entries
/// and those of every `<profile>` that has an `<activation>` element. Everything else is ignored.
final class PomModulesScan {
    private static final List<String> MODULE = List.of("project", "modules", "module");
    private static final List<String> PROFILE = List.of("project", "profiles", "profile");

    private static final List<String> PROFILE_ACTIVATION = List.of("project", "profiles", "profile", "activation");

    private static final List<String> PROFILE_MODULE = List.of("project", "profiles", "profile", "modules", "module");

    private final Deque<String> path = new ArrayDeque<>();
    private final List<String> modules = new ArrayList<>();
    private final List<String> profileModules = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private boolean profileActivated;

    /// One StAX event (the iterator yields `Object`).
    PomModulesScan accept(Object event) {
        return event instanceof XMLEvent xml
               ? onEvent(xml)
               : this;
    }

    /// The `<module>` entries found, trimmed, in document order.
    List<String> modules() {
        return List.copyOf(modules);
    }

    private PomModulesScan onEvent(XMLEvent event) {
        if (event.isStartElement()) {
            return start(event.asStartElement().getName().getLocalPart());
        }

        if (event.isCharacters()) {
            return characters(event.asCharacters().getData());
        }

        return event.isEndElement()
               ? end()
               : this;
    }

    private PomModulesScan start(String name) {
        path.addLast(name);
        text.setLength(0);
        if (at(PROFILE)) {
            profileModules.clear();
            profileActivated = false;
        }

        if (at(PROFILE_ACTIVATION)) {
            profileActivated = true;
        }

        return this;
    }

    private PomModulesScan characters(String data) {
        text.append(data);

        return this;
    }

    private PomModulesScan end() {
        if (at(MODULE)) {
            modules.add(text.toString().trim());
        }

        if (at(PROFILE_MODULE)) {
            profileModules.add(text.toString().trim());
        }

        if (at(PROFILE) && profileActivated) {
            modules.addAll(profileModules);
        }

        path.removeLast();

        return this;
    }

    private boolean at(List<String> elements) {
        return elements.size() == path.size() && elements.equals(List.copyOf(path));
    }
}
