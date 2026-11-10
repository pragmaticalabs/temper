package org.pragmatica.temper.domain.manifest.read;

import java.util.List;

import org.pragmatica.lang.Option;


/// One element of a parsed XML document: its name, its own character data and its child elements, in order.
/// Attributes, comments and processing instructions are dropped; the effective POM carries nothing the
/// engine needs in them.
public record XmlNode(String name, String text, List<XmlNode> children) {
    public XmlNode {
        children = List.copyOf(children);
    }

    public static XmlNode xmlNode(String name, String text, List<XmlNode> children) {
        return new XmlNode(name, text, children);
    }

    /// The first child element named `name`.
    public Option<XmlNode> child(String name) {
        return Option.from(children.stream().filter(child -> child.name()
                                                                  .equals(name)).findFirst());
    }

    /// Every child element named `name`.
    public List<XmlNode> children(String name) {
        return children.stream()
                       .filter(child -> child.name()
                                             .equals(name))
                       .toList();
    }

    /// The `element` children of the first `container` child: `plugins/plugin`, `goals/goal`; none without one.
    public List<XmlNode> children(String container, String element) {
        return child(container).map(found -> found.children(element))
                    .or(List.of());
    }

    /// Every child element of the first `container` child; none without one.
    public List<XmlNode> childrenOf(String container) {
        return child(container).map(XmlNode::children)
                    .or(List.of());
    }

    /// The trimmed text of the first child element named `name`, when it is present and not blank.
    public Option<String> text(String name) {
        return child(name).map(XmlNode::trimmed)
                    .filter(text -> !text.isEmpty());
    }

    /// This element's own text, trimmed.
    public String trimmed() {
        return text.trim();
    }

    public boolean hasChildren() {
        return ! children.isEmpty();
    }
}
