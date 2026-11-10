package org.pragmatica.temper.domain.manifest;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.pragmatica.temper.domain.manifest.read.ClassInfos;

/// Test fixture: a class file synthesised with `java.lang.classfile`, then read back through the production
/// reader ([ClassInfos]). The fixtures exist only as bytes, so they can never run as temper's own tests.
final class SyntheticClass {
    static final String TEST = "org.junit.jupiter.api.Test";
    static final String JUNIT4_TEST = "org.junit.Test";
    static final String PARAMETERIZED = "org.junit.jupiter.params.ParameterizedTest";
    static final String NESTED = "org.junit.jupiter.api.Nested";
    static final String TAG = "org.junit.jupiter.api.Tag";
    static final String TAGS = "org.junit.jupiter.api.Tags";
    static final String RUN_WITH = "org.junit.runner.RunWith";
    static final String DISPLAY_NAME = "org.junit.jupiter.api.DisplayName";

    private final String name;
    private String superName = "java.lang.Object";
    private final List<String> interfaces = new ArrayList<>();
    private final List<AccessFlag> flags = new ArrayList<>(List.of(AccessFlag.PUBLIC, AccessFlag.SUPER));
    private final List<Annotation> annotations = new ArrayList<>();
    private final List<MethodSpec> methods = new ArrayList<>();
    private Optional<String> outer = Optional.empty();
    private boolean hasInnerEntry;
    private final List<AccessFlag> innerFlags = new ArrayList<>(List.of(AccessFlag.PUBLIC));

    /// A method's access flags: the fixtures need these five combinations only.
    private enum MethodAccess {
        PUBLIC(ClassFile.ACC_PUBLIC),
        PUBLIC_STATIC(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC),
        PUBLIC_ABSTRACT(ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT),
        PRIVATE(ClassFile.ACC_PRIVATE);

        private final int flags;

        MethodAccess(int flags) {
            this.flags = flags;
        }
    }

    private record MethodSpec(String name, List<Annotation> annotations, MethodAccess access, MethodTypeDesc type, Optional<String> signature) {}

    private static final MethodTypeDesc NO_ARGS = MethodTypeDesc.of(ConstantDescs.CD_void);

    private SyntheticClass(String name) {
        this.name = name;
    }

    static SyntheticClass type(String name) {
        return new SyntheticClass(name);
    }

    /// A concrete class with one `@Test` method `test`.
    static SyntheticClass testClass(String name) {
        return type(name).method("test", TEST);
    }

    SyntheticClass extending(String superName) {
        this.superName = superName;
        return this;
    }

    SyntheticClass implementing(String... names) {
        interfaces.addAll(List.of(names));
        return this;
    }

    SyntheticClass asAbstract() {
        flags.add(AccessFlag.ABSTRACT);
        return this;
    }

    SyntheticClass asInterface() {
        flags.remove(AccessFlag.SUPER);
        flags.add(AccessFlag.INTERFACE);
        flags.add(AccessFlag.ABSTRACT);
        return this;
    }

    SyntheticClass asAnnotation() {
        asInterface();
        flags.add(AccessFlag.ANNOTATION);
        interfaces.add("java.lang.annotation.Annotation");
        return this;
    }

    /// A JUnit `@Nested` (non-static) member class of `outerName`.
    SyntheticClass nestedIn(String outerName) {
        return innerOf(outerName).annotated(NESTED);
    }

    /// A non-static member class of `outerName`, not `@Nested`.
    SyntheticClass innerOf(String outerName) {
        outer = Optional.of(outerName);
        hasInnerEntry = true;
        return this;
    }

    /// A static member class of `outerName`, not `@Nested`.
    SyntheticClass memberOf(String outerName) {
        innerFlags.add(AccessFlag.STATIC);
        return innerOf(outerName);
    }

    /// Neither the class flags nor its `InnerClasses` entry say `public`.
    SyntheticClass asPackagePrivate() {
        flags.remove(AccessFlag.PUBLIC);
        innerFlags.remove(AccessFlag.PUBLIC);
        return this;
    }

    /// Marks this member class `private` in its `InnerClasses` entry.
    SyntheticClass asPrivateMember() {
        innerFlags.remove(AccessFlag.PUBLIC);
        innerFlags.add(AccessFlag.PRIVATE);
        return this;
    }

    /// A local class: an `InnerClasses` entry without an outer class.
    SyntheticClass asLocal() {
        hasInnerEntry = true;
        outer = Optional.empty();
        return this;
    }

    SyntheticClass annotated(String... types) {
        for (var type : types) {
            annotations.add(Annotation.of(ClassDesc.of(type)));
        }
        return this;
    }

    SyntheticClass tagged(String... tags) {
        for (var tag : tags) {
            annotations.add(tag(tag));
        }
        return this;
    }

    /// Class-level `@Tags({@Tag(a), @Tag(b)})`.
    SyntheticClass tagsContainer(String... tags) {
        var values = new ArrayList<AnnotationValue>();
        for (var tag : tags) {
            values.add(AnnotationValue.ofAnnotation(tag(tag)));
        }
        annotations.add(Annotation.of(ClassDesc.of(TAGS), AnnotationElement.of("value", AnnotationValue.ofArray(values))));
        return this;
    }

    /// Class-level `@DisplayName(value)`.
    SyntheticClass displayName(String value) {
        annotations.add(Annotation.of(ClassDesc.of(DISPLAY_NAME), AnnotationElement.ofString("value", value)));
        return this;
    }

