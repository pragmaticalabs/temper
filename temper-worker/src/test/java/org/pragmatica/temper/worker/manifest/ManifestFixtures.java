package org.pragmatica.temper.worker.manifest;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;

/// Manifest test inputs: class files synthesised with `java.lang.classfile` (they exist only as bytes, so they never
/// run as temper's own tests) and effective-POM projects for a workspace.
public final class ManifestFixtures {
    static final String TEST = "org.junit.jupiter.api.Test";

    private ManifestFixtures() {}

    /// An effective-POM `<project>` for `<ws>/<module>` with surefire bound to `test`.
    public static String project(Path workspace, String module) {
        var base = workspace.resolve(module);

        return """
               <project>
                 <groupId>org.acme</groupId>
                 <artifactId>%s</artifactId>
                 <build>
                   <testSourceDirectory>%s/src/test/java</testSourceDirectory>
                   <outputDirectory>%s/target/classes</outputDirectory>
                   <testOutputDirectory>%s/target/test-classes</testOutputDirectory>
                   <directory>%s/target</directory>
                   <plugins>
                     <plugin>
                       <artifactId>maven-surefire-plugin</artifactId>
                       <version>3.5.4</version>
                       <executions>
                         <execution><id>default-test</id><phase>test</phase><goals><goal>test</goal></goals></execution>
                       </executions>
                     </plugin>
                   </plugins>
                 </build>
               </project>
               """.formatted(module, base, base, base, base);
    }

    /// A class file: concrete or abstract, extending `superName`, with one `@Test` method per name.
    public static byte[] classBytes(String name, String superName, boolean isAbstract, String... testMethods) {
        return ClassFile.of()
                        .build(ClassDesc.of(name),
                               builder -> {
                                   builder.withFlags(isAbstract
                                                     ? new AccessFlag[] {AccessFlag.PUBLIC, AccessFlag.SUPER, AccessFlag.ABSTRACT}
                                                     : new AccessFlag[] {AccessFlag.PUBLIC, AccessFlag.SUPER});
                                   builder.withSuperclass(ClassDesc.of(superName));
                                   for (var method : testMethods) {
                                       builder.withMethod(method,
                                                          MethodTypeDesc.of(ConstantDescs.CD_void),
                                                          ClassFile.ACC_PUBLIC,
                                                          body -> {
                                                              body.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of(TEST))));
                                                              body.withCode(code -> code.return_());
                                                          });
                                   }
                               });
    }

}
