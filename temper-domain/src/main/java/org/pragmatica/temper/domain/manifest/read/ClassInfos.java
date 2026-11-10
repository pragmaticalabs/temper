package org.pragmatica.temper.domain.manifest.read;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.manifest.ClassInfo;
import org.pragmatica.temper.domain.manifest.ManifestError;
import org.pragmatica.temper.domain.manifest.MethodInfo;
import org.pragmatica.temper.domain.manifest.Nesting;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Reads one compiled class into a [ClassInfo] with the JDK's `java.lang.classfile` API (spec §5.2: no
/// dependency). Only runtime-visible annotations are read: JUnit's are all runtime-retained, and an
/// annotation that is not runtime-retained cannot affect test discovery. `ClassFile.parse` reports a malformed
/// class by exception, and parts of the model are parsed lazily, so the whole read is lifted.
public sealed interface ClassInfos {
    String NESTED = "org.junit.jupiter.api.Nested";
    String TAG = "org.junit.jupiter.api.Tag";
    String TAGS = "org.junit.jupiter.api.Tags";
    String DISPLAY_NAME = "org.junit.jupiter.api.DisplayName";

    /// Reads `classFile`; `file` names it in a failure (`ERROR(manifest: Malformed class file <file>)`).
    static Result<ClassInfo> read(String file, byte[] classFile) {
        return Result.lift(thrown -> ManifestError.MalformedClass.FACTORY.apply(file, Causes.fromThrowable(thrown)),
                           () -> classInfo(ClassFile.of().parse(classFile)));
    }

    private static ClassInfo classInfo(ClassModel model) {
        var annotations = annotations(model);
        var types = types(annotations);
        var ownEntry = ownEntry(model);
        var nesting = ownEntry.map(ClassInfos::nesting).or(Nesting.TOP_LEVEL);

        return ClassInfo.classInfo(binaryName(model.thisClass()),
                                   Option.from(model.superclass()).map(ClassInfos::binaryName),
                                   model.interfaces().stream().map(ClassInfos::binaryName).toList(),
                                   has(model.flags(), AccessFlag.ABSTRACT),
                                   has(model.flags(), AccessFlag.INTERFACE),
                                   ownEntry.flatMap(ClassInfos::outerOf),
                                   nesting,
                                   ownEntry.map(ClassInfos::isPrivate).or(false),
                                   types.contains(NESTED) && nesting == Nesting.INNER,
                                   types,
                                   tags(annotations),
                                   displayName(annotations),
                                   model.methods().stream().map(ClassInfos::methodInfo).toList());
    }

    /// A member class names its outer class; a local or anonymous class does not.
    private static Nesting nesting(InnerClassInfo entry) {
        return Option.from(entry.outerClass())
                     .map(_ -> memberNesting(entry))
                     .or(Nesting.LOCAL);
    }

    private static Nesting memberNesting(InnerClassInfo entry) {
        return entry.has(AccessFlag.STATIC)
               ? Nesting.STATIC_MEMBER
               : Nesting.INNER;
    }

    private static boolean isPrivate(InnerClassInfo entry) {
        return entry.has(AccessFlag.PRIVATE);
    }

    private static MethodInfo methodInfo(MethodModel method) {
        var annotations = annotations(method);

        return MethodInfo.methodInfo(method.methodName().stringValue(),
                                     method.methodType().stringValue(),
                                     genericSignature(method),
                                     types(annotations),
                                     tags(annotations),
                                     has(method.flags(), AccessFlag.STATIC) || has(method.flags(), AccessFlag.PRIVATE));
    }

    private static Option<String> genericSignature(MethodModel method) {
        return Option.from(method.findAttribute(Attributes.signature()))
                     .map(SignatureAttribute::signature)
                     .map(Utf8Entry::stringValue);
    }

    private static boolean has(AccessFlags flags, AccessFlag flag) {
        return flags.has(flag);
    }

    /// The `InnerClasses` entry that describes the class itself; a top-level class has none.
    private static Option<InnerClassInfo> ownEntry(ClassModel model) {
        var self = model.thisClass().asInternalName();

        return Option.from(model.findAttribute(Attributes.innerClasses())).flatMap(attribute -> entryOf(attribute, self));
    }

    private static Option<InnerClassInfo> entryOf(InnerClassesAttribute attribute, String self) {
        return Option.from(attribute.classes()
                                    .stream()
                                    .filter(inner -> inner.innerClass()
                                                          .asInternalName()
                                                          .equals(self))
                                    .findFirst());
    }

    private static Option<String> outerOf(InnerClassInfo inner) {
        return Option.from(inner.outerClass()).map(ClassInfos::binaryName);
    }

    private static List<Annotation> annotations(AttributedElement element) {
        return Option.from(element.findAttribute(Attributes.runtimeVisibleAnnotations()))
                     .map(RuntimeVisibleAnnotationsAttribute::annotations)
                     .or(List.of());
    }

    private static Set<String> types(List<Annotation> annotations) {
        return annotations.stream()
                          .map(ClassInfos::typeOf)
                          .collect(Collectors.toUnmodifiableSet());
    }

    /// `@Tag` values, and the `@Tag`s inside a `@Tags` container.
    private static Set<String> tags(List<Annotation> annotations) {
        return annotations.stream()
                          .flatMap(ClassInfos::tagValues)
                          .collect(Collectors.toUnmodifiableSet());
    }

    /// The value of a directly present `@DisplayName`, raw: surefire reads it by reflection, untrimmed, neither
    /// inherited nor through a meta-annotation.
    private static Option<String> displayName(List<Annotation> annotations) {
        return Option.from(annotations.stream()
                                      .filter(annotation -> typeOf(annotation).equals(DISPLAY_NAME))
                                      .findFirst())
                     .flatMap(ClassInfos::value)
                     .flatMap(ClassInfos::text);
    }

    private static Stream<String> tagValues(Annotation annotation) {
        return switch (typeOf(annotation)) {
            case TAG -> value(annotation).flatMap(ClassInfos::text).stream();
            case TAGS -> value(annotation).stream().flatMap(ClassInfos::nestedTags);
            default -> Stream.empty();
        };
    }

    private static Stream<String> nestedTags(AnnotationValue value) {
        return value instanceof AnnotationValue.OfArray array
               ? array.values()
                      .stream()
                      .flatMap(ClassInfos::tagOf)
               : Stream.empty();
    }

    private static Stream<String> tagOf(AnnotationValue value) {
        return value instanceof AnnotationValue.OfAnnotation nested
               ? tagValues(nested.annotation())
               : Stream.empty();
    }

    private static Option<AnnotationValue> value(Annotation annotation) {
        return Option.from(annotation.elements()
                                     .stream()
                                     .filter(element -> element.name()
                                                               .equalsString("value"))
                                     .findFirst()).map(AnnotationElement::value);
    }

    private static Option<String> text(AnnotationValue value) {
        return value instanceof AnnotationValue.OfString string
               ? some(string.stringValue())
               : none();
    }

    /// `Lorg/junit/jupiter/api/Test;` → `org.junit.jupiter.api.Test`.
    private static String typeOf(Annotation annotation) {
        return nameOf(annotation.className().stringValue());
    }

    /// A type's field descriptor (`Lorg/junit/jupiter/api/Test;`) as its binary name.
    private static String nameOf(String descriptor) {
        return descriptor.substring(1,
                                    descriptor.length() - 1)
                         .replace('/', '.');
    }

    private static String binaryName(ClassEntry entry) {
        return entry.asInternalName()
                    .replace('/', '.');
    }

    record unused() implements ClassInfos {}
}
