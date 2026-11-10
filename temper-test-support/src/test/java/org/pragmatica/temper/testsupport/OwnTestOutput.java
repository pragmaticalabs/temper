package org.pragmatica.temper.testsupport;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;

import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.LocationProvider;


/// Imports exactly the test output directory the analysing class was loaded from, whatever its package. A package filter
/// misses test classes outside `org.pragmatica`; scanning the whole classpath would also check the support test-jar from
/// every module. The code source of the module's own `HermeticLaunchArchTest` is that module's `target/test-classes`.
public final class OwnTestOutput implements LocationProvider {
    @Override
    public Set<Location> get(Class<?> testClass) {
        try {
            return Set.of(Location.of(Path.of(testClass.getProtectionDomain().getCodeSource().getLocation().toURI())));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the test output of " + testClass.getName(), e);
        }
    }
}