    /// Class-level `@DisplayNameGeneration(generator.class)`.
    SyntheticClass displayNameGeneration(String generator) {
        annotations.add(Annotation.of(ClassDesc.of("org.junit.jupiter.api.DisplayNameGeneration"),
                                      AnnotationElement.of("value", AnnotationValue.ofClass(ClassDesc.of(generator)))));
        return this;
    }

    /// Class-level `@RunWith(runner.class)`.
    SyntheticClass runWith(String runner) {
        annotations.add(Annotation.of(ClassDesc.of(RUN_WITH), AnnotationElement.of("value", AnnotationValue.ofClass(ClassDesc.of(runner)))));
        return this;
    }

    SyntheticClass method(String methodName, String... annotationTypes) {
        return method(methodName, List.of(), annotationTypes);
    }

    SyntheticClass taggedMethod(String methodName, List<String> tags, String... annotationTypes) {
        return method(methodName, tags, annotationTypes);
    }

    SyntheticClass staticMethod(String methodName, String... annotationTypes) {
        methods.add(new MethodSpec(methodName, annotationsOf(List.of(), annotationTypes), MethodAccess.PUBLIC_STATIC, NO_ARGS, Optional.empty()));
        return this;
    }

    SyntheticClass privateMethod(String methodName, String... annotationTypes) {
        methods.add(new MethodSpec(methodName, annotationsOf(List.of(), annotationTypes), MethodAccess.PRIVATE, NO_ARGS, Optional.empty()));
        return this;
    }

    private SyntheticClass method(String methodName, List<String> tags, String... annotationTypes) {
        var access = flags.contains(AccessFlag.ANNOTATION) ? MethodAccess.PUBLIC_ABSTRACT : MethodAccess.PUBLIC;
        methods.add(new MethodSpec(methodName, annotationsOf(tags, annotationTypes), access, NO_ARGS, Optional.empty()));
        return this;
    }

    /// A public instance method `methodName()` with the result type `result` and no generic `Signature`.
    SyntheticClass methodReturning(String methodName, ClassDesc result, String... annotationTypes) {
        methods.add(new MethodSpec(methodName, annotationsOf(List.of(), annotationTypes), MethodAccess.PUBLIC, MethodTypeDesc.of(result), Optional.empty()));
        return this;
    }

    /// A public instance method `methodName()` with the result type `result` and the generic method
    /// `signature` (`()Ljava/util/List<Lorg/junit/jupiter/api/DynamicTest;>;`).
    SyntheticClass genericMethodReturning(String methodName, ClassDesc result, String signature, String... annotationTypes) {
        methods.add(new MethodSpec(methodName, annotationsOf(List.of(), annotationTypes), MethodAccess.PUBLIC, MethodTypeDesc.of(result), Optional.of(signature)));
        return this;
    }

    private static List<Annotation> annotationsOf(List<String> tags, String... types) {
        var all = new ArrayList<Annotation>();
        for (var type : types) {
            all.add(Annotation.of(ClassDesc.of(type)));
        }
        for (var tag : tags) {
            all.add(tag(tag));
        }
        return all;
    }

    private static Annotation tag(String value) {
        return Annotation.of(ClassDesc.of(TAG), AnnotationElement.ofString("value", value));
    }

    byte[] bytes() {
        return ClassFile.of().build(ClassDesc.of(name), this::build);
    }

    /// The class as the production reader sees it.
    ClassInfo info() {
        return ClassInfos.read(name.replace('.', '/') + ".class", bytes())
                         .onFailure(cause -> Assertions.fail(cause.message()))
                         .unwrap();
    }

    private void build(ClassBuilder builder) {
        builder.withFlags(flags.toArray(AccessFlag[]::new));
        builder.withSuperclass(ClassDesc.of(superName));
        builder.withInterfaceSymbols(interfaces.stream().map(ClassDesc::of).toList());
        if (!annotations.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(annotations));
        }
        if (hasInnerEntry) {
            builder.with(InnerClassesAttribute.of(innerClass()));
        }
        for (var method : methods) {
            builder.withMethod(method.name(), method.type(), method.access().flags, mb -> body(mb, method));
        }
    }

    private InnerClassInfo innerClass() {
        var simple = name.substring(name.lastIndexOf('$') + 1);
        return InnerClassInfo.of(ClassDesc.of(name),
                                 outer.map(ClassDesc::of),
                                 Optional.of(simple),
                                 innerFlags.toArray(AccessFlag[]::new));
    }

    /// Returns the result type's default value: nothing, `null`, or `0` for an `int`-like result (the only primitive results the fixtures use).
    private static void returnDefault(CodeBuilder code, TypeKind kind) {
        switch (kind) {
            case VOID -> code.return_();
            case REFERENCE -> code.aconst_null().areturn();
            default -> code.iconst_0().ireturn();
        }
    }

    private static void body(MethodBuilder builder, MethodSpec method) {
        if (!method.annotations().isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(method.annotations()));
        }
        method.signature().ifPresent(signature -> builder.with(SignatureAttribute.of(MethodSignature.parseFrom(signature))));
        if (method.access() != MethodAccess.PUBLIC_ABSTRACT) {
            builder.withCode(code -> returnDefault(code, TypeKind.from(method.type().returnType())));
        }
    }
}
