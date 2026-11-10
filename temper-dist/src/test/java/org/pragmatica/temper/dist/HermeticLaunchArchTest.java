package org.pragmatica.temper.dist;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import org.pragmatica.temper.testsupport.HermeticLaunchRules;
import org.pragmatica.temper.testsupport.OwnTestOutput;

/// Issue #121: this module's test classes start child processes only through HermeticProcess.
@AnalyzeClasses(locations = OwnTestOutput.class)
class HermeticLaunchArchTest {
    @ArchTest
    static final ArchTests launches = ArchTests.in(HermeticLaunchRules.class);
}
