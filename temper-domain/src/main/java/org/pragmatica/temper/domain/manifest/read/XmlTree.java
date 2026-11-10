package org.pragmatica.temper.domain.manifest.read;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.events.XMLEvent;


/// Thread-confined scan state that builds ONE XML document's [XmlNode] tree, fed StAX event by event.
/// Attributes, comments and processing instructions are ignored.
final class XmlTree {
    private final Deque<Builder> open = new ArrayDeque<>();

    XmlTree() {
        open.push(Builder.builder("#document"));
    }

    /// One StAX event (the iterator yields `Object`).
    XmlTree accept(Object event) {
        return event instanceof XMLEvent xml
               ? onEvent(xml)
               : this;
    }

    /// The document element; a document without one fails (inside the caller's lift).
    XmlNode document() {
        return open.getFirst()
                   .build()
                   .children()
                   .getFirst();
    }

    private XmlTree onEvent(XMLEvent event) {
        if (event.isStartElement()) {
            return start(event.asStartElement().getName().getLocalPart());
        }

        if (event.isCharacters()) {
            return text(event.asCharacters().getData());
        }

        return event.isEndElement()
               ? end()
               : this;
    }

    private XmlTree start(String name) {
        open.push(Builder.builder(name));

        return this;
    }

    private XmlTree text(String text) {
        open.getFirst().text().append(text);

        return this;
    }

    private XmlTree end() {
        var done = open.pop().build();

        open.getFirst().children().add(done);

        return this;
    }

    /// An element still open during the scan.
    private record Builder(String name, StringBuilder text, List<XmlNode> children) {
        static Builder builder(String name) {
            return new Builder(name, new StringBuilder(), new ArrayList<>());
        }

        XmlNode build() {
            return XmlNode.xmlNode(name, text.toString(), children);
        }
    }
}
