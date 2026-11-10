package org.pragmatica.temper.domain.manifest;

import java.util.Set;

import org.pragmatica.lang.Option;


/// A method of a compiled class: its name and descriptor (which identify an override), its generic
/// `Signature` when it has one (a `@TestFactory` return type's element type lives only there), the binary
/// names of its runtime-visible annotations, its direct `@Tag` values, and whether it is `static` or `private`
/// (such a method is never a Jupiter test).
public record MethodInfo(String name,
                         String descriptor,
                         Option<String> genericSignature,
                         Set<String> annotations,
                         Set<String> tags,
                         boolean isStaticOrPrivate) {
    public MethodInfo {
        annotations = Set.copyOf(annotations);
        tags = Set.copyOf(tags);
    }

    public static MethodInfo methodInfo(String name,
                                        String descriptor,
                                        Option<String> genericSignature,
                                        Set<String> annotations,
                                        Set<String> tags,
                                        boolean isStaticOrPrivate) {
        return new MethodInfo(name, descriptor, genericSignature, annotations, tags, isStaticOrPrivate);
    }

    /// The override identity: name plus descriptor.
    public String signature() {
        return name + descriptor;
    }

    /// The erased result type, as a field descriptor (`V` for `void`).
    public String resultDescriptor() {
        return descriptor.substring(descriptor.indexOf(')') + 1);
    }

    /// The generic result type when the method has a `Signature`, else none.
    public Option<String> resultSignature() {
        return genericSignature.map(MethodInfo::resultOf);
    }

    /// The result part of a method signature: after the parameters, before any `^` throws clause.
    private static String resultOf(String methodSignature) {
        var result = methodSignature.substring(methodSignature.indexOf(')') + 1);
        var throwsAt = result.indexOf('^');

        return throwsAt < 0
               ? result
               : result.substring(0, throwsAt);
    }
}
