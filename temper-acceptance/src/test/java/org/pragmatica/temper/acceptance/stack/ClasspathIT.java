package org.pragmatica.temper.acceptance.stack;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.manifest.ManifestRules;

import static org.assertj.core.api.Assertions.assertThat;

/// Integration finding F5 (M1 WP7 verification): the ITs' classpath holds the controller's and the worker's own
/// classes only, never their shaded runnable jars (attached as `-all`, used only to launch separate JVMs), so a
/// temper-domain, -store or -protocol class under test is reached from its own jar whatever the dependency order.
/// No docker needed.
class ClasspathIT {
    @Test
    void controllerAndWorkerJars_carryOnlyTheirOwnClasses_andTemperDomainComesFromItsOwn() throws Exception {
        var domain = Path.of(ManifestRules.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var jars = List.of(System.getProperty("java.class.path").split(File.pathSeparator))
                       .stream()
                       .map(Path::of)
                       .filter(path -> path.getFileName().toString().matches("temper-(controller|worker)-.*\\.jar"))
                       .toList();

        assertThat(domain.toString()).contains("temper-domain");
        assertThat(jars).as("the controller and worker jars on the classpath").hasSize(2);
        for (var jar : jars) {
            var own = "org/pragmatica/temper/" + (jar.getFileName().toString().startsWith("temper-controller") ? "controller/" : "worker/");
            try (var file = new JarFile(jar.toFile())) {
                assertThat(file.stream().map(JarEntry::getName).filter(name -> name.endsWith(".class")))
                    .as(jar + " holds only " + own)
                    .isNotEmpty()
                    .allMatch(name -> name.startsWith(own));
            }
        }
    }
}
