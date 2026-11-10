package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;


/// What the rules need from one compiled class. Names are binary names in dotted form (`a.b.Outer$Inner`),
/// as the report's `classname` uses them. `annotations` are the class's runtime-visible annotation types;
/// `tags` its direct `@Tag`/`@Tags` values; `displayName` the raw value of its own `@DisplayName` (directly present,
/// as surefire reads it: [ManifestRules] names the class's report entry by it); `outerName` is the enclosing class of a member class; `nesting`
/// and `isPrivate` come from the class's own `InnerClasses` entry; `nested` marks a JUnit `@Nested` class
/// (annotated and [Nesting#INNER]: JUnit ignores `@Nested` on a static class).
public record ClassInfo(String binaryName,
                        Option<String> superName,
                        List<String> interfaces,
                        boolean isAbstract,
                        boolean isInterface,
                        Option<String> outerName,
                        Nesting nesting,
                        boolean isPrivate,
                        boolean nested,
                        Set<String> annotations,
                        Set<String> tags,
                        Option<String> displayName,
                        List<MethodInfo> methods) {
    public ClassInfo {
        interfaces = List.copyOf(interfaces);
        annotations = Set.copyOf(annotations);
        tags = Set.copyOf(tags);
        methods = List.copyOf(methods);
    }

    public static ClassInfo classInfo(String binaryName,
                                      Option<String> superName,
                                      List<String> interfaces,
                                      boolean isAbstract,
                                      boolean isInterface,
                                      Option<String> outerName,
                                      Nesting nesting,
                                      boolean isPrivate,
                                      boolean nested,
                                      Set<String> annotations,
                                      Set<String> tags,
                                      Option<String> displayName,
                                      List<MethodInfo> methods) {
        return new ClassInfo(binaryName,
                             superName,
                             interfaces,
                             isAbstract,
                             isInterface,
                             outerName,
                             nesting,
                             isPrivate,
                             nested,
                             annotations,
                             tags,
                             displayName,
                             methods);
    }
}
