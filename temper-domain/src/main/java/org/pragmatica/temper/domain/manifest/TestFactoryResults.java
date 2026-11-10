package org.pragmatica.temper.domain.manifest;

import java.util.Set;
import java.util.regex.Pattern;


/// Jupiter 5.13's `@TestFactory` result rule (`IsTestFactoryMethod`): a factory whose declared result type is
/// not compatible is not a test and is never executed.
///
/// - **Compatible:** a `DynamicNode` (`DynamicTest`, `DynamicContainer`), an array of one, `Object` or
///   `Object[]`; or a non-array type convertible to a stream (`Stream`, the primitive streams, `Iterable`,
///   `Collection`s, `Iterator`) whose single type argument is a `DynamicNode`, `?`, `? extends Object` or
///   `? extends` a `DynamicNode`; a raw container type is compatible too.
/// - **Incompatible:** `void`, a primitive, any other array, a container of anything else, and a `java.lang`
///   type such as `String` (none of them is convertible to a stream).
/// - **Unclassified:** any other type (a reactor or library type, a JDK type outside the lists below) counts
///   as compatible. The engine cannot see its hierarchy, and a factory is optional (it may report nothing):
///   counting an incompatible one as a test can only hide a drift, never claim a class is missing.
public sealed interface TestFactoryResults {
    /// The `DynamicNode` family in internal form; JUnit's constructors keep it closed.
    Set<String> DYNAMIC_NODES = Set.of("org/junit/jupiter/api/DynamicNode",
                                       "org/junit/jupiter/api/DynamicTest",
                                       "org/junit/jupiter/api/DynamicContainer");

    /// The JDK types known to be convertible to a stream, in internal form.
    Set<String> STREAMABLE = Set.of("java/util/stream/Stream",
                                    "java/util/stream/IntStream",
                                    "java/util/stream/LongStream",
                                    "java/util/stream/DoubleStream",
                                    "java/lang/Iterable",
                                    "java/util/Iterator",
                                    "java/util/ListIterator",
                                    "java/util/Collection",
                                    "java/util/SequencedCollection",
                                    "java/util/List",
                                    "java/util/Set",
                                    "java/util/SequencedSet",
                                    "java/util/SortedSet",
                                    "java/util/NavigableSet",
                                    "java/util/Queue",
                                    "java/util/Deque",
                                    "java/util/ArrayList",
                                    "java/util/LinkedList",
                                    "java/util/HashSet",
                                    "java/util/LinkedHashSet",
                                    "java/util/TreeSet",
                                    "java/util/ArrayDeque");

    /// The arrays a factory may return.
    Set<String> ARRAYS = Set.of("[Ljava/lang/Object;",
                                "[Lorg/junit/jupiter/api/DynamicNode;",
                                "[Lorg/junit/jupiter/api/DynamicTest;",
                                "[Lorg/junit/jupiter/api/DynamicContainer;");

    /// The one type argument a container may have: a `DynamicNode`, `?`, `? extends Object`, `? extends` a `DynamicNode`.
    Pattern ELEMENT = Pattern.compile("\\*|\\+Ljava/lang/Object;|\\+?Lorg/junit/jupiter/api/Dynamic(Node|Test|Container);");

    /// Whether Jupiter accepts `method`'s declared result type for a `@TestFactory`.
    static boolean isCompatible(MethodInfo method) {
        var result = method.resultDescriptor();

        return result.startsWith("L")
               ? isCompatibleType(result.substring(1, result.length() - 1),
                                  method)
               : ARRAYS.contains(result);
    }

    private static boolean isCompatibleType(String type, MethodInfo method) {
        return isNodeOrObject(type) || isCompatibleContainer(type, method);
    }

    private static boolean isNodeOrObject(String type) {
        return DYNAMIC_NODES.contains(type) || type.equals("java/lang/Object");
    }

    /// A known stream-convertible type by its type argument; a `java.lang` type is none; any other is unclassified.
    private static boolean isCompatibleContainer(String type, MethodInfo method) {
        return STREAMABLE.contains(type)
               ? hasDynamicElements(type, method)
               : !isJavaLang(type);
    }

    /// A raw container (no `Signature`, or no type arguments in it) is compatible; otherwise its one type
    /// argument decides.
    private static boolean hasDynamicElements(String type, MethodInfo method) {
        var parameterized = "L" + type + "<";

        return method.resultSignature()
                     .filter(result -> result.startsWith(parameterized))
                     .map(result -> ELEMENT.matcher(result.substring(parameterized.length(),
                                                                     result.length() - 2)).matches())
                     .or(true);
    }

    /// A type of `java.lang` itself (not of a subpackage).
    private static boolean isJavaLang(String type) {
        return type.startsWith("java/lang/") && type.indexOf('/', "java/lang/".length()) < 0;
    }

    record unused() implements TestFactoryResults {}
}
